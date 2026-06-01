#!/usr/bin/env bash
set -euo pipefail

# Force a filesystem check at every boot and allow automatic repairs.
# Raspberry Pi OS keeps cmdline.txt as one single line; preserve that format.

CMDLINE_FILE="${CMDLINE_FILE:-}"

if [[ -z "$CMDLINE_FILE" ]]; then
    if [[ $EUID -ne 0 ]]; then
        echo "setup-boot-fsck: run as root or set CMDLINE_FILE for testing" >&2
        exit 1
    fi

    for candidate in /boot/firmware/cmdline.txt /boot/cmdline.txt; do
        if [[ -f "$candidate" ]]; then
            CMDLINE_FILE="$candidate"
            break
        fi
    done
fi

if [[ -z "$CMDLINE_FILE" || ! -f "$CMDLINE_FILE" ]]; then
    echo "setup-boot-fsck: cmdline.txt not found" >&2
    exit 1
fi

cmdline="$(tr '\n' ' ' < "$CMDLINE_FILE" | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"
changed=0

for flag in fsck.mode=force fsck.repair=yes; do
    if [[ " $cmdline " != *" $flag "* ]]; then
        cmdline="$cmdline $flag"
        changed=1
    fi
done

if [[ "$changed" -eq 1 ]]; then
    backup="${CMDLINE_FILE}.bak.$(date +%Y%m%d%H%M%S)"
    cp "$CMDLINE_FILE" "$backup"
    printf '%s\n' "$cmdline" > "$CMDLINE_FILE"
    echo "setup-boot-fsck: updated $CMDLINE_FILE (backup: $backup)"
else
    echo "setup-boot-fsck: already enabled in $CMDLINE_FILE"
fi
