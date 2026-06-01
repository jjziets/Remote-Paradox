# Pi operations tracked in GitHub (no secrets)

This document records **Pi-related behaviour and repo-owned tooling** so the team can reproduce setup from the repository. It does **not** include private keys, passphrases, LAN/WAN addresses, WiFi credentials, or router admin details. Keep those in an **organisation secret store** or a local `.env` that stays out of git (see `.env.example`).

---

## GitHub Actions vs the Pi

| Trigger | What runs | What it does **not** do |
|--------|-------------|-------------------------|
| Push to **`main`** | `.github/workflows/build-android.yml` | Does **not** deploy to the Pi |
| Push **`v*`** tags | Same workflow + GitHub Release with Android APK assets | Same — **no** Pi deploy |
| Publish **`bridge-v*`** releases | No workflow in this repo today | Does **not** build Android APKs |

The Android workflow builds **signed Android release APKs** on GitHub-hosted runners, uploads **artifacts**, and on `v*` tags publishes APKs to a **release**. It always builds the phone APK. It builds and uploads the watch APK only when watch files or shared Gradle files changed.

Bridge/Pi releases use the separate `bridge-v*` tag channel. The Pi-side updater selects non-draft, non-prerelease `bridge-v*` releases and stages the GitHub source tarball. This keeps bridge recovery separate from Android APK releases. **Paradox Bridge, nginx, the web app, and Pi-only scripts are not deployed by CI** unless you add a separate workflow and drive deploy credentials from **GitHub Encrypted Secrets** (never hard-code keys or hosts in YAML).

Current bridge release state from the recovery plan: production has reported bridge `/system/version` as `1.0.1` from `bridge-v1.0.1`. Re-check the live Pi before using that as release evidence.

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

### Bridge updater and apply path

The Pi update path is bridge-source based:

1. `paradox-bridge/scripts/updater.py` queries GitHub releases.
2. It selects the latest non-draft, non-prerelease `bridge-v*` release.
3. It stages the release tarball under `/opt/paradox-bridge/staging`.
4. It writes `/opt/paradox-bridge/update_status.json` with pending version metadata.
5. `paradox-bridge/scripts/apply_update.sh` copies staged bridge source, scripts, deploy helpers, and package metadata into `/opt/paradox-bridge`, creates `/var/lib/paradox-bridge/maintenance`, reinstalls Python dependencies best-effort, ensures recovery helpers are installed, and restarts bridge/Bluetooth services.

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

## Manual deploy checklist (bridge + static assets)

Until a dedicated deploy workflow exists:

1. Sync `paradox-bridge/` into `/opt/paradox-bridge/`.
2. Sync `web-app/` into `/opt/paradox-bridge/web-app/`.
3. When nginx/site config changes: `sudo bash /opt/paradox-bridge/deploy/setup-nginx.sh`
4. When boot fsck policy changes: `sudo bash /opt/paradox-bridge/deploy/setup-boot-fsck.sh`
5. When watchdog scripts change: `sudo bash /opt/paradox-bridge/deploy/setup-wifi-watchdog.sh`
6. When boot-repair scripts change: `sudo bash /opt/paradox-bridge/deploy/setup-boot-repair.sh`
7. Restart the **paradox-bridge** systemd unit after application changes (unit name as on your image).

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

## Future: automated Pi deploy from GitHub

If you add CI deploy later:

- Use **repository or organisation secrets** for SSH host, user, and **private key** (or a short-lived token pattern your org approves).
- Prefer **`[self-hosted, linux]`** or a locked-down SSH path per your org’s runner policy.
- Keep deploy scripts in-repo; keep **values** in secrets.

This file should remain free of deploy **values** so it stays safe to share publicly.
