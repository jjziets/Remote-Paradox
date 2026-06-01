# Remote Paradox Recovery and Maintenance Plan

## Goal

Improve Pi recovery and maintenance so Remote Paradox can recover as much as possible after power or package-state failures, expose safe system maintenance controls in the phone app, and document a clean deploy-from-scratch path in `README.md`.

## Status

Current phase: Phase 4-6 - Parallel implementation.

## Scope

In scope:
- Bridge-side system maintenance API for safe update checks and explicit package operations.
- Background systemd-run or script-based package maintenance jobs with status/log output.
- Phone admin UI for checking package updates, applying security updates, optional full upgrade, and reboot.
- Recovery hardening follow-up around existing `fsck`, Wi-Fi watchdog, updater, and boot repair timers.
- README deployment guide from a blank SD card / fresh Pi through app install and update flow.

Out of scope for this pass:
- Automatic full OS upgrades without admin confirmation.
- Bootloader-only GitHub recovery when Linux/networking cannot start.
- Full A/B OS partitioning or unattended OS re-imaging.
- Android watch changes unless API/model sharing requires a safe compile fix.

## Phase Plan

| Phase | Owner | Status | Output |
|---|---|---:|---|
| 0. Planning and agent dispatch | Main | Complete | Planning files and scoped subagent assignments |
| 1. Bridge maintenance design | Agent A + Main | Complete | Endpoint/script design, job status model, test plan |
| 2. Phone admin maintenance UI design | Agent B + Main | Complete | UI/API integration plan, versioning impact |
| 3. Fresh deploy documentation design | Agent C + Main | Complete | README outline and deploy checklist |
| 4. Bridge implementation | Worker A + Main | Complete | Maintenance scripts, API routes, tests |
| 5. Phone implementation | Worker B + Main | Complete | Admin controls, API models, ViewModel tests |
| 6. Docs implementation | Worker C + Main | In progress | README deploy-from-scratch guide and operations notes |
| 7. Integration verification | Main | In progress | Local tests, GitHub Actions, Pi deploy proof |
| 8. Release | Main | Pending | Version bump, tags/releases, Pi/app update instructions |

## Implementation Slices

Worker A - Bridge maintenance API and scripts:
- Owns:
  - `paradox-bridge/src/paradox_bridge/models.py`
  - `paradox-bridge/src/paradox_bridge/main.py`
  - `paradox-bridge/scripts/maintenance_job.sh`
  - `paradox-bridge/scripts/apply_update.sh`
  - `paradox-bridge/tests/test_maintenance.py`
  - `paradox-bridge/tests/test_maintenance_job.py`
- Add admin-only endpoints:
  - `GET /system/maintenance/status`
  - `POST /system/maintenance/check-updates`
  - `POST /system/maintenance/repair-packages`
  - `POST /system/maintenance/security-upgrade`
  - `POST /system/maintenance/full-upgrade`
  - `GET /system/maintenance/jobs/{job_id}`
  - `GET /system/maintenance/jobs/{job_id}/log`
- Use `sudo systemd-run --collect --property=Type=oneshot` to start root package jobs.
- Store status/logs under `/opt/paradox-bridge/maintenance/`.
- Return `409` when another maintenance job is active.
- Require exact confirmation body for full upgrade.

Worker B - Phone admin maintenance UI:
- Owns:
  - `android-app/app/src/main/java/com/remoteparadox/app/data/ApiModels.kt`
  - `android-app/app/src/main/java/com/remoteparadox/app/data/ParadoxApi.kt`
  - `android-app/app/src/main/java/com/remoteparadox/app/MainViewModel.kt`
  - `android-app/app/src/main/java/com/remoteparadox/app/ui/screens/SettingsScreen.kt`
  - focused app tests under `android-app/app/src/test/java/com/remoteparadox/app/`
- Add `PiMaintenanceState`.
- Add admin-only Settings card separate from bridge app update card.
- Add check, repair, security update, full upgrade, reboot/status/log flows.
- Poll maintenance jobs every 2-3 seconds until terminal state or timeout.
- Strong confirmation for full system upgrade.
- Bump phone app to `1.2.12` / versionCode `67` during release phase if phone files change.

Worker C - Deploy-from-scratch docs:
- Owns:
  - `README.md`
  - `docs/pi-github-operations.md`
  - optional new docs under `docs/`
- Preserve existing uncommitted README changes.
- Explain release channels:
  - Android app/APK releases use `v*`.
  - Bridge/Pi source releases use `bridge-v*`.
- Document fresh SD setup, first SSH, bridge install, nginx, recovery timers, Android APK install, watch pairing, registration, updates, and troubleshooting.
- Do not invent untested service-install commands. Either verify them on the Pi or mark as manual/current-image specific.

## Design Decisions

- Default maintenance action should be safe: check updates and apply security/repair operations only when explicit.
- Full `apt upgrade` must be advanced/manual with clear risk and confirmation.
- Package work must run outside the HTTP request path and expose status/logs.
- Bridge/system releases should use `bridge-v*` tags; Android APK releases should use `v*` tags.
- Recovery claims must distinguish "Linux boots and network works" from "bootloader/SD is unrecoverable."
- Package maintenance jobs should be file-backed because they can outlive bridge restarts.
- Logs returned to the phone app must be bounded and admin-only.
- Security-only upgrades should use `unattended-upgrade` only if available; otherwise report unsupported.

## Open Questions

- Should the phone UI allow full package upgrade on the first implementation? Current plan: yes, but hidden/advanced with exact confirmation and no `dist-upgrade`.
- Which security update mechanism is available on the Pi image: `unattended-upgrade`, `apt list --upgradable`, or distro-specific security suites?
- Should package logs be stored under `/opt/paradox-bridge/maintenance/` or `/var/log/paradox-bridge/`?
- Should reboot require re-entering the admin password, or is JWT admin auth enough?
- Do we add a repo-owned service install script before README finalization, or document the current deployed service unit as a manual step?

## Verification Plan

Bridge:
- `python3 -m pytest paradox-bridge/tests/test_maintenance.py paradox-bridge/tests/test_maintenance_job.py paradox-bridge/tests/test_boot_repair.py paradox-bridge/tests/test_updater.py paradox-bridge/tests/test_setup_boot_fsck.py paradox-bridge/tests/test_wifi_watchdog.py`
- Live Pi smoke checks after deploy:
  - `/system/version`
  - `/health`
  - `/system/maintenance/status`
  - check-updates job status/log tail

Phone:
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest`
- Build signed APK through GitHub Actions on `main`/tag.

Docs:
- Commands in README that touch the Pi must be verified or explicitly marked as current-image/manual.
- README must mention `bridge-v*` vs `v*` release streams.

## Errors Encountered

| Error | Attempt | Resolution |
|---|---|---|
| None yet | Planning | N/A |
