#!/usr/bin/env bash
# setup-power-hardening.sh — Keep the headless Pi awake and WiFi responsive.
set -euo pipefail

NM_CONN="${POWER_HARDENING_NM_CONN:-preconfigured}"
IFACE="${POWER_HARDENING_IFACE:-wlan0}"

echo "=== Remote Paradox — power hardening ==="

mkdir -p /etc/systemd/sleep.conf.d /etc/systemd/logind.conf.d /etc/NetworkManager/conf.d

cat >/etc/systemd/sleep.conf.d/remote-paradox-no-sleep.conf <<'EOF'
[Sleep]
AllowSuspend=no
AllowHibernation=no
AllowSuspendThenHibernate=no
AllowHybridSleep=no
EOF

cat >/etc/systemd/logind.conf.d/remote-paradox-no-sleep.conf <<'EOF'
[Login]
IdleAction=ignore
HandleSuspendKey=ignore
HandleHibernateKey=ignore
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
HandleLidSwitchDocked=ignore
EOF

cat >/etc/NetworkManager/conf.d/remote-paradox-wifi-powersave-off.conf <<'EOF'
[connection]
wifi.powersave = 2
EOF

systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target >/dev/null

if command -v nmcli >/dev/null 2>&1; then
  nmcli connection modify "$NM_CONN" 802-11-wireless.powersave 2 || true
fi

if command -v iw >/dev/null 2>&1; then
  iw dev "$IFACE" set power_save off || true
fi

if command -v iwconfig >/dev/null 2>&1; then
  iwconfig "$IFACE" power off || true
fi

systemctl daemon-reload
systemctl restart systemd-logind || true

echo "Sleep targets:"
systemctl is-enabled sleep.target suspend.target hibernate.target hybrid-sleep.target 2>&1 || true
echo "WiFi power-save policy:"
nmcli -f 802-11-wireless.powersave connection show "$NM_CONN" 2>/dev/null || true
echo "Done."
