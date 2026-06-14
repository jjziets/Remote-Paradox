# Remote Paradox — Install from scratch

This guide builds a working **Remote Paradox** node on a fresh Raspberry Pi: the
`paradox-bridge` service connects to a Paradox alarm panel over serial and exposes
a local + remote API (and BLE fallback) that the Android phone/watch apps and the
web dashboard talk to.

It is written to be **safe to publish** — every credential, host, key, and serial
value here is a placeholder. Put your real values only in files that stay off git
(`.env`, `/etc/paradox-bridge/config.json`, your TLS keys).

> Replaces nothing destructive remotely — this is for a *new* card. For recovering
> an existing node, see [`docs/pi-github-operations.md`](../docs/pi-github-operations.md).

---

## 1. Hardware

| Item | Notes |
|------|-------|
| Raspberry Pi | Reference build is a **Pi Zero 2 W** (≈512 MB RAM). A Pi 3/4 with more RAM is more forgiving. |
| microSD | Use a **genuine endurance card** (SanDisk High/Max Endurance, Samsung PRO Endurance). Cheap/counterfeit cards are the #1 cause of random lock-ups and no-boot. |
| Power | Stable 5 V. For an always-on alarm node, add a **UPS / safe-shutdown HAT or supercap** — an unclean power cut can corrupt the SD card. |
| Wiring to the panel | TTL serial between the Pi UART and the Paradox panel's serial/PGM header (default **9600 baud**, `/dev/serial0`). See the pin-out reference image in the repo root and your panel's manual. **Do not cross 5 V/3.3 V levels** — use the correct level shifting for your panel. |

---

## 2. Flash the OS

Reference image: **Raspberry Pi OS (Debian 12 "bookworm"), 64-bit**.

**Option A — the repo flasher (headless):**
1. `cp .env.example .env` and fill it in (hostname, user, Wi-Fi, first-boot password). `.env` is git-ignored — keep it that way.
2. `sudo ./flash-sd.sh` — writes the image and a first-boot config that sets the hostname, creates the user, joins Wi-Fi (`preconfigured` NetworkManager profile), and enables SSH.

**Option B — Raspberry Pi Imager:** select Raspberry Pi OS (64-bit), and in the
advanced options set hostname, enable SSH, create the user, and configure Wi-Fi.

Boot the Pi and wait ~90 s.

---

## 3. First login

```bash
ssh <user>@<hostname>.local        # e.g. ssh home@remote-paradox.local
```

Install your SSH **public** key and switch to key-only auth (step 6's `setup-nginx.sh`
disables password auth — make sure your key works first):
```bash
ssh-copy-id <user>@<hostname>.local
```

---

## 4. Put the bridge on the Pi

```bash
# from your workstation, sync the repo onto the Pi:
rsync -a paradox-bridge/ <user>@<hostname>:/tmp/pb/      # then move into place as root, or:
sudo rsync -a /tmp/pb/ /opt/paradox-bridge/
sudo rsync -a web-app/  /opt/paradox-bridge/web-app/

# build the Python venv (Python 3.11 on bookworm):
sudo python3 -m venv /opt/paradox-bridge/venv
sudo /opt/paradox-bridge/venv/bin/pip install -e /opt/paradox-bridge

# runtime state dir (owned by the service user):
sudo install -d -o <user> -g <user> \
  /var/lib/paradox-bridge/maintenance/jobs /var/lib/paradox-bridge/maintenance/logs
sudo apt-get update && sudo apt-get install -y nginx
```

---

## 5. Configure the bridge

```bash
sudo install -d /etc/paradox-bridge
sudo cp install/config.json.example /etc/paradox-bridge/config.json
sudo nano /etc/paradox-bridge/config.json
```
Fill in:
- `jwt_secret` → `openssl rand -hex 32`
- `panel_pc_password` → your Paradox panel **PC password** (factory default is often `0000`)
- `serial_port` / `serial_baud` → match your wiring (`/dev/serial0`, `9600`)
- `public_host` / `public_port` → your DDNS/host and the port you forward for remote access (leave defaults for LAN-only)
- TLS: drop a cert/key at `tls_cert_path`/`tls_key_path` (a self-signed pair is fine for a private deployment).

> Enable the Pi UART for `/dev/serial0`: `sudo raspi-config` → Interface Options →
> Serial Port → login shell **No**, hardware serial **Yes** (or set `enable_uart=1`).

---

## 6. Install the services

```bash
sudo cp install/systemd/paradox-*.service install/systemd/paradox-*.timer /etc/systemd/system/
sudo nano /etc/systemd/system/paradox-bridge.service   # set PARADOX_ADMIN_USER / PARADOX_ADMIN_PASS, and User= if not 'home'
sudo systemctl daemon-reload
sudo systemctl enable --now paradox-bridge paradox-ble paradox-state-recorder \
     paradox-updater.timer
```
- `paradox-bridge` — the API/app (binds `127.0.0.1:8080`).
- `paradox-ble` — local Bluetooth LE control fallback (root).
- `paradox-state-recorder` — 1 Hz state log for debugging.
- `paradox-updater.timer` — checks for `bridge-v*` releases every 15 min.

Then the reverse proxy (this also **hardens SSH to key-only**):
```bash
sudo bash /opt/paradox-bridge/deploy/setup-nginx.sh
```

---

## 7. Reliability hardening (recommended)

These idempotent scripts make the node survive crashes and recover on its own. They
are also re-applied automatically by the updater's `apply_update.sh`.

```bash
cd /opt/paradox-bridge/deploy
sudo bash setup-boot-fsck.sh        # force + auto-repair fsck at boot
sudo bash setup-wifi-watchdog.sh    # recover dropped Wi-Fi
sudo bash setup-boot-repair.sh      # repair package state + bridge health on a timer
sudo bash setup-watchdog.sh         # hardware watchdog: auto-reset a soft hang
sudo bash setup-zram-swap.sh        # compressed RAM swap instead of an SD swapfile
sudo bash setup-panic-recovery.sh   # auto-reboot on kernel panic + capture crash log
sudo reboot                         # required for the cmdline/ramoops changes
```

For an always-on alarm node, also consider a **read-only / overlay root** so a power
cut cannot corrupt the filesystem.

---

## 8. Verify

```bash
curl -fsS http://127.0.0.1:8080/health          # {"status":"ok","alarm_connected":true,...}
systemctl list-timers --all                     # updater / boot-repair / wifi-watchdog
swapon --show                                    # /dev/zram0
systemctl show -p RuntimeWatchdogUSec --value    # ~14s
grep -o 'panic=[0-9]*' /proc/cmdline             # panic=10
cat /sys/module/pstore/parameters/backend        # ramoops (persistent crash log)
```

Then build/install the Android apps from [`android-app/`](../android-app/) and point
them at your node. The web dashboard is served by nginx from `web-app/`.

---

## 9. Updating

Bridge/Pi updates ship on the **`bridge-v*`** GitHub release channel (separate from
the Android `v*` APK releases). The updater stages the release and `apply_update.sh`
installs the new source, re-runs the deploy scripts, and restarts services. See
[`docs/pi-github-operations.md`](../docs/pi-github-operations.md) for details.

---

## 10. Security checklist (before exposing anything)

- [ ] Changed `PARADOX_ADMIN_PASS`, `panel_pc_password`, and generated a fresh `jwt_secret`.
- [ ] SSH is **key-only** (no password auth) and your key is installed.
- [ ] Real TLS cert/key in place; remote access goes through nginx, not the raw `:8080`.
- [ ] `.env`, `config.json`, TLS keys, and any host/IP details are **kept out of git**.
- [ ] Only the intended port is forwarded at your router for remote access.
