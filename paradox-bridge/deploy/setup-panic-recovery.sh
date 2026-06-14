#!/usr/bin/env bash
# setup-panic-recovery.sh — Auto-reboot on kernel panic and capture the crash.
#
# A Pi Zero 2 W that panics (e.g. failing to mount a corrupt rootfs after an
# unclean power cut) otherwise hangs forever with a steady-blinking ACT LED.
# This:
#   1. sets kernel.panic=10 / panic_on_oops=1 (covers panics after boot),
#   2. adds panic=10 to the kernel cmdline (covers boot-time panics, which run
#      before sysctl is applied) so it auto-reboots and retries fsck,
#   3. enables ramoops/pstore so the next panic log survives the reboot.
# Run on the Raspberry Pi as root. A reboot is required for cmdline/ramoops.
set -euo pipefail

SYSCTL="${SYSCTL_FILE:-/etc/sysctl.d/99-panic-reboot.conf}"
CMDLINE_FILE="${CMDLINE_FILE:-}"
CONFIG_FILE="${CONFIG_FILE:-}"

if [[ -z "$CMDLINE_FILE" || -z "$CONFIG_FILE" ]]; then
    if [[ $EUID -ne 0 ]]; then
        echo "setup-panic-recovery: run as root (or set CMDLINE_FILE/CONFIG_FILE/SYSCTL_FILE for testing)" >&2
        exit 1
    fi
fi

# Resolve boot config locations (firmware path on modern Pi OS, legacy fallback).
if [[ -z "$CMDLINE_FILE" ]]; then
    for c in /boot/firmware/cmdline.txt /boot/cmdline.txt; do
        [[ -f "$c" ]] && { CMDLINE_FILE="$c"; break; }
    done
fi
if [[ -z "$CONFIG_FILE" ]]; then
    for c in /boot/firmware/config.txt /boot/config.txt; do
        [[ -f "$c" ]] && { CONFIG_FILE="$c"; break; }
    done
fi
[[ -n "$CMDLINE_FILE" && -f "$CMDLINE_FILE" ]] || { echo "setup-panic-recovery: cmdline.txt not found" >&2; exit 1; }

echo "=== Remote Paradox — panic auto-reboot + crash capture ==="

# 1) Runtime sysctls (apply now + persist).
mkdir -p "$(dirname "$SYSCTL")"
cat >"$SYSCTL" <<'EOF'
# Auto-reboot 10s after a kernel panic/oops instead of hanging forever.
kernel.panic=10
kernel.panic_on_oops=1
EOF
sysctl -w kernel.panic=10 kernel.panic_on_oops=1 >/dev/null 2>&1 || true

# 2) panic=10 on the kernel cmdline (single line; Pi OS requires that).
cmdline="$(tr '\n' ' ' < "$CMDLINE_FILE" | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
if [[ " $cmdline " != *" panic="* ]]; then
    backup="${CMDLINE_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    cp "$CMDLINE_FILE" "$backup"
    printf '%s\n' "$cmdline panic=10" > "$CMDLINE_FILE"
    echo "setup-panic-recovery: added panic=10 to $CMDLINE_FILE (backup: $backup) — reboot required"
else
    echo "setup-panic-recovery: cmdline already has a panic= setting"
fi

# 3) ramoops (persistent crash log) when the firmware ships the overlay.
if [[ -n "$CONFIG_FILE" && -f "$CONFIG_FILE" ]]; then
    overlay_dir="$(dirname "$CONFIG_FILE")/overlays"
    if ls "$overlay_dir"/ramoops*.dtbo >/dev/null 2>&1; then
        if ! grep -q '^dtoverlay=ramoops' "$CONFIG_FILE"; then
            printf '\n# Persist kernel panic/oops log across reboot (pstore)\ndtoverlay=ramoops\n' >> "$CONFIG_FILE"
            echo "setup-panic-recovery: added dtoverlay=ramoops to $CONFIG_FILE — reboot required"
        else
            echo "setup-panic-recovery: ramoops already enabled in $CONFIG_FILE"
        fi
    else
        echo "setup-panic-recovery: no ramoops overlay on this image — skipping persistent capture"
    fi
fi
