#!/usr/bin/env bash
set -euo pipefail

# Install a conservative recovery timer. It runs after boot and periodically,
# repairing interrupted package transactions and reinstalling the bridge only
# if the local service cannot come healthy.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SCRIPT_DIR}/boot-repair.sh"
DST_DIR="/opt/paradox-bridge/scripts"
DST="${DST_DIR}/boot-repair.sh"

if [[ $EUID -ne 0 ]]; then
    echo "setup-boot-repair: run as root" >&2
    exit 1
fi

if [[ ! -f "$SRC" ]]; then
    echo "setup-boot-repair: missing $SRC" >&2
    exit 1
fi

mkdir -p "$DST_DIR"
install -m 0755 "$SRC" "$DST"

cat >/etc/systemd/system/paradox-boot-repair.service <<'EOF'
[Unit]
Description=Remote Paradox conservative boot repair
After=network-online.target paradox-bridge.service
Wants=network-online.target

[Service]
Type=oneshot
User=root
ExecStart=/opt/paradox-bridge/scripts/boot-repair.sh
TimeoutStartSec=10min
EOF

cat >/etc/systemd/system/paradox-boot-repair.timer <<'EOF'
[Unit]
Description=Run Remote Paradox boot repair after startup and periodically

[Timer]
OnBootSec=4min
OnUnitActiveSec=6h
AccuracySec=2min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable paradox-boot-repair.timer
systemctl restart paradox-boot-repair.timer

echo "Installed $DST"
systemctl list-timers paradox-boot-repair.timer --no-pager || true
