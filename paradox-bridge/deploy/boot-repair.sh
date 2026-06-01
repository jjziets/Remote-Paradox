#!/usr/bin/env bash
set -euo pipefail

# Conservative boot/app repair for the Pi. This does not run full OS upgrades.
# It repairs interrupted package transactions, verifies the bridge, and can
# reinstall the latest bridge-v* GitHub release if the local bridge stays down.

INSTALL_DIR="${INSTALL_DIR:-/opt/paradox-bridge}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/health}"
STATUS_FILE="${STATUS_FILE:-$INSTALL_DIR/update_status.json}"
LOCK_FILE="${LOCK_FILE:-/run/paradox-boot-repair.lock}"
SKIP_APT_REPAIR="${BOOT_REPAIR_SKIP_APT:-0}"
SLEEP_AFTER_RESTART="${BOOT_REPAIR_SLEEP_AFTER_RESTART:-10}"
PYTHON_BIN="${PYTHON_BIN:-$INSTALL_DIR/venv/bin/python3}"

PATH="$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

log() {
    printf 'paradox-boot-repair: %s\n' "$*"
    if command -v logger >/dev/null 2>&1; then
        logger -t paradox-boot-repair "$*"
    fi
}

run_best_effort() {
    log "+ $*"
    "$@" || log "warning: command failed: $*"
}

bridge_healthy() {
    curl -fsS --max-time 8 "$HEALTH_URL" >/dev/null 2>&1
}

restart_bridge() {
    run_best_effort systemctl reset-failed paradox-bridge
    run_best_effort systemctl restart paradox-bridge
    sleep "$SLEEP_AFTER_RESTART"
}

repair_package_state() {
    if [[ "$SKIP_APT_REPAIR" == "1" ]]; then
        log "package repair skipped by BOOT_REPAIR_SKIP_APT=1"
        return
    fi
    if ! command -v dpkg >/dev/null 2>&1 || ! command -v apt-get >/dev/null 2>&1; then
        log "dpkg/apt-get not available, skipping package repair"
        return
    fi

    export DEBIAN_FRONTEND=noninteractive
    run_best_effort dpkg --configure -a
    run_best_effort apt-get -y -f install
}

stage_latest_bridge() {
    if [[ "$PYTHON_BIN" == */* ]]; then
        [[ -x "$PYTHON_BIN" ]] || {
            log "python unavailable, cannot stage bridge release"
            return 1
        }
    elif ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
        log "python unavailable, cannot stage bridge release"
        return 1
    fi
    if [[ ! -f "$INSTALL_DIR/scripts/updater.py" ]]; then
        log "updater unavailable, cannot stage bridge release"
        return 1
    fi
    run_best_effort "$PYTHON_BIN" "$INSTALL_DIR/scripts/updater.py" --force
}

status_is_pending() {
    [[ -f "$STATUS_FILE" ]] || return 1
    python3 - "$STATUS_FILE" <<'PY'
import json
import sys

try:
    with open(sys.argv[1]) as f:
        data = json.load(f)
except Exception:
    sys.exit(1)
sys.exit(0 if data.get("pending") else 1)
PY
}

apply_staged_bridge() {
    if ! status_is_pending; then
        log "no staged bridge update to apply"
        return
    fi
    if [[ ! -x "$INSTALL_DIR/scripts/apply_update.sh" ]]; then
        log "apply_update.sh unavailable"
        return 1
    fi
    run_best_effort "$INSTALL_DIR/scripts/apply_update.sh"
}

main() {
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$LOCK_FILE"
        if ! flock -n 9; then
            log "another repair run is active"
            exit 0
        fi
    else
        lock_dir="${LOCK_FILE}.d"
        if ! mkdir "$lock_dir" 2>/dev/null; then
            log "another repair run is active"
            exit 0
        fi
        trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT
    fi

    log "starting repair pass"

    if [[ -x "$INSTALL_DIR/deploy/setup-boot-fsck.sh" ]]; then
        run_best_effort "$INSTALL_DIR/deploy/setup-boot-fsck.sh"
    fi

    repair_package_state

    if bridge_healthy; then
        log "bridge is healthy"
        exit 0
    fi

    log "bridge health check failed, restarting service"
    restart_bridge
    if bridge_healthy; then
        log "bridge recovered after restart"
        exit 0
    fi

    log "bridge still unhealthy, staging latest bridge release"
    stage_latest_bridge
    apply_staged_bridge
    restart_bridge

    if bridge_healthy; then
        log "bridge recovered after reinstall"
        exit 0
    fi

    log "repair pass finished but bridge is still unhealthy"
    exit 1
}

main "$@"
