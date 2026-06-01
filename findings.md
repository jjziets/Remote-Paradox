# Findings

## 2026-06-01 - Current Recovery Baseline

- Bridge is currently released/deployed as `bridge-v1.0.1`.
- Pi reports bridge `/system/version` as `1.0.1` and `/health` as `ok`.
- `fsck.mode=force fsck.repair=yes` is present in `/boot/firmware/cmdline.txt`.
- `paradox-boot-repair.timer` is installed and active. It repairs interrupted package state, checks bridge health, restarts the bridge, and can force-stage/reinstall the latest `bridge-v*` release if the bridge remains unhealthy.
- `wifi-watchdog.timer` is installed and active. It repairs recoverable NetworkManager/Wi-Fi states.
- The updater now selects `bridge-v*` releases instead of Android app releases.

## 2026-06-01 - Maintenance Safety Constraints

- Automatic full `apt upgrade` is intentionally risky because kernel, firmware, networking, Python, or systemd updates can break boot or connectivity.
- Safer first-class operations are:
  - `apt update` / list available package updates.
  - repair interrupted package state via `dpkg --configure -a` and `apt-get -f install`.
  - security-focused upgrade if the Pi image supports a reliable security-only path.
  - explicit full upgrade with warning and confirmation.
  - explicit reboot.
- Package operations should be one-at-a-time, backgrounded, logged, and observable from the phone app.

## 2026-06-01 - Agent A Backend Design

- Add an admin-only `/system/maintenance/*` namespace for OS package maintenance, separate from existing bridge app update endpoints.
- Recommended endpoints:
  - `GET /system/maintenance/status`
  - `POST /system/maintenance/check-updates`
  - `POST /system/maintenance/repair-packages`
  - `POST /system/maintenance/security-upgrade`
  - `POST /system/maintenance/full-upgrade`
  - `GET /system/maintenance/jobs/{job_id}`
  - `GET /system/maintenance/jobs/{job_id}/log`
- Start package work with `sudo systemd-run --collect --property=Type=oneshot /opt/paradox-bridge/scripts/maintenance_job.sh`.
- Use a global lock to prevent concurrent package jobs and return HTTP `409` while active.
- Store JSON state and logs under `/var/lib/paradox-bridge/maintenance/`; `/opt/paradox-bridge` is root-owned on the live deploy path.
- Full upgrade requires exact confirmation and should be plain `apt-get -y upgrade`, not `dist-upgrade`.
- Security upgrade should use `unattended-upgrade -d` only if available; otherwise report unsupported.

## 2026-06-01 - Agent B Phone Design

- Add OS/package maintenance to the existing admin-only Settings screen.
- Keep bridge app update controls separate from OS package maintenance controls.
- Add `PiMaintenanceState` to `MainViewModel.kt`.
- Add models and Retrofit methods for maintenance status, start/check operations, and job polling.
- Phone UI controls:
  - Check OS Updates
  - Repair Package State
  - Apply Security Updates
  - Full System Upgrade (advanced/risky)
  - Reboot Pi
  - bounded status/log tail
- No BLE maintenance path for the first pass; HTTP only.
- Phone release likely needs `versionName` `1.2.12` and `versionCode` `67`; watch should not rebuild if untouched.

## 2026-06-01 - Agent C Documentation Design

- README needs a deploy-from-scratch path that explains how `/opt/paradox-bridge` gets populated; current Quick Start skips that.
- Current docs need release-channel clarity:
  - Android APKs: `v*` releases.
  - Bridge/Pi source updates: `bridge-v*` releases.
- README should document recovery hardening scripts:
  - `setup-boot-fsck.sh`
  - `setup-wifi-watchdog.sh`
  - `setup-boot-repair.sh`
- Security docs should be updated because durable refresh tokens now exist; JWT-only 72-hour wording is stale.
- Need verify current service install commands before presenting them as from-scratch truth.

## 2026-06-01 - Documentation Baseline

- `README.md` already has Quick Start, Architecture, Android App, Demo Mode, Security, and a short Deployment section.
- `docs/pi-github-operations.md` documents CI vs Pi, journald persistence, Wi-Fi watchdog, and manual deploy notes, but it predates the full boot-repair timer and bridge release separation.
- `README.md` has uncommitted changes in the working tree from before this planning pass; docs edits must preserve that work.

## 2026-06-01 - Live Pi Verification For Implementation

- `paradox-bridge.service` runs from `/opt/paradox-bridge` with `PYTHONPATH=/opt/paradox-bridge/src`, `PARADOX_CONFIG=/etc/paradox-bridge/config.json`, and `ExecStart=/opt/paradox-bridge/venv/bin/python3 /opt/paradox-bridge/start.py`.
- Pi Python in the bridge venv is `Python 3.11.2`.
- `/health` is currently `ok` with `alarm_connected=true`.
- Active timers include `wifi-watchdog.timer`, `paradox-updater.timer`, and `paradox-boot-repair.timer`.
- `/boot/firmware/cmdline.txt` contains `fsck.mode=force fsck.repair=yes`.
- `unattended-upgrade` is not installed on the Pi at this time, so security-only maintenance should return a clear unsupported status until that package/config exists.
- `apt list --upgradable` currently reports 93 upgradable packages.

## 2026-06-01 - Live Maintenance API Verification

- First live API start attempt exposed a permission bug: the bridge service could
  not create mutable job state under `/opt/paradox-bridge/maintenance`.
- Runtime job state was moved to `/var/lib/paradox-bridge/maintenance`, owned by
  the bridge service user.
- The API launcher now uses `sudo -n systemd-run --no-block --collect` so it
  returns after the systemd job is queued and does not hang waiting for sudo or
  job completion.
- Verified `/system/maintenance/check-updates` end-to-end through the API:
  queued job `208c88ddb4b24e0ca393fa0eb79c45c9`, completed with exit code `0`,
  reported `updates_available=93`, `reboot_required=false`, and exposed bounded
  log lines through `/system/maintenance/jobs/{job_id}/log`.
