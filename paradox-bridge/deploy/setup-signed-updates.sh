#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "Run setup-signed-updates as root" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$HERE/../scripts/signed_update.py"
KEY="$HERE/bridge-release.pub"
/usr/bin/python3 -I -c 'from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey'
install -d -m 0755 /etc/paradox-updater /usr/local/lib/paradox-updater
install -d -m 0700 /var/lib/paradox-updater
if [[ -f /etc/paradox-updater/release-public.pem ]]; then
    cmp -s "$KEY" /etc/paradox-updater/release-public.pem || { echo "Pinned signing key differs; manual key rotation required" >&2; exit 1; }
else
    install -m 0644 "$KEY" /etc/paradox-updater/release-public.pem
fi
install -m 0644 "$SOURCE" /usr/local/lib/paradox-updater/signed_update.py
for wrapper in updater.py apply_update.sh; do
    if [[ ! "$HERE/../scripts/$wrapper" -ef "/opt/paradox-bridge/scripts/$wrapper" ]]; then
        install -m 0755 "$HERE/../scripts/$wrapper" "/opt/paradox-bridge/scripts/$wrapper"
    fi
done
install -m 0755 "$HERE/boot-repair.sh" /opt/paradox-bridge/scripts/boot-repair.sh
cat >/etc/systemd/system/paradox-signed-updater.service <<'UNIT'
[Unit]
Description=Verify and deploy CI-signed Paradox Bridge releases
After=network-online.target
Wants=network-online.target
[Service]
Type=oneshot
User=root
UMask=0077
ExecStart=/usr/bin/python3 -I /usr/local/lib/paradox-updater/signed_update.py
TimeoutStartSec=15min
Nice=10
UNIT
cat >/etc/systemd/system/paradox-signed-updater.timer <<'UNIT'
[Unit]
Description=Poll for signed bridge releases every five minutes
[Timer]
OnBootSec=3min
OnUnitInactiveSec=5min
RandomizedDelaySec=20
Persistent=true
[Install]
WantedBy=timers.target
UNIT
SERVICE_USER="$(systemctl show -p User --value paradox-bridge)"
[[ "$SERVICE_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo "Cannot determine bridge user" >&2; exit 1; }
printf '%s ALL=(root) NOPASSWD: /usr/bin/python3 -I /usr/local/lib/paradox-updater/signed_update.py --stage-only, /usr/bin/python3 -I /usr/local/lib/paradox-updater/signed_update.py --stage-only --force\n' "$SERVICE_USER" >/etc/sudoers.d/paradox-signed-updater
chmod 0440 /etc/sudoers.d/paradox-signed-updater
visudo -cf /etc/sudoers.d/paradox-signed-updater
systemctl disable --now paradox-updater.timer 2>/dev/null || true
systemctl daemon-reload
systemctl enable --now paradox-signed-updater.timer
echo "Signed pull deployment installed. Existing TLS keys, users and alarm configuration are unchanged."
