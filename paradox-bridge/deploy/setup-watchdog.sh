#!/usr/bin/env bash
# setup-watchdog.sh — Arm the systemd hardware watchdog so a soft hang auto-reboots.
# The Pi (bcm2835) has a hardware watchdog at /dev/watchdog with a ~15s max timeout.
# Run on the Raspberry Pi as root (same pattern as setup-wifi-watchdog.sh).
set -euo pipefail

# Keep just under the bcm2835 hardware maximum (~15s).
RUNTIME_WATCHDOG_SEC="${RUNTIME_WATCHDOG_SEC:-14}"
REBOOT_WATCHDOG_SEC="${REBOOT_WATCHDOG_SEC:-10min}"
CONF_DIR="${SYSTEMD_CONF_DIR:-/etc/systemd/system.conf.d}"
CONF="${CONF_DIR}/watchdog.conf"

if [[ "${SYSTEMD_CONF_DIR:-}" == "" && $EUID -ne 0 ]]; then
    echo "setup-watchdog: run as root (or set SYSTEMD_CONF_DIR for testing)" >&2
    exit 1
fi

echo "=== Remote Paradox — systemd hardware watchdog ==="

new_conf="$(cat <<EOF
[Manager]
# bcm2835 hardware watchdog max timeout is ~15s; stay just under it.
RuntimeWatchdogSec=${RUNTIME_WATCHDOG_SEC}
RebootWatchdogSec=${REBOOT_WATCHDOG_SEC}
EOF
)"

mkdir -p "$CONF_DIR"
if [[ -f "$CONF" ]] && [[ "$(cat "$CONF")" == "$new_conf" ]]; then
    echo "setup-watchdog: already configured ($CONF)"
    exit 0
fi

printf '%s\n' "$new_conf" > "$CONF"
echo "setup-watchdog: wrote $CONF (RuntimeWatchdogSec=${RUNTIME_WATCHDOG_SEC})"

# RuntimeWatchdogSec only takes effect after PID 1 re-executes; daemon-reload is
# not enough. Skip the re-exec when testing against a non-default conf dir.
if [[ "${SYSTEMD_CONF_DIR:-}" == "" ]] && [[ -d /run/systemd/system ]]; then
    systemctl daemon-reexec
    echo "setup-watchdog: armed -> RuntimeWatchdogUSec=$(systemctl show -p RuntimeWatchdogUSec --value)"
fi
