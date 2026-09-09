#!/usr/bin/env bash
# setup-state-recorder.sh — Install the one-second Pi state recorder.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SCRIPT_DIR}/state-recorder.sh"
DST_DIR="/opt/paradox-bridge/scripts"
DST="${DST_DIR}/state-recorder.sh"
LOG_DIR="/var/log/paradox-bridge/state-recorder"

if [[ $EUID -ne 0 ]]; then
    echo "setup-state-recorder: run as root" >&2
    exit 1
fi

if [[ ! -f "$SRC" ]]; then
    echo "setup-state-recorder: missing $SRC" >&2
    exit 1
fi

mkdir -p "$DST_DIR" "$LOG_DIR"
install -m 0755 "$SRC" "$DST"

cat >/etc/systemd/system/paradox-state-recorder.service <<'EOF'
[Unit]
Description=Remote Paradox one-second Pi state recorder
After=multi-user.target

[Service]
Type=simple
User=root
Environment=STATE_RECORDER_LOG_DIR=/var/log/paradox-bridge/state-recorder
Environment=STATE_RECORDER_INTERVAL_SEC=1
Environment=STATE_RECORDER_RETENTION_MINUTES=1440
Environment=STATE_RECORDER_PRUNE_INTERVAL_SEC=60
Environment=STATE_RECORDER_IFACE=wlan0
ExecStart=/opt/paradox-bridge/scripts/state-recorder.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now paradox-state-recorder.service

echo "Installed $DST"
systemctl status paradox-state-recorder.service --no-pager || true
echo "Logs: $LOG_DIR/state-YYYYMMDD-HHMM.log"
