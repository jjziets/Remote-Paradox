#!/usr/bin/env bash
# setup-nginx.sh — Install and configure nginx as a reverse proxy for Paradox Bridge.
# Run on the Raspberry Pi as root (or with sudo).
set -euo pipefail

CONF_SRC="/opt/paradox-bridge/deploy/nginx-paradox-bridge.conf"
CONF_DST="/etc/nginx/sites-available/paradox-bridge"
WEB_SRC="/opt/paradox-bridge/web-app"

echo "=== Paradox Bridge — nginx setup ==="

# 1. Install nginx
if ! command -v nginx &>/dev/null; then
    echo "[1/6] Installing nginx..."
    apt-get update -qq && apt-get install -y nginx
else
    echo "[1/6] nginx already installed"
fi

# 2. Copy config
echo "[2/6] Copying nginx config..."
cp "$CONF_SRC" "$CONF_DST"

# 3. Enable site, disable default
echo "[3/6] Enabling site..."
ln -sf "$CONF_DST" /etc/nginx/sites-enabled/paradox-bridge
rm -f /etc/nginx/sites-enabled/default

# 4. Verify web-app directory exists
if [ ! -f "$WEB_SRC/index.html" ]; then
    echo "WARNING: $WEB_SRC/index.html not found — web app won't be served."
fi

# 5. Test nginx config
echo "[4/6] Testing nginx config..."
nginx -t

# 6. Restart nginx
echo "[5/6] Restarting nginx..."
systemctl enable nginx
systemctl restart nginx

# 7. Verify the paradox-bridge service uses 127.0.0.1
echo "[6/6] Checking paradox-bridge config..."
CONFIG_FILE="${PARADOX_CONFIG:-/etc/paradox-bridge/config.json}"
if [ -f "$CONFIG_FILE" ]; then
    if grep -q '"api_host"' "$CONFIG_FILE"; then
        echo "  api_host is set in config — make sure it's 127.0.0.1"
    else
        echo "  api_host not in config — default 127.0.0.1 will be used"
    fi
fi

# 7. Harden SSH — disable password authentication
echo "[7/7] Hardening SSH (key-only access)..."
SSHD_CONF="/etc/ssh/sshd_config"
if grep -q "^PasswordAuthentication yes" "$SSHD_CONF" 2>/dev/null || \
   ! grep -q "^PasswordAuthentication no" "$SSHD_CONF" 2>/dev/null; then
    # Ensure key-only auth
    sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' "$SSHD_CONF"
    sed -i 's/^#\?ChallengeResponseAuthentication.*/ChallengeResponseAuthentication no/' "$SSHD_CONF"
    sed -i 's/^#\?UsePAM.*/UsePAM no/' "$SSHD_CONF"
    # Ensure PubkeyAuthentication is on
    sed -i 's/^#\?PubkeyAuthentication.*/PubkeyAuthentication yes/' "$SSHD_CONF"
    systemctl restart sshd
    echo "  SSH hardened: password auth disabled, key-only access"
else
    echo "  SSH already hardened"
fi

echo ""
echo "=== Done! ==="
echo "  nginx:  https://<pi-ip>:8443/ (web app + API proxy)"
echo "  API:    127.0.0.1:8080 (internal only)"
echo "  SSH:    key-based only (password auth disabled)"
echo ""
echo "Router port forwarding:"
echo "  WAN :<your-port> → Pi :8443 (HTTPS)"
echo "  WAN :22           → Pi :22  (SSH, key-only)"
echo ""
echo "IMPORTANT: Make sure your SSH key is installed before running this!"
echo "  ssh-copy-id home@remote-paradox"
echo ""
echo "Restart paradox-bridge if api_host was changed:"
echo "  sudo systemctl restart paradox-bridge"
