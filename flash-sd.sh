#!/usr/bin/env bash
set -euo pipefail

# ── Configuration ────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ ! -f "$SCRIPT_DIR/.env" ]]; then
    echo "ERROR: .env not found in $SCRIPT_DIR — copy .env.example and fill in values"
    exit 1
fi
# shellcheck source=.env
source "$SCRIPT_DIR/.env"

DISK="/dev/disk4"
RDISK="/dev/rdisk4"                       # raw device = much faster writes
IMAGE_XZ="2025-12-04-raspios-trixie-arm64.img.xz"

HOSTNAME_PI="${PI_HOSTNAME:?Set PI_HOSTNAME in .env}"
USERNAME="${PI_USERNAME:?Set PI_USERNAME in .env}"
PASSWORD_HASH=$(openssl passwd -6 "${PI_PASSWORD:?Set PI_PASSWORD in .env}")

WIFI_SSID="${WIFI_SSID:?Set WIFI_SSID in .env}"
WIFI_PSK="${WIFI_PSK:?Set WIFI_PSK in .env}"
WIFI_COUNTRY="${WIFI_COUNTRY:-ZA}"
NM_UUID="54d387ec-8fb6-4037-b83a-a8d31148a1ee"

# ── Preflight checks ────────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    echo "This script must be run as root (sudo)."
    exit 1
fi

if [[ ! -f "$SCRIPT_DIR/$IMAGE_XZ" ]]; then
    echo "ERROR: $IMAGE_XZ not found in $SCRIPT_DIR"
    exit 1
fi

echo ""
echo "┌──────────────────────────────────────────────────────┐"
echo "│  Remote Paradox — Headless Pi SD Card Flasher        │"
echo "└──────────────────────────────────────────────────────┘"
echo ""
echo "  Image    : $IMAGE_XZ"
echo "  Target   : $DISK  ($(diskutil info "$DISK" 2>/dev/null | awk '/Disk Size/ {print $3, $4}'))"
echo "  Hostname : $HOSTNAME_PI"
echo "  User     : $USERNAME"
echo "  WiFi     : $WIFI_SSID"
echo ""

diskutil list "$DISK"
echo ""
echo "⚠  ALL DATA ON $DISK WILL BE DESTROYED."
read -rp "Type YES to continue: " CONFIRM
if [[ "$CONFIRM" != "YES" ]]; then
    echo "Aborted."
    exit 0
fi

# ── Step 1: Unmount all partitions ───────────────────────────────────
echo ""
echo "[1/5] Unmounting $DISK …"
diskutil unmountDisk "$DISK"

# ── Step 2: Decompress and flash ────────────────────────────────────
echo "[2/5] Decompressing & flashing to $RDISK (this takes a while) …"
xz -dc "$SCRIPT_DIR/$IMAGE_XZ" | dd of="$RDISK" bs=4m status=progress
sync
echo "       Flash complete."

# ── Step 3: Mount boot partition ─────────────────────────────────────
echo "[3/5] Mounting boot partition …"
sleep 3
diskutil mountDisk "$DISK"
sleep 2

BOOT_VOL=""
for candidate in /Volumes/bootfs /Volumes/boot; do
    if [[ -d "$candidate" ]]; then
        BOOT_VOL="$candidate"
        break
    fi
done

if [[ -z "$BOOT_VOL" ]]; then
    echo "ERROR: Could not find boot partition mount. Listing /Volumes:"
    ls /Volumes/
    echo "Set BOOT_VOL manually and re-run the config steps."
    exit 1
fi
echo "       Boot partition mounted at $BOOT_VOL"

# ── Step 4: Write headless configuration ─────────────────────────────
echo "[4/5] Writing headless configuration …"

# 4a — Enable SSH
touch "$BOOT_VOL/ssh"
echo "       ✓ SSH enabled"

# 4b — User credentials (username:encrypted_password)
echo "${USERNAME}:${PASSWORD_HASH}" > "$BOOT_VOL/userconf.txt"
echo "       ✓ User '$USERNAME' configured"

# 4c — firstrun.sh (sets hostname, WiFi via NetworkManager, SSH)
cat > "$BOOT_VOL/firstrun.sh" << 'FIRSTRUN_OUTER'
#!/bin/bash
set +e

# ── Hostname ──
CURRENT_HOSTNAME=$(cat /etc/hostname | tr -d " \t\n\r")
NEW_HOSTNAME="__HOSTNAME__"
if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then
    /usr/lib/raspberrypi-sys-mods/imager_custom set_hostname "$NEW_HOSTNAME"
else
    echo "$NEW_HOSTNAME" > /etc/hostname
    sed -i "s/127.0.1.1.*${CURRENT_HOSTNAME}/127.0.1.1\t${NEW_HOSTNAME}/g" /etc/hosts
fi

# ── Enable SSH ──
if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then
    /usr/lib/raspberrypi-sys-mods/imager_custom enable_ssh
else
    systemctl enable ssh
    systemctl start ssh
fi

# ── Create user if needed ──
if ! id -u "__USERNAME__" >/dev/null 2>&1; then
    if [ -f /usr/lib/userconf-pi/userconf ]; then
        /usr/lib/userconf-pi/userconf '__USERNAME__' '__PASSWORD_HASH__'
    else
        useradd -m -G sudo,adm,dialout,cdrom,audio,video,plugdev,games,users,input,render,netdev,gpio,i2c,spi -s /bin/bash '__USERNAME__'
        echo '__USERNAME__:__PASSWORD_HASH__' | chpasswd -e
    fi
fi

# ── WiFi via NetworkManager ──
CONN_DIR="/etc/NetworkManager/system-connections"
mkdir -p "$CONN_DIR"
cat > "${CONN_DIR}/preconfigured.nmconnection" << 'NMEOF'
[connection]
id=preconfigured
uuid=__NM_UUID__
type=wifi
autoconnect=true

[wifi]
mode=infrastructure
ssid=__WIFI_SSID__

[wifi-security]
auth-alg=open
key-mgmt=wpa-psk
psk=__WIFI_PSK__

[ipv4]
method=auto

[ipv6]
method=auto
NMEOF
chmod 600 "${CONN_DIR}/preconfigured.nmconnection"

# ── Set WiFi country ──
if command -v raspi-config >/dev/null 2>&1; then
    raspi-config nonint do_wifi_country "__WIFI_COUNTRY__"
fi

# ── Cleanup: remove firstrun from cmdline and delete this script ──
BOOT_MNT=""
if [ -d /boot/firmware ]; then
    BOOT_MNT="/boot/firmware"
elif [ -d /boot ]; then
    BOOT_MNT="/boot"
fi
if [ -n "$BOOT_MNT" ]; then
    sed -i 's| systemd.run=[^ ]*||g' "${BOOT_MNT}/cmdline.txt"
    sed -i 's| systemd.run_success_action=[^ ]*||g' "${BOOT_MNT}/cmdline.txt"
    sed -i 's| systemd.unit=[^ ]*||g' "${BOOT_MNT}/cmdline.txt"
    rm -f "${BOOT_MNT}/firstrun.sh"
fi

exit 0
FIRSTRUN_OUTER

# Replace placeholders with actual values
sed -i '' "s|__HOSTNAME__|${HOSTNAME_PI}|g" "$BOOT_VOL/firstrun.sh"
sed -i '' "s|__USERNAME__|${USERNAME}|g" "$BOOT_VOL/firstrun.sh"
sed -i '' "s|__PASSWORD_HASH__|${PASSWORD_HASH}|g" "$BOOT_VOL/firstrun.sh"
sed -i '' "s|__NM_UUID__|${NM_UUID}|g" "$BOOT_VOL/firstrun.sh"
sed -i '' "s|__WIFI_SSID__|${WIFI_SSID}|g" "$BOOT_VOL/firstrun.sh"
sed -i '' "s|__WIFI_PSK__|${WIFI_PSK}|g" "$BOOT_VOL/firstrun.sh"
sed -i '' "s|__WIFI_COUNTRY__|${WIFI_COUNTRY}|g" "$BOOT_VOL/firstrun.sh"
chmod +x "$BOOT_VOL/firstrun.sh"
echo "       ✓ firstrun.sh written"

# 4d — Patch cmdline.txt to run firstrun.sh on first boot
CMDLINE="$BOOT_VOL/cmdline.txt"
if [[ -f "$CMDLINE" ]]; then
    # Append systemd.run directive (single line, no newline at end)
    EXISTING=$(tr -d '\n' < "$CMDLINE")
    echo -n "${EXISTING} systemd.run=/boot/firmware/firstrun.sh systemd.run_success_action=reboot systemd.unit=kernel-command-line.target" > "$CMDLINE"
    echo "       ✓ cmdline.txt patched for first-boot run"
else
    echo "       ⚠ cmdline.txt not found — you may need to patch it manually"
fi

# ── Step 5: Eject ────────────────────────────────────────────────────
echo "[5/5] Ejecting $DISK …"
diskutil eject "$DISK"
echo ""
echo "┌──────────────────────────────────────────────────────┐"
echo "│  ✓  SD card is ready!                                │"
echo "│                                                      │"
echo "│  1. Insert into your Raspberry Pi and power on       │"
echo "│  2. Wait ~90 seconds for first boot                  │"
echo "│  3. Find it on your network as: $HOSTNAME_PI    │"
echo "│  4. SSH in:  ssh home@remote-paradox.local           │"
echo "│     or:      ssh home@<ip-from-dhcp-list>            │"
echo "│  5. Password: <as set in .env>                       │"
echo "└──────────────────────────────────────────────────────┘"
echo ""
