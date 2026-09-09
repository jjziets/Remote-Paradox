#!/usr/bin/env bash
# state-recorder.sh — Lightweight one-second Pi state recorder.
set -uo pipefail

LOG_DIR="${STATE_RECORDER_LOG_DIR:-/var/log/paradox-bridge/state-recorder}"
INTERVAL_SEC="${STATE_RECORDER_INTERVAL_SEC:-1}"
RETENTION_MINUTES="${STATE_RECORDER_RETENTION_MINUTES:-1440}"
PRUNE_INTERVAL_SEC="${STATE_RECORDER_PRUNE_INTERVAL_SEC:-60}"
IFACE="${STATE_RECORDER_IFACE:-wlan0}"
BRIDGE_URL="${STATE_RECORDER_BRIDGE_URL:-http://127.0.0.1:8080/health}"
ONCE="${STATE_RECORDER_ONCE:-0}"
INTERVAL_MS="$(awk -v sec="$INTERVAL_SEC" 'BEGIN { printf "%d", sec * 1000 }')"

mkdir -p "$LOG_DIR"

last_prune=0

now_ms() {
  local ms
  ms="$(date +%s%3N 2>/dev/null || true)"
  if [[ "$ms" =~ ^[0-9]+$ ]]; then
    printf '%s' "$ms"
  else
    printf '%s000' "$(date +%s)"
  fi
}

read_first_line() {
  local file="$1"
  if [[ -r "$file" ]]; then
    IFS= read -r line <"$file" || true
    printf '%s' "$line"
  fi
}

collect_sample() {
  local epoch iso uptime load mem_avail root_avail temp throttled nm_state ip_addr gw bridge ble nginx network ssh avahi health states

  epoch="$(date +%s)"
  iso="$(date --iso-8601=seconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"
  uptime="$(awk '{printf "%.2f", $1}' /proc/uptime 2>/dev/null || printf 'unknown')"
  load="$(awk '{print $1","$2","$3}' /proc/loadavg 2>/dev/null || printf 'unknown')"
  mem_avail="$(awk '/MemAvailable/ {print $2; found=1} END {if (!found) print "unknown"}' /proc/meminfo 2>/dev/null)"
  root_avail="$(df -Pk / 2>/dev/null | awk 'NR==2 {print $4}')"
  temp="$(read_first_line /sys/class/thermal/thermal_zone0/temp)"

  if command -v vcgencmd >/dev/null 2>&1; then
    throttled="$(vcgencmd get_throttled 2>/dev/null | cut -d= -f2)"
  else
    throttled="unknown"
  fi

  if command -v nmcli >/dev/null 2>&1; then
    nm_state="$(nmcli -g GENERAL.STATE dev show "$IFACE" 2>/dev/null | tr ' ' '_' || true)"
  else
    nm_state="unknown"
  fi

  ip_addr="$(ip -4 addr show dev "$IFACE" 2>/dev/null | awk '/inet / {print $2; exit}')"
  gw="$(ip -4 route show default dev "$IFACE" 2>/dev/null | awk '{print $3; exit}')"

  if command -v systemctl >/dev/null 2>&1; then
    states="$(systemctl is-active paradox-bridge paradox-ble nginx NetworkManager ssh avahi-daemon 2>/dev/null || true)"
    set -- $states
    bridge="${1:-unknown}"
    ble="${2:-unknown}"
    nginx="${3:-unknown}"
    network="${4:-unknown}"
    ssh="${5:-unknown}"
    avahi="${6:-unknown}"
  else
    bridge="unknown"
    ble="unknown"
    nginx="unknown"
    network="unknown"
    ssh="unknown"
    avahi="unknown"
  fi

  if command -v curl >/dev/null 2>&1; then
    health="$(curl -fsS --max-time 0.25 "$BRIDGE_URL" >/dev/null 2>&1 && printf 'ok' || printf 'fail')"
  else
    health="unknown"
  fi

  printf 'epoch=%s iso=%s uptime=%s load=%s mem_avail_kb=%s root_avail_kb=%s temp_milli_c=%s throttled=%s iface=%s nm_state=%s ip=%s gw=%s bridge=%s ble=%s nginx=%s network=%s ssh=%s avahi=%s health=%s\n' \
    "$epoch" "$iso" "$uptime" "$load" "${mem_avail:-unknown}" "${root_avail:-unknown}" "${temp:-unknown}" "${throttled:-unknown}" \
    "$IFACE" "${nm_state:-unknown}" "${ip_addr:-none}" "${gw:-none}" "$bridge" "$ble" "$nginx" "$network" "$ssh" "$avahi" "$health"
}

prune_old_logs() {
  find "$LOG_DIR" -type f -name 'state-*.log' -mmin +"$RETENTION_MINUTES" -delete 2>/dev/null || true
}

next_sample_ms="$(now_ms)"

while true; do
  now="$(date +%s)"
  file="$LOG_DIR/state-$(date +%Y%m%d-%H%M).log"
  collect_sample >>"$file"

  if (( now - last_prune >= PRUNE_INTERVAL_SEC )); then
    prune_old_logs
    last_prune="$now"
  fi

  if [[ "$ONCE" == "1" ]]; then
    break
  fi
  next_sample_ms=$((next_sample_ms + INTERVAL_MS))
  current_ms="$(now_ms)"
  sleep_ms=$((next_sample_ms - current_ms))
  if (( sleep_ms > 0 )); then
    sleep "$(awk -v ms="$sleep_ms" 'BEGIN { printf "%.3f", ms / 1000 }')"
  else
    next_sample_ms="$current_ms"
  fi
done
