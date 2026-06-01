#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="/opt/paradox-bridge"
STAGING_DIR="$INSTALL_DIR/staging"
STATUS_FILE="$INSTALL_DIR/update_status.json"
VERSION_FILE="$INSTALL_DIR/CURRENT_VERSION"
VENV="$INSTALL_DIR/venv"
MAINTENANCE_STATE_DIR="/var/lib/paradox-bridge/maintenance"

if [ ! -d "$STAGING_DIR" ]; then
    echo "[apply_update] No staging directory found"
    exit 1
fi

NEW_VERSION=$(python3 -c "import json; print(json.load(open('$STATUS_FILE')).get('new_version','unknown'))" 2>/dev/null || echo "unknown")
RELEASE_TAG=$(python3 -c "import json; print(json.load(open('$STATUS_FILE')).get('tag',''))" 2>/dev/null || echo "")

# The tarball extracts to a directory like jjziets-Remote-Paradox-<hash>/
EXTRACTED=$(find "$STAGING_DIR" -maxdepth 1 -type d ! -path "$STAGING_DIR" | head -1)
if [ -z "$EXTRACTED" ]; then
    echo "[apply_update] No extracted directory found in staging"
    exit 1
fi

BRIDGE_SRC="$EXTRACTED/paradox-bridge"
if [ ! -d "$BRIDGE_SRC" ]; then
    echo "[apply_update] paradox-bridge directory not found in release"
    exit 1
fi

echo "[apply_update] Applying version $NEW_VERSION..."

rsync -a --delete \
    --exclude='__pycache__' --exclude='*.pyc' \
    "$BRIDGE_SRC/src/paradox_bridge/" "$INSTALL_DIR/src/paradox_bridge/"

if [ -f "$BRIDGE_SRC/pyproject.toml" ]; then
    cp "$BRIDGE_SRC/pyproject.toml" "$INSTALL_DIR/pyproject.toml"
fi

if [ -f "$BRIDGE_SRC/scripts/updater.py" ]; then
    cp "$BRIDGE_SRC/scripts/updater.py" "$INSTALL_DIR/scripts/updater.py"
fi
if [ -f "$BRIDGE_SRC/scripts/apply_update.sh" ]; then
    cp "$BRIDGE_SRC/scripts/apply_update.sh" "$INSTALL_DIR/scripts/apply_update.sh"
    chmod +x "$INSTALL_DIR/scripts/apply_update.sh"
fi
if [ -f "$BRIDGE_SRC/scripts/maintenance_job.sh" ]; then
    cp "$BRIDGE_SRC/scripts/maintenance_job.sh" "$INSTALL_DIR/scripts/maintenance_job.sh"
    chmod +x "$INSTALL_DIR/scripts/maintenance_job.sh"
fi

SERVICE_USER=$(systemctl show -p User --value paradox-bridge 2>/dev/null || true)
SERVICE_GROUP=$(systemctl show -p Group --value paradox-bridge 2>/dev/null || true)
if [ -z "$SERVICE_USER" ]; then
    SERVICE_USER=$(stat -c '%U' "$INSTALL_DIR" 2>/dev/null || echo "")
fi
if [ -z "$SERVICE_GROUP" ]; then
    SERVICE_GROUP="$SERVICE_USER"
fi
mkdir -p "$MAINTENANCE_STATE_DIR/jobs" "$MAINTENANCE_STATE_DIR/logs"
if [ -n "$SERVICE_USER" ]; then
    chown -R "$SERVICE_USER:$SERVICE_GROUP" /var/lib/paradox-bridge
fi
chmod 0750 /var/lib/paradox-bridge "$MAINTENANCE_STATE_DIR"
if [ -f "$BRIDGE_SRC/deploy/setup-boot-fsck.sh" ]; then
    mkdir -p "$INSTALL_DIR/deploy"
    cp "$BRIDGE_SRC/deploy/setup-boot-fsck.sh" "$INSTALL_DIR/deploy/setup-boot-fsck.sh"
    chmod +x "$INSTALL_DIR/deploy/setup-boot-fsck.sh"
fi
for deploy_script in boot-repair.sh setup-boot-repair.sh wifi-watchdog.sh setup-wifi-watchdog.sh; do
    if [ -f "$BRIDGE_SRC/deploy/$deploy_script" ]; then
        mkdir -p "$INSTALL_DIR/deploy"
        cp "$BRIDGE_SRC/deploy/$deploy_script" "$INSTALL_DIR/deploy/$deploy_script"
        chmod +x "$INSTALL_DIR/deploy/$deploy_script"
    fi
done

# Reinstall deps in case they changed
"$VENV/bin/pip" install -q -e "$INSTALL_DIR" 2>/dev/null || true

# Write bridge version from pyproject.toml (not from release tag)
BRIDGE_VER=$(python3 -c "
import re
with open('$INSTALL_DIR/pyproject.toml') as f:
    m = re.search(r'^version\s*=\s*\"([^\"]+)\"', f.read(), re.MULTILINE)
    print(m.group(1) if m else '$NEW_VERSION')
" 2>/dev/null || echo "$NEW_VERSION")
echo "$BRIDGE_VER" > "$VERSION_FILE"

python3 -c "
import json
s = json.load(open('$STATUS_FILE'))
s['pending'] = False
s['applied'] = True
json.dump(s, open('$STATUS_FILE', 'w'))
"

rm -rf "$STAGING_DIR"

if [ -x "$INSTALL_DIR/deploy/setup-boot-fsck.sh" ]; then
    echo "[apply_update] Ensuring boot-time filesystem repair is enabled..."
    "$INSTALL_DIR/deploy/setup-boot-fsck.sh" || echo "[apply_update] WARNING: boot fsck setup failed"
fi
if [ -x "$INSTALL_DIR/deploy/setup-wifi-watchdog.sh" ]; then
    echo "[apply_update] Ensuring WiFi watchdog is installed..."
    "$INSTALL_DIR/deploy/setup-wifi-watchdog.sh" || echo "[apply_update] WARNING: WiFi watchdog setup failed"
fi
if [ -x "$INSTALL_DIR/deploy/setup-boot-repair.sh" ]; then
    echo "[apply_update] Ensuring boot repair timer is installed..."
    "$INSTALL_DIR/deploy/setup-boot-repair.sh" || echo "[apply_update] WARNING: boot repair setup failed"
fi

echo "[apply_update] Configuring Bluetooth for LE-only (no audio profiles)..."
mkdir -p /etc/systemd/system/bluetooth.service.d
cat > /etc/systemd/system/bluetooth.service.d/disable-audio.conf <<'BTEOF'
[Service]
ExecStart=
ExecStart=/usr/libexec/bluetooth/bluetoothd --noplugin=a2dp,avrcp,hfp_hf,hfp_ag
BTEOF

if grep -q '^#ControllerMode = dual' /etc/bluetooth/main.conf 2>/dev/null; then
    sed -i 's/^#ControllerMode = dual/ControllerMode = le/' /etc/bluetooth/main.conf
elif ! grep -q '^ControllerMode' /etc/bluetooth/main.conf 2>/dev/null; then
    sed -i '/^\[General\]/a ControllerMode = le' /etc/bluetooth/main.conf
fi

systemctl daemon-reload

echo "[apply_update] Restarting services..."
systemctl restart paradox-bridge
systemctl restart bluetooth || true
sleep 2
systemctl restart paradox-ble || true

echo "[apply_update] Done. Version $NEW_VERSION applied."
