#!/usr/bin/env bash
# setup-wifi-watchdog.sh — Install WiFi watchdog script + systemd timer (NetworkManager).
# Run on the Raspberry Pi as root after /opt/paradox-bridge is populated (same pattern as setup-nginx.sh).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SCRIPT_DIR}/wifi-watchdog.sh"
DST_DIR="/opt/paradox-bridge/scripts"
DST="${DST_DIR}/wifi-watchdog.sh"

echo "=== Remote Paradox — WiFi watchdog ==="

if [[ ! -f "$SRC" ]]; then
  echo "ERROR: $SRC not found"
  exit 1
fi

mkdir -p "$DST_DIR"
install -m 0755 "$SRC" "$DST"

cat >/etc/systemd/system/wifi-watchdog.service <<'EOF'
[Unit]
Description=Remote Paradox WiFi connectivity watchdog (one-shot)
After=network-online.target NetworkManager.service
Wants=network-online.target

[Service]
Type=oneshot
Environment=WIFI_WATCHDOG_IFACE=wlan0
Environment=WIFI_WATCHDOG_NM_CONN=preconfigured
ExecStart=/opt/paradox-bridge/scripts/wifi-watchdog.sh
EOF

cat >/etc/systemd/system/wifi-watchdog.timer <<'EOF'
[Unit]
Description=Run WiFi watchdog periodically

[Timer]
OnBootSec=3min
OnUnitActiveSec=5min
AccuracySec=1min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable wifi-watchdog.timer
systemctl restart wifi-watchdog.timer

echo "Installed $DST"
echo "Timer: systemctl list-timers wifi-watchdog.timer"
systemctl list-timers wifi-watchdog.timer --no-pager || true
echo "Done. Logs: journalctl -t wifi-watchdog -n 50"
