#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
JOB_ID="${2:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
MAINTENANCE_DIR="${PARADOX_MAINTENANCE_DIR:-/var/lib/paradox-bridge/maintenance}"
JOBS_DIR="$MAINTENANCE_DIR/jobs"
LOGS_DIR="$MAINTENANCE_DIR/logs"
LOCK_FILE="$MAINTENANCE_DIR/maintenance.lock"
CURRENT_FILE="$MAINTENANCE_DIR/current.json"
JOB_FILE="$JOBS_DIR/$JOB_ID.json"
LOG_FILE="$LOGS_DIR/$JOB_ID.log"

APT_GET_BIN="${APT_GET_BIN:-apt-get}"
APT_BIN="${APT_BIN:-apt}"
DPKG_BIN="${DPKG_BIN:-dpkg}"
UNATTENDED_UPGRADE_BIN="${UNATTENDED_UPGRADE_BIN:-unattended-upgrade}"
REBOOT_REQUIRED_FILE="${REBOOT_REQUIRED_FILE:-/var/run/reboot-required}"

case "$ACTION" in
    check-updates|repair-packages|security-upgrade|full-upgrade)
        ;;
    *)
        echo "Usage: $0 {check-updates|repair-packages|security-upgrade|full-upgrade} [job-id]" >&2
        exit 2
        ;;
esac

mkdir -p "$JOBS_DIR" "$LOGS_DIR"
touch "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

FINAL_STATUS_SET=0

write_state() {
    local status="$1"
    local message="${2:-}"
    local exit_code="${3:-}"
    local updates_available="${4:-}"
    local security_updates_available="${5:-}"
    local security_upgrade_supported="${6:-}"
    STATUS="$status" MESSAGE="$message" EXIT_CODE="$exit_code" \
    UPDATES_AVAILABLE="$updates_available" \
    SECURITY_UPDATES_AVAILABLE="$security_updates_available" \
    SECURITY_UPGRADE_SUPPORTED="$security_upgrade_supported" \
    REBOOT_REQUIRED="$([[ -f "$REBOOT_REQUIRED_FILE" ]] && echo true || echo false)" \
    ACTION="$ACTION" JOB_ID="$JOB_ID" JOB_FILE="$JOB_FILE" python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

path = Path(os.environ["JOB_FILE"])
now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
try:
    data = json.loads(path.read_text())
except Exception:
    data = {
        "job_id": os.environ["JOB_ID"],
        "action": os.environ["ACTION"],
        "created_at": now,
    }
data["job_id"] = os.environ["JOB_ID"]
data["action"] = os.environ["ACTION"]
data["status"] = os.environ["STATUS"]
data["updated_at"] = now
message = os.environ.get("MESSAGE", "")
if message:
    data["message"] = message
if os.environ["STATUS"] == "running" and not data.get("started_at"):
    data["started_at"] = now
if os.environ["STATUS"] in {"succeeded", "failed", "unsupported", "cancelled"}:
    data["finished_at"] = now
exit_code = os.environ.get("EXIT_CODE", "")
if exit_code:
    data["exit_code"] = int(exit_code)
data["reboot_required"] = os.environ.get("REBOOT_REQUIRED") == "true"
updates_available = os.environ.get("UPDATES_AVAILABLE", "")
if updates_available:
    data["updates_available"] = int(updates_available)
security_updates_available = os.environ.get("SECURITY_UPDATES_AVAILABLE", "")
if security_updates_available:
    data["security_updates_available"] = int(security_updates_available)
security_upgrade_supported = os.environ.get("SECURITY_UPGRADE_SUPPORTED", "")
if security_upgrade_supported:
    data["security_upgrade_supported"] = security_upgrade_supported == "true"
path.parent.mkdir(parents=True, exist_ok=True)
tmp = path.with_name(f".{path.name}.tmp")
tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
tmp.replace(path)
PY
}

count_upgradable() {
    sed '1{/^Listing/d;}' | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' '
}

write_current() {
    JOB_ID="$JOB_ID" CURRENT_FILE="$CURRENT_FILE" python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

path = Path(os.environ["CURRENT_FILE"])
now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps({"job_id": os.environ["JOB_ID"], "updated_at": now}) + "\n")
PY
}

clear_current() {
    JOB_ID="$JOB_ID" CURRENT_FILE="$CURRENT_FILE" python3 <<'PY'
import json
import os
from pathlib import Path

path = Path(os.environ["CURRENT_FILE"])
try:
    current = json.loads(path.read_text())
except Exception:
    current = {}
if current.get("job_id") == os.environ["JOB_ID"]:
    path.unlink(missing_ok=True)
PY
}

finish() {
    local rc=$?
    if [[ "$FINAL_STATUS_SET" != "1" ]]; then
        if [[ "$rc" == "0" ]]; then
            write_state "succeeded" "Completed" "0" || true
        else
            write_state "failed" "Failed" "$rc" || true
        fi
    fi
    clear_current || true
    exit "$rc"
}
trap finish EXIT

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "[maintenance] Another maintenance job is active"
    write_state "failed" "Another maintenance job is active" "75"
    FINAL_STATUS_SET=1
    exit 75
fi

write_current
write_state "running" "Running $ACTION"
echo "[maintenance] Starting $ACTION"

case "$ACTION" in
    check-updates)
        "$APT_GET_BIN" update
        upgradable_output="$("$APT_BIN" list --upgradable 2>/dev/null || true)"
        printf '%s\n' "$upgradable_output"
        updates_available="$(printf '%s\n' "$upgradable_output" | count_upgradable)"
        write_state "running" "Found $updates_available available package updates" "" "$updates_available"
        ;;
    repair-packages)
        "$DPKG_BIN" --configure -a
        DEBIAN_FRONTEND=noninteractive "$APT_GET_BIN" -y -f install
        ;;
    security-upgrade)
        if ! command -v "$UNATTENDED_UPGRADE_BIN" >/dev/null 2>&1; then
            echo "[maintenance] unattended-upgrade is not installed; security upgrade unsupported"
            write_state "unsupported" "unattended-upgrade is not installed" "0" "" "" "false"
            FINAL_STATUS_SET=1
            exit 0
        fi
        write_state "running" "Running security upgrade" "" "" "" "true"
        "$APT_GET_BIN" update
        DEBIAN_FRONTEND=noninteractive "$UNATTENDED_UPGRADE_BIN" -d
        ;;
    full-upgrade)
        "$APT_GET_BIN" update
        DEBIAN_FRONTEND=noninteractive "$APT_GET_BIN" -y --with-new-pkgs upgrade
        ;;
esac

echo "[maintenance] Completed $ACTION"
