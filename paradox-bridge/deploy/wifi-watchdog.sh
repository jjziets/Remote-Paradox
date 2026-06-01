#!/usr/bin/env bash
# wifi-watchdog.sh — Periodically verify WiFi (NetworkManager) has LAN connectivity;
# if not, bring radio/networking up and re-activate the saved connection.
#
# Environment (optional):
#   WIFI_WATCHDOG_IFACE   — default wlan0
#   WIFI_WATCHDOG_NM_CONN — NetworkManager connection id (default preconfigured)
#   WIFI_WATCHDOG_DRY_RUN — if 1, print nmcli actions instead of running them
#
# Intended to be run from systemd.timer every few minutes on the Pi.
set -u

IFACE="${WIFI_WATCHDOG_IFACE:-wlan0}"
NM_CONN="${WIFI_WATCHDOG_NM_CONN:-preconfigured}"
DRY="${WIFI_WATCHDOG_DRY_RUN:-0}"

log() {
  if command -v logger >/dev/null 2>&1; then
    logger -t wifi-watchdog "$*"
  fi
}

nmcli_run() {
  if [[ "$DRY" == "1" ]]; then
    printf 'DRY_RUN:nmcli %s\n' "$(printf '%q ' "$@" | sed 's/[[:space:]]*$//')"
    return 0
  fi
  nmcli "$@"
}

wifi_recover() {
  log "recovering WiFi (iface=$IFACE conn=$NM_CONN)"
  nmcli_run radio wifi on || true
  nmcli_run networking on || true
  if ! nmcli_run connection up "$NM_CONN"; then
    nmcli_run dev connect "$IFACE" || true
  fi
}

# Interface must exist (driver loaded / device present)
if ! ip link show "$IFACE" >/dev/null 2>&1; then
  exit 0
fi

state=""
state=$(nmcli -g GENERAL.STATE dev show "$IFACE" 2>/dev/null || true)

gw=""
gw=$(ip -4 route show default dev "$IFACE" 2>/dev/null | awk '{print $3; exit}') || true

healthy=0
# Match NM "…(connected)" — do not use plain "connected" (matches "disconnected").
if [[ "$state" == *"(connected)"* ]] && [[ -n "$gw" ]]; then
  if ping -c1 -W2 "$gw" >/dev/null 2>&1; then
    healthy=1
  fi
fi

if [[ "$healthy" == "1" ]]; then
  exit 0
fi

log "unhealthy: state=${state:-<empty>} gw=${gw:-<none>}"
wifi_recover
exit 0
