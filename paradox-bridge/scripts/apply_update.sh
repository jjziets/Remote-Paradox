#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="/opt/paradox-bridge"
STAGING_DIR="$INSTALL_DIR/staging"
STATUS_FILE="$INSTALL_DIR/update_status.json"
VERSION_FILE="$INSTALL_DIR/CURRENT_VERSION"
VENV="$INSTALL_DIR/venv"

if [ ! -d "$STAGING_DIR" ]; then
    echo "[apply_update] No staging directory found"
    exit 1
fi

NEW_VERSION=$(python3 -c "import json; print(json.load(open('$STATUS_FILE')).get('new_version','unknown'))" 2>/dev/null || echo "unknown")

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

# Reinstall deps in case they changed
"$VENV/bin/pip" install -q -e "$INSTALL_DIR" 2>/dev/null || true

echo "$NEW_VERSION" > "$VERSION_FILE"

python3 -c "
import json
s = json.load(open('$STATUS_FILE'))
s['pending'] = False
s['applied'] = True
json.dump(s, open('$STATUS_FILE', 'w'))
"

rm -rf "$STAGING_DIR"

echo "[apply_update] Restarting paradox-bridge..."
systemctl restart paradox-bridge

echo "[apply_update] Done. Version $NEW_VERSION applied."
