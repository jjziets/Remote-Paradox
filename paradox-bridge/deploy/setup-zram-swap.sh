#!/usr/bin/env bash
# setup-zram-swap.sh — Replace the on-SD swapfile with compressed RAM swap (zram).
# A swapfile on the SD card causes I/O stalls and wears/corrupts the card; zram
# keeps swap in RAM (zstd-compressed) so memory pressure never hits the card.
# Run on the Raspberry Pi as root after /opt/paradox-bridge is populated.
set -euo pipefail

ZRAM_SIZE="${ZRAM_SIZE:-384M}"          # logical size; zstd typically compresses ~2-3x
UNIT="/etc/systemd/system/zram-swap.service"
SYSCTL="/etc/sysctl.d/99-zram.conf"

if [[ $EUID -ne 0 ]]; then
    echo "setup-zram-swap: run as root" >&2
    exit 1
fi

echo "=== Remote Paradox — zram swap (size ${ZRAM_SIZE}) ==="

cat >"$UNIT" <<EOF
[Unit]
Description=Compressed RAM swap (zram)
DefaultDependencies=no
After=local-fs.target
Before=swap.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStartPre=-/sbin/modprobe zram
ExecStart=/bin/sh -c 'test -e /sys/block/zram0 || { echo no-zram0 >&2; exit 1; }; echo zstd > /sys/block/zram0/comp_algorithm 2>/dev/null || true; echo ${ZRAM_SIZE} > /sys/block/zram0/disksize; mkswap -L zram-swap /dev/zram0; swapon -p 100 /dev/zram0'
ExecStop=/bin/sh -c 'swapoff /dev/zram0 || true; echo 1 > /sys/block/zram0/reset || true'

[Install]
WantedBy=multi-user.target
EOF

cat >"$SYSCTL" <<'EOF'
# Prefer fast zram swap; tune readahead for swap-to-RAM.
vm.swappiness=100
vm.page-cluster=0
EOF

systemctl daemon-reload
systemctl enable --now zram-swap.service
sysctl --system >/dev/null 2>&1 || true

# Retire the dphys-swapfile (on-SD) once zram is active so we never leave the
# box without swap mid-transition.
if systemctl list-unit-files dphys-swapfile.service >/dev/null 2>&1; then
    systemctl disable --now dphys-swapfile 2>/dev/null || true
fi
swapoff /var/swap 2>/dev/null || true
[[ -f /var/swap ]] && rm -f /var/swap && echo "setup-zram-swap: removed on-SD /var/swap"

echo "setup-zram-swap: active swap ->"
/sbin/swapon --show || true
