# Pi operations tracked in GitHub (no secrets)

This document records **Pi-related behaviour and repo-owned tooling** so the team can reproduce setup from the repository. It does **not** include private keys, passphrases, LAN/WAN addresses, WiFi credentials, or router admin details. Keep those in an **organisation secret store** or a local `.env` that stays out of git (see `.env.example`).

---

## GitHub Actions vs the Pi

| Trigger | What runs | What it does **not** do |
|--------|-------------|-------------------------|
| Push to **`main`** | `.github/workflows/build-android.yml` | Does **not** deploy to the Pi |
| Push **`v*`** tags | Same workflow + GitHub Release with Android APK assets | Same — **no** Pi deploy |
| Push **`bridge-v*`** tags | `.github/workflows/release-bridge.yml`: tests, signs and publishes the bridge | Does **not** build APKs or upgrade the OS |

The Android workflow tests and builds **signed Android release APKs** on GitHub-hosted runners and publishes both versioned APKs on `v*` tags. A watch-only version is not increased for a phone-only change, but both assets remain discoverable by the apps.

Bridge/Pi releases use the separate `bridge-v*` channel. After the one-time
[signed updater bootstrap](signed-pi-deployment.md), the Pi polls public GitHub
every five minutes, verifies the pinned Ed25519 signature and file hashes, and
installs the bridge source and recovery helpers. CI uses GitHub-hosted runners;
the Pi needs neither a runner nor a GitHub token. Existing nginx/TLS identity,
web assets, configuration and users are preserved. OS upgrades remain explicit.

**A published release is not proof of deployment.** Confirm the root-owned
`/var/lib/paradox-updater/deployment.json` receipt, running version, service
health and fresh panel polling using the linked deployment runbook.

---

## Pi-side tooling in this repository

### Persistent systemd journal (debugging outages)

For post-mortems after power loss or hangs, **volatile** journal storage loses prior boots. On a production Pi, use a **journald drop-in** (under `/etc/systemd/journald.conf.d/`) with at least:

- `Storage=persistent`
- Bounded size / retention (e.g. `SystemMaxUse=`, `MaxRetentionSec=`)
- `Compress=yes` where appropriate

Then `systemctl restart systemd-journald` and `journalctl --flush`. Inspect older boots with `journalctl --list-boots` and `journalctl -b -1`.

Exact byte limits are an operational choice — keep them out of the repo if they embed site-specific policy; the important part is **persistent + capped** storage.

### WiFi watchdog (NetworkManager)

If WiFi drops but the kernel is still running, a **timer-driven** script can turn WiFi back on and re-activate the saved NetworkManager profile. This repo assumes the **first-boot** connection id from `flash-sd.sh` (`preconfigured`) and interface **`wlan0`** (override if your image differs).

| Artifact | Role |
|----------|------|
| `paradox-bridge/deploy/wifi-watchdog.sh` | Health check + `nmcli` recovery |
| `paradox-bridge/deploy/setup-wifi-watchdog.sh` | Installs script under `/opt/paradox-bridge/scripts/` and enables **`wifi-watchdog.timer`** |
| `paradox-bridge/tests/test_wifi_watchdog.py` | Unit coverage with faked `nmcli` / `ip` / `ping` |

Install on the Pi after the tree exists under `/opt/paradox-bridge`:

```bash
sudo bash /opt/paradox-bridge/deploy/setup-wifi-watchdog.sh
```

Useful checks:

```bash
systemctl list-timers wifi-watchdog.timer
journalctl -t wifi-watchdog
```

Optional environment variables (set via **systemd drop-in**, not committed secrets): `WIFI_WATCHDOG_IFACE`, `WIFI_WATCHDOG_NM_CONN`.

**Limitations:** Does not fix total power loss, SD faults, or a wedged kernel—only recoverable WiFi / DHCP / NetworkManager states.

### One-second Pi state recorder

`paradox-bridge/deploy/setup-state-recorder.sh` installs
`paradox-state-recorder.service`, a small "black box" logger for the recurring
Pi offline/hang investigation. It writes one compact state line per second to
minute-sized files under:

```bash
/var/log/paradox-bridge/state-recorder/state-YYYYMMDD-HHMM.log
```

Retention is intentionally capped: the recorder deletes `state-*.log` files
older than 24 hours. This keeps the last day of evidence without growing an
unbounded log on the SD card.

Each sample records:

- wall-clock time and epoch,
- uptime and load,
- available memory and root filesystem space,
- CPU temperature and Raspberry Pi throttle flags where `vcgencmd` exists,
- `wlan0` NetworkManager state, IPv4 address, and gateway,
- systemd states for `paradox-bridge`, `paradox-ble`, `nginx`,
  `NetworkManager`, `ssh`, and `avahi-daemon`,
- local bridge health through `http://127.0.0.1:8080/health`.

Install on the Pi:

```bash
sudo bash /opt/paradox-bridge/deploy/setup-state-recorder.sh
```

Useful checks during or after the next outage:

```bash
systemctl status paradox-state-recorder --no-pager
sudo ls -lh /var/log/paradox-bridge/state-recorder/
latest=$(sudo ls -1 /var/log/paradox-bridge/state-recorder/state-*.log | tail -1)
sudo tail -120 "$latest"
sudo grep -R "health=fail\\|bridge=inactive\\|network=inactive\\|nm_state=.*disconnected\\|throttled=0x[^0]" /var/log/paradox-bridge/state-recorder/ | tail -100
journalctl --list-boots
journalctl -b -1 -n 200 --no-pager
```

How to read the evidence:

- If there is a time gap in `epoch=` values and the next line has low `uptime=`,
  the Pi rebooted or lost power.
- If `uptime=` keeps increasing but `health=fail`, inspect bridge/nginx service
  state and `journalctl -u paradox-bridge -u nginx`.
- If `network=active` but `nm_state` changes away from `100_(connected)`, inspect
  NetworkManager and the WiFi watchdog logs.
- If `throttled` changes from `0x0`, suspect power/undervoltage or thermal
  throttling. The exact bit meaning is Raspberry Pi firmware-specific, so check
  `vcgencmd get_throttled` documentation for the running image.
- If the logs stop while the Pi LEDs are still blinking, suspect a kernel hang,
  storage stall, or power instability rather than application-level failure.

This recorder is diagnostic only. It does not repair failures; it gives us the
last known second-by-second state before the next incident.

### Arm/disarm command diagnostics

Added during the 2026-09-09 investigation. The state recorder's `health=ok`
only means `/health` returned HTTP success; it does not inspect the JSON
`alarm_connected` field or prove that the panel acknowledged an alarm command.
Use the additional command log to diagnose slow or unsuccessful operations.

Install after deploying `src/paradox_bridge/diagnostics.py` and the matching
`alarm.py` / `main.py` instrumentation:

```bash
sudo bash /opt/paradox-bridge/deploy/setup-command-diagnostics.sh
sudo systemctl restart paradox-bridge
sudo tail -40 /var/log/paradox-bridge/command-diagnostics/commands.jsonl
```

The installer enables `PARADOX_DIAGNOSTICS_DIR` in a bridge systemd drop-in.
The log directory is readable only by root and the bridge service user/group.
JSON lines record UTC timestamps and the following evidence:

- `request_started` / `request_finished`: one generated request ID, command
  route, HTTP response status, returned `success` boolean, and elapsed time.
  A 200 response with `accepted=false` is an unsuccessful command.
- `command_started` / `command_waiting` / `command_result` / `command_error`:
  partition ID, command, generated command ID, elapsed time, and cached panel
  state. Waiting is recorded after 2 seconds and every 10 seconds thereafter.
- `pai_signal`: allowlisted panel timeout, connection-loss and parser signals.
  This retains signals that PAI may catch internally and convert to `False`.
- `panel_state`: cached flags for each partition, open non-bypassed zone IDs,
  connection flag, age of the last status poll, poll-task state, and request-lock
  state. Cached state is checked every 5 seconds; changes and a 30-second
  heartbeat are logged. Poll age above 15 seconds is marked stale for diagnosis,
  not treated as proof of a broken connection or used to trigger recovery.

No PINs, passwords, bearer tokens, request bodies, response detail strings,
zone labels, or raw serial packets are recorded. Diagnostics do not send new
alarm commands, add retries, or change the alarm command result. Panel acceptance
and the actual later partition state are separate evidence; a result marked
accepted is not proof that the requested state was reached.

Files rotate hourly in UTC, retaining the current file and 23 archives
(approximately the last 24 hours). Rotation happens when a record is written.
This is separate from the one-second state recorder and the system journal.
Writes are buffered by the OS, so sudden power loss can lose the last entries;
stopped logs alone cannot prove sleep, a kernel crash, or a recorder failure.

For the next incident, record its local time, device, partition and action;
collect evidence promptly before it rotates:

```bash
sudo grep -hE 'request_|command_|pai_signal|panel_(connect|poll|status)' \
  /var/log/paradox-bridge/command-diagnostics/commands.jsonl* | tail -120
sudo journalctl -u paradox-bridge --since '20 minutes ago' --no-pager \
  | sed -E 's/([?&]token=)[^ &"]+/\1[REDACTED]/g'
```

Existing bridge/nginx access logs can contain WebSocket query tokens. Redact
them before sharing and keep incident archives outside Git. Full findings and
verification limits are in [the September investigation](pi-arm-disarm-2026-09-09.md).

### Boot-time filesystem repair

`paradox-bridge/deploy/setup-boot-fsck.sh` adds the following flags to the Pi boot command line:

- `fsck.mode=force`
- `fsck.repair=yes`

Install on the Pi after `/opt/paradox-bridge` contains the bridge deploy scripts:

```bash
sudo bash /opt/paradox-bridge/deploy/setup-boot-fsck.sh
```

Useful checks:

```bash
tr ' ' '\n' </boot/firmware/cmdline.txt | grep '^fsck\.'
```

If the image uses `/boot/cmdline.txt` instead of `/boot/firmware/cmdline.txt`, the setup script detects that path.

### Conservative boot repair timer

`paradox-bridge/deploy/setup-boot-repair.sh` installs:

| Artifact | Role |
|----------|------|
| `paradox-bridge/deploy/boot-repair.sh` | Repairs interrupted package state, checks bridge health, restarts the bridge, and can force-stage/reinstall the latest `bridge-v*` release if the bridge remains unhealthy |
| `paradox-bridge/deploy/setup-boot-repair.sh` | Installs the script under `/opt/paradox-bridge/scripts/` and enables **`paradox-boot-repair.timer`** |
| `paradox-bridge/tests/test_boot_repair.py` | Unit coverage for healthy, restart, stage, and apply paths |

Install on the Pi:

```bash
sudo bash /opt/paradox-bridge/deploy/setup-boot-repair.sh
```

Useful checks:

```bash
systemctl list-timers paradox-boot-repair.timer
journalctl -t paradox-boot-repair -n 100
curl -fsS http://127.0.0.1:8080/health
```

The timer is intentionally conservative. It does not run full OS upgrades and cannot repair a Pi that cannot boot Linux or reach the network.

### Crash hardening (watchdog, zram, panic recovery)

The Pi Zero 2 W has a single green ACT LED (no power/undervoltage LED) and only ~512 MB RAM. These scripts reduce both the rate of crashes and the time spent unrecovered after one. They are installed automatically by `apply_update.sh` and can be run by hand on the live Pi.

| Artifact | Role |
|----------|------|
| `paradox-bridge/deploy/setup-watchdog.sh` | Arms the systemd hardware watchdog (`RuntimeWatchdogSec=14`, just under the bcm2835 ~15s max) so a soft hang auto-resets instead of sitting frozen |
| `paradox-bridge/deploy/setup-zram-swap.sh` | Replaces the on-SD `dphys-swapfile` with compressed RAM swap (`zram-swap.service`, zstd) so memory pressure never writes to / wears the SD card |
| `paradox-bridge/deploy/setup-panic-recovery.sh` | Sets `kernel.panic=10`/`panic_on_oops=1`, adds `panic=10` to the kernel cmdline (boot-time panics auto-reboot and retry the forced fsck), and enables `dtoverlay=ramoops` so the next panic log survives the reboot in `/sys/fs/pstore` |

Install on the Pi:

```bash
sudo bash /opt/paradox-bridge/deploy/setup-watchdog.sh
sudo bash /opt/paradox-bridge/deploy/setup-zram-swap.sh
sudo bash /opt/paradox-bridge/deploy/setup-panic-recovery.sh   # cmdline/ramoops changes need a reboot
```

Useful checks:

```bash
systemctl show -p RuntimeWatchdogUSec --value     # watchdog armed
swapon --show                                     # should list only /dev/zram0
grep -o 'panic=[0-9]*' /proc/cmdline              # panic=10 active
cat /sys/module/pstore/parameters/backend         # ramoops
ls /sys/fs/pstore/                                # captured crash logs, if any
```

**Limitations:** the watchdog cannot recover an instant power cut (the board is already off), and none of this repairs a corrupt or failing SD card — a steady ~1 Hz ACT-LED blink that never boots indicates the kernel is stuck (typically a corrupt rootfs), which needs a reflash onto a genuine endurance card.

### Bridge updater and apply path

Once `/etc/paradox-updater/release-public.pem` is pinned, both legacy entrypoints
delegate to the root-owned signed verifier. There is no unsigned fallback.
The signed timer automatically applies newer releases; app checks verify release
availability and manual apply can explicitly retry a quarantined release.

Deployments hold the same maintenance lock as OS jobs, preserve a durable code
backup, and require the exact version, matching installed hashes, active
services and three fresh panel health checks. Failure restores previous code;
an interrupted install is recovered before the next network request. See
[signed deployment](signed-pi-deployment.md) for limits and recovery commands.

Manual checks:

```bash
cat /opt/paradox-bridge/CURRENT_VERSION 2>/dev/null || true
cat /opt/paradox-bridge/update_status.json 2>/dev/null || true
curl -fsS http://127.0.0.1:8080/system/version
curl -fsS http://127.0.0.1:8080/system/update-status
```

Do not use `v*` Android tags for bridge recovery.

### OS package maintenance operations

Package maintenance is distinct from bridge source updates. The recovery plan keeps these operations admin-triggered, one-at-a-time, logged, and observable:

- check available OS package updates,
- repair interrupted package state with `dpkg --configure -a` and `apt-get -f install`,
- apply security updates only if the Pi image supports a reliable security-only path,
- run a full `apt-get upgrade` only after explicit confirmation,
- reboot after maintenance when required.

These controls are live as of bridge `1.0.2` and Android `1.2.12`.

Admin API:

- `GET /system/maintenance/status`
- `POST /system/maintenance/check-updates`
- `POST /system/maintenance/repair-packages`
- `POST /system/maintenance/security-upgrade`
- `POST /system/maintenance/full-upgrade` for package upgrades within the current OS release
- `GET /system/maintenance/jobs/{job_id}`
- `GET /system/maintenance/jobs/{job_id}/log`

Runtime state and logs are stored under `/var/lib/paradox-bridge/maintenance`.
Jobs are started with `sudo -n systemd-run --no-block --collect --property=Type=oneshot`
and run `/opt/paradox-bridge/scripts/maintenance_job.sh`.

Live verification on 2026-06-01:

- `/system/maintenance/check-updates` queued a systemd job and completed.
- The job reported 93 upgradable packages.
- `/system/maintenance/status` reported no active job and no reboot required.
- The Pi did not have `unattended-upgrade` installed, so security-only upgrades
  report unsupported until that package/config exists.

Do not document or automate an unattended package-upgrade path as safe, and do
not present this as an OS release upgrade.

---

## Bootstrap and separately managed assets

Use the [signed bootstrap and release procedure](signed-pi-deployment.md) for
bridge deployments. The following manual path is only for first installation
or separately reviewed static web/nginx changes, not routine bridge updates:

1. Sync `paradox-bridge/` into `/opt/paradox-bridge/`.
2. Sync `web-app/` into `/opt/paradox-bridge/web-app/`.
3. When nginx/site config changes: `sudo bash /opt/paradox-bridge/deploy/setup-nginx.sh`
4. When boot fsck policy changes: `sudo bash /opt/paradox-bridge/deploy/setup-boot-fsck.sh`
5. When watchdog scripts change: `sudo bash /opt/paradox-bridge/deploy/setup-wifi-watchdog.sh`
6. When boot-repair scripts change: `sudo bash /opt/paradox-bridge/deploy/setup-boot-repair.sh`
7. When crash-hardening scripts change: `sudo bash /opt/paradox-bridge/deploy/setup-watchdog.sh`, `setup-zram-swap.sh`, and `setup-panic-recovery.sh` (the last needs a reboot for cmdline/ramoops).
8. Restart the **paradox-bridge** systemd unit after application changes (unit name as on your image).

Use **SSH keys** and **sudo** appropriate to your environment; do not paste private key material into issues or PRs.

From-scratch service bootstrap is documented in `README.md` with the
2026-06-01 live-verified systemd unit shape:

- service user/group: `home` on the current Pi image, replace with your Pi user
  for a fresh image,
- venv: `/opt/paradox-bridge/venv` running Python 3.11,
- app config: `/etc/paradox-bridge/config.json`,
- mutable maintenance state: `/var/lib/paradox-bridge/maintenance`,
- app bind target: `127.0.0.1:8080` behind nginx,
- recovery timers: `wifi-watchdog.timer` and `paradox-boot-repair.timer`.

---

## CI/CD trust boundary

GitHub Actions holds the bridge signing key and Android keystore in encrypted
secrets. The Pi only receives the public bridge key. It makes outbound HTTPS
requests and never exposes SSH to a runner. Transport TLS, bridge release
signatures and Android APK signatures are separate controls. Publishing does
not mint or replace the Pi's TLS certificate. Keep deployment values and private
keys outside this repository.
