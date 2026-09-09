# Remote Paradox

Local-network alarm controller: **Paradox SP6000** alarm panel ↔ **Raspberry Pi** ↔ **Android app / Web app**.

Arm, disarm, and monitor zones from your phone or browser over WiFi — no cloud, no subscriptions.

## Architecture

```
┌─────────────────────┐
│    Android App      │   HTTPS + cert pinning
│  (Jetpack Compose)  │◄─────────────────────────┐
└─────────────────────┘                          │
                                                 │
┌─────────────────────┐                          │
│    Web App          │   Same-origin (nginx)    │
│  (HTML5 / JS)       │◄─────────────────────────┤
└─────────────────────┘                          │
                                                 ▼
                                  ┌──────────────────────────┐
                                  │  nginx (port 9433)        │
                                  │  TLS · rate-limit · CSP   │
                                  │  static files + API proxy │
                                  ├──────────────────────────┤
                                  │  paradox-bridge (127.0.0.1)│
                                  │  FastAPI · JWT · SQLite    │
                                  │  Raspberry Pi Zero 2W      │
                                  └────────────┬─────────────┘
                                               │  Serial 9600 8N1
                                               ▼
                                  ┌──────────────────────────┐
                                  │  Paradox SP6000 REL       │
                                  │  Alarm Panel              │
                                  └──────────────────────────┘
```

## Quick Start

1. Copy `.env.example` to `.env` and fill in your values.
2. Flash the SD card: `sudo bash flash-sd.sh`
3. Boot the Pi and SSH in: `ssh home@remote-paradox.local`
4. Populate `/opt/paradox-bridge` from this repository.
5. Install nginx/recovery helpers from the repo-owned deploy scripts.
6. Verify the bridge service and generate an invite QR code from the web dashboard.
7. Install the Android APK and scan the invite QR code to register.

The detailed deploy-from-scratch path is in [Deployment From Scratch](#deployment-from-scratch-pi).
Pi operations and GitHub release notes are in [docs/pi-github-operations.md](docs/pi-github-operations.md).

## Project Structure

```
Remote Paradox/
├── .env.example              # Template — copy to .env
├── flash-sd.sh               # SD card flasher (sources .env)
├── PLAN.md                   # Detailed project plan
├── docs/
│   └── pi-github-operations.md  # Pi hardening & CI scope (no secrets)
├── paradox-bridge/           # FastAPI service (Python)
│   ├── src/paradox_bridge/
│   │   ├── main.py           # API routes
│   │   ├── alarm.py          # Alarm service (PAI / demo)
│   │   ├── virtual_panel.py  # Demo-mode virtual SP6000
│   │   ├── auth.py           # JWT + invite codes
│   │   ├── config.py         # Configuration loader
│   │   ├── database.py       # SQLite (users, invites, audit)
│   │   ├── tls.py            # Self-signed cert generation
│   │   └── demo_cli.py       # CLI for demo mode testing
│   ├── deploy/
│   │   ├── nginx-paradox-bridge.conf  # nginx reverse proxy config
│   │   ├── setup-nginx.sh             # Pi: nginx install
│   │   ├── setup-boot-fsck.sh         # Pi: force fsck repair at boot
│   │   ├── setup-boot-repair.sh       # Pi: install bridge/package repair timer
│   │   ├── boot-repair.sh             # Pi: conservative boot recovery
│   │   ├── wifi-watchdog.sh           # Pi: NM WiFi recovery (timer-driven)
│   │   └── setup-wifi-watchdog.sh     # Pi: install watchdog systemd units
│   ├── tests/
│   └── pyproject.toml
├── web-app/
│   └── index.html            # Standalone HTML5 web client
└── android-app/              # Android app (Kotlin / Compose)
    ├── app/
    ├── app-debug.apk         # Pre-built debug APK
    └── build.gradle.kts
```

## Web App

The web app is a single HTML file (`web-app/index.html`) that works in two modes:

### Production (on the Pi, served by nginx)

nginx serves `index.html` as a static file at `https://<pi-ip>:9433/`.
API calls use relative paths (`/auth/login`, `/alarm/status`) — same-origin, no CORS needed.

### Local Development (on your Mac/PC)

Open the file directly in your browser:

```
file:///Users/hanneszietsman/CrypotAI/Remote%20Paradox/web-app/index.html
```

Or on any machine, just double-click `web-app/index.html`.

The app auto-detects `file://` mode and shows **Server IP**, **Port**, and **HTTPS** fields
on the login screen. Point it at your Pi or local demo server:

| Field     | Example value     | Notes                           |
|-----------|-------------------|---------------------------------|
| Server IP | `192.168.50.32`   | Pi's LAN IP, or public IP       |
| Port      | `9433`            | nginx port (or 8080 for demo)   |
| HTTPS     | checked           | Uncheck only for plain HTTP dev |
| Username  | `admin`           | Your registered username        |
| Password  | `********`        | Your password                   |

The server address is saved in `localStorage` — you only enter it once.

### Features

- Real-time zone status with auto-polling (1s when arming/triggered, 5s idle)
- Arm Away / Arm Home / Disarm controls
- Panic buttons (Emergency, Medical, Fire) with confirmation
- Zone grid with bypass toggle
- Event history tab
- Admin: invite new users via QR code

## Android App

Android app releases use Git tags named `v*` and publish APK assets through
GitHub Actions. Download the phone APK from the
[latest Android release](https://github.com/jjziets/Remote-Paradox/releases)
or build from source:

```bash
cd android-app
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Install a downloaded release APK with:

```bash
adb install -r remote-paradox-phone.apk
```

For a physical phone, enable Developer Options and USB debugging, then accept
the device authorization prompt. For wireless debugging on recent Android
versions, use **Developer options > Wireless debugging > Pair device with pairing
code**, then run:

```bash
adb pair <phone-ip>:<pairing-port>
adb connect <phone-ip>:<adb-port>
adb install -r remote-paradox-phone.apk
```

### First-time setup
1. Admin generates an invite QR code (web app or API)
2. Scan the QR code in the Android app
3. Register with a username and password

### Returning users
The login screen shows editable **Server IP** and **Port** fields pre-populated
from saved config, plus username/password.

### Watch install and pairing

The watch APK is built by the same `v*` Android release workflow. Both APKs are
attached with independent versioned names, such as `remote-paradox-watch-1.2.31.apk`.
Install the watch APK when its own version is newer than the installed version.

Install to a Wear OS watch with ADB after pairing the watch over USB or wireless
debugging:

```bash
adb devices
adb install -r remote-paradox-watch.apk
```

For wireless watch debugging, enable **Developer options > Wireless debugging**
on the watch, pair with:

```bash
adb pair <watch-ip>:<pairing-port>
adb connect <watch-ip>:<adb-port>
adb install -r remote-paradox-watch.apk
```

Keep the phone and watch paired through the normal Wear OS companion app. The
phone app uses the Wear OS Data Layer to sync bridge connection details and
tokens to the watch.

## Demo Mode

Run the API locally for testing without a real alarm panel:

```bash
cd paradox-bridge

# Create a demo config
mkdir -p /tmp/paradox-dev
cat > /tmp/paradox-dev/config.json << 'JSON'
{
  "demo_mode": true,
  "tls_cert_path": "/tmp/paradox-dev/server.crt",
  "tls_key_path": "/tmp/paradox-dev/server.key"
}
JSON

# Start the demo server
PARADOX_CONFIG=/tmp/paradox-dev/config.json \
PARADOX_ADMIN_PASS=admin123 \
python -m uvicorn paradox_bridge.main:app \
  --host 0.0.0.0 --port 8080 \
  --ssl-keyfile /tmp/paradox-dev/server.key \
  --ssl-certfile /tmp/paradox-dev/server.crt
```

Then open `web-app/index.html`, enter your Mac's IP, port `8080`, HTTPS checked, and log in as `admin` / `admin123`.

### Demo CLI

Toggle zones and trigger panic from the terminal:

```bash
paradox-demo status                # Show all zones
paradox-demo open 9                # Open zone 9 (Front Door)
paradox-demo close 9               # Close zone 9
paradox-demo panic emergency       # Trigger emergency panic
```

## Security

| Layer | Protection |
|-------|-----------|
| **TLS** | Self-signed ECDSA P-256 cert (10-year validity) |
| **Certificate pinning** | SHA-256 fingerprint in QR invite; Android app trusts only that cert |
| **nginx** | TLS termination, rate limiting (10 req/s auth, 30 req/s API), security headers (CSP, HSTS, X-Frame-Options) |
| **Authentication** | JWT access tokens (30-day default), durable hashed refresh tokens (90-day default), bcrypt password hashing |
| **Invite system** | One-time codes, 15-minute expiry, admin-only generation |
| **Audit logging** | Every arm/disarm/panic/invite action logged with username |
| **SSH** | Key-only access (password auth disabled by deploy script) |
| **FastAPI** | Bound to 127.0.0.1 (not network-exposed), all endpoints require auth |
| **XSS** | All dynamic content in web app escaped via `esc()` |
| **Android** | Credentials in AES-256-GCM EncryptedSharedPreferences |

## Deployment (Pi)

Operational notes for release channels, recovery timers, and CI scope:
[docs/pi-github-operations.md](docs/pi-github-operations.md).

### Release channels

| Channel | Tag pattern | Payload | Consumer |
|---------|-------------|---------|----------|
| Android phone/watch | `v*` | Signed APK assets from `.github/workflows/build-android.yml` | Human installs APKs, phone app update checker |
| Pi bridge/source | `bridge-v*` | CI-tested archive with Ed25519-signed manifest and file hashes | Root-owned Pi pull updater and boot-repair recovery |

Both workflows use GitHub-hosted runners. After the one-time
[signed updater bootstrap](docs/signed-pi-deployment.md), the Pi automatically
pulls `bridge-v*` releases, verifies their signature, installs the bridge and
recovery helpers, and checks the exact running version and fresh panel polling.
Failed installs restore previous code. A GitHub release alone is not proof of
deployment: check the Pi's root-owned receipt and live health as documented in
the runbook. Existing TLS certificates, users and configuration are preserved;
OS packages are not upgraded by bridge releases.

### Deployment From Scratch (Pi)

This is the repo-backed path from a blank SD card to a working Pi. Commands that
depend on the current Pi image are explicitly marked for live verification.

#### 1. Prepare and flash the SD card

On the workstation:

```bash
cp .env.example .env
# Edit .env with WiFi, Pi username/password, alarm code, and admin password.
sudo bash flash-sd.sh
```

`flash-sd.sh` is macOS-oriented and currently targets `/dev/disk4`; verify the
disk before running. It writes the Raspberry Pi OS image named in the script,
enables SSH, creates the configured user, sets hostname, and creates the
NetworkManager WiFi profile named `preconfigured`.

Boot the Pi and connect:

```bash
ssh <PI_USERNAME>@remote-paradox.local
# or
ssh <PI_USERNAME>@<pi-ip-from-dhcp>
```

#### 2. Populate `/opt/paradox-bridge`

Until a dedicated Pi bootstrap script exists, sync the repository from the
workstation into the install layout expected by the deploy scripts:

```bash
ssh <PI_USERNAME>@remote-paradox.local 'sudo mkdir -p /opt/paradox-bridge && sudo chown <PI_USERNAME>:<PI_USERNAME> /opt/paradox-bridge'

rsync -avz --exclude='venv' --exclude='__pycache__' \
  --exclude='.venv' --exclude='.pytest_cache' --exclude='._*' \
  paradox-bridge/ <PI_USERNAME>@remote-paradox.local:/opt/paradox-bridge/

rsync -avz \
  web-app/ <PI_USERNAME>@remote-paradox.local:/opt/paradox-bridge/web-app/
```

The deploy scripts assume `/opt/paradox-bridge` contains `deploy/`, `scripts/`,
`src/`, `pyproject.toml`, and `web-app/`. If the current Pi image uses a
different layout, verify the image first and update these commands rather than
forcing the image to match this draft blindly.

#### 3. Bridge service bootstrap

The current live Pi path was verified against `remote-paradox` on 2026-06-01.
Run these on the Pi after `/opt/paradox-bridge` is populated:

```bash
sudo apt-get update
sudo apt-get install -y python3 python3-venv python3-pip python3-cryptography python3-dbus python3-gi bluez rsync

cd /opt/paradox-bridge
python3 -m venv --system-site-packages venv
./venv/bin/pip install -U pip
./venv/bin/pip install -e '.[pi]'

sudo mkdir -p /etc/paradox-bridge
sudo mkdir -p /var/lib/paradox-bridge/maintenance/jobs /var/lib/paradox-bridge/maintenance/logs
sudo chown -R <PI_USERNAME>:<PI_USERNAME> /opt/paradox-bridge /var/lib/paradox-bridge
sudo chmod 0750 /var/lib/paradox-bridge /var/lib/paradox-bridge/maintenance
```

Before enabling services, copy `install/config.json.example` from the checkout
to `/etc/paradox-bridge/config.json`, replace every placeholder, and generate a
fresh JWT secret (`openssl rand -hex 32`). Set the actual panel PC password and
UART settings, keep `api_host` at `127.0.0.1`, and disable the UART login shell
with `raspi-config`. Give the service user ownership of `/etc/paradox-bridge`
with directory mode `0700` and config mode `0600`; the database and initial TLS
certificate are created there. Add the service user to `dialout`. Do not replace
this directory when upgrading an existing Pi.

Install the tested systemd unit. Replace `<PI_USERNAME>` and
`<ADMIN_PASSWORD>` before running:

```bash
sudo tee /etc/systemd/system/paradox-bridge.service >/dev/null <<'UNIT'
[Unit]
Description=Paradox Alarm Bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=<PI_USERNAME>
Group=<PI_USERNAME>
WorkingDirectory=/opt/paradox-bridge
Environment=PYTHONPATH=/opt/paradox-bridge/src
Environment=PARADOX_CONFIG=/etc/paradox-bridge/config.json
Environment=PARADOX_ADMIN_USER=admin
Environment=PARADOX_ADMIN_PASS=<ADMIN_PASSWORD>
ExecStart=/opt/paradox-bridge/venv/bin/python3 /opt/paradox-bridge/start.py
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now paradox-bridge
```

Install `install/systemd/paradox-ble.service` from the checkout into
`/etc/systemd/system/`, then run `sudo systemctl daemon-reload` and
`sudo systemctl enable --now paradox-ble`. The BLE service uses Debian's D-Bus
and GI modules through the venv's `--system-site-packages` option.

The bridge binds to `127.0.0.1:8080`; nginx exposes it on HTTPS port `9433`.
After the first successful admin login, change the bootstrap admin password from
the app or web dashboard.

#### 4. Install nginx and recovery hardening

After `/opt/paradox-bridge` is populated and the bridge service can start:

```bash
ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-nginx.sh'

ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-boot-fsck.sh'

ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-wifi-watchdog.sh'

ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-power-hardening.sh'

ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-state-recorder.sh'

ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-boot-repair.sh'

ssh <PI_USERNAME>@remote-paradox.local \
  'sudo bash /opt/paradox-bridge/deploy/setup-command-diagnostics.sh && sudo systemctl restart paradox-bridge'
```

Recovery helpers:

- `setup-boot-fsck.sh` adds `fsck.mode=force fsck.repair=yes` to the boot
  command line.
- `setup-wifi-watchdog.sh` installs `wifi-watchdog.timer`, which repairs
  recoverable NetworkManager/WiFi states for interface `wlan0` and connection
  `preconfigured`.
- `setup-power-hardening.sh` masks system sleep targets, disables logind
  suspend actions, and sets NetworkManager WiFi power save to disabled for the
  `preconfigured` connection.
- `setup-state-recorder.sh` installs `paradox-state-recorder.service`, which
  writes one compact Pi state sample per second under
  `/var/log/paradox-bridge/state-recorder/` and deletes minute log files older
  than 24 hours. See `docs/pi-github-operations.md` for the triage commands and
  how to interpret the recorder output.
- `setup-command-diagnostics.sh` enables private, rotating command timing and
  panel-state logs after the next bridge restart. See
  [arm/disarm diagnostics](docs/pi-github-operations.md#armdisarm-command-diagnostics)
  when commands time out or the app appears to show stale alarm state.
- `setup-boot-repair.sh` installs `paradox-boot-repair.timer`, which repairs
  interrupted package state, restarts the bridge, and can force-stage/reinstall
  the latest `bridge-v*` release if the local bridge remains unhealthy.

These helpers do not repair total power loss, a dead SD card, an unbootable
kernel, or a network that never comes up.

#### 5. Enable signed CI/CD and verify services

Follow the [signed release bootstrap](docs/signed-pi-deployment.md#one-time-bootstrap)
to pin the reviewed public key and install `paradox-signed-updater.timer`.
The installer does not regenerate TLS certificates or require a GitHub token
on the Pi. Do not enable the legacy unsigned updater timer.

```bash
ssh <PI_USERNAME>@remote-paradox.local 'systemctl status paradox-bridge --no-pager'
ssh <PI_USERNAME>@remote-paradox.local 'systemctl list-timers wifi-watchdog.timer paradox-boot-repair.timer --no-pager'
ssh <PI_USERNAME>@remote-paradox.local 'curl -fsS http://127.0.0.1:8080/health'
ssh <PI_USERNAME>@remote-paradox.local 'curl -fsS http://127.0.0.1:8080/system/version'
ssh <PI_USERNAME>@remote-paradox.local 'sudo cat /var/lib/paradox-updater/deployment.json'
```

#### 6. Register clients

Generate an admin invite from the web dashboard or API, then install the Android
phone APK from a `v*` release and scan the QR code. Install the watch APK only
when the release includes a newer `remote-paradox-watch-<version>.apk`, then keep the watch paired
to the phone so credential sync can run.

### Maintenance operations

Bridge app update controls are separate from OS/package maintenance:

- Bridge updates verify and apply signed `bridge-v*` releases automatically;
  see the deployment runbook to pause the timer or inspect a rollback.
- OS maintenance should be explicit and admin-only: check updates, repair
  interrupted package state, apply security updates if supported by the image,
  run `apt-get update` and `apt-get -y upgrade` only after a strong
  confirmation, and reboot when
  needed.

The maintenance API/UI is available from bridge `1.0.2` and Android `1.2.12`.
On 2026-06-01 the live Pi verified `check-updates` through the API: the job ran
under systemd, completed successfully, and reported 93 upgradable packages with
no reboot required.

Phone Settings now has a **Pi OS Maintenance** card with:

- Check OS Updates
- Repair Package State
- Apply Security Updates, disabled when unsupported by the Pi image
- Upgrade Packages, requiring exact confirmation; this upgrades installed
  packages on the current OS release and does not perform an OS release upgrade

The bridge endpoints are admin-only:

- `GET /system/maintenance/status`
- `POST /system/maintenance/check-updates`
- `POST /system/maintenance/repair-packages`
- `POST /system/maintenance/security-upgrade`
- `POST /system/maintenance/full-upgrade` for package upgrades within the
  current OS release
- `GET /system/maintenance/jobs/{job_id}`
- `GET /system/maintenance/jobs/{job_id}/log`

Runtime job state and logs live under `/var/lib/paradox-bridge/maintenance`.
The tested Pi does not currently have `unattended-upgrade` installed, so
security-only upgrades report unsupported until that package/config is added.

### Router Port Forwarding

| WAN Port | → Pi Port | Service |
|----------|-----------|---------|
| 9433     | 9433      | HTTPS (nginx → FastAPI) |
| 22       | 22        | SSH (key-only) |

## Configuration

All secrets live in `.env` (never committed). See `.env.example` for the template.

Server config: `/etc/paradox-bridge/config.json` (auto-generated on first run).

## Connecting to the Pi

```bash
# mDNS
ssh home@remote-paradox.local

# Or by IP (check router DHCP leases)
ssh home@192.168.50.32
```

## License

Private — all rights reserved.
