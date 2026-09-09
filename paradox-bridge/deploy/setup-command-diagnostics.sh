#!/usr/bin/env bash
# Install logging policy without changing the alarm state or restarting services.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "setup-command-diagnostics: run as root" >&2
    exit 1
fi
service_user=$(systemctl show -p User --value paradox-bridge)
service_group=$(systemctl show -p Group --value paradox-bridge)
service_user=${service_user:-root}
service_group=${service_group:-$(id -gn "$service_user")}
install -d -m 0750 -o "$service_user" -g "$service_group" /var/log/paradox-bridge/command-diagnostics
install -d -m 0755 /etc/systemd/system/paradox-bridge.service.d
cat >/etc/systemd/system/paradox-bridge.service.d/command-diagnostics.conf <<'EOF'
[Service]
Environment=PARADOX_DIAGNOSTICS_DIR=/var/log/paradox-bridge/command-diagnostics
EOF
systemctl daemon-reload
echo "Command diagnostics configured; activated on the next bridge restart."
