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
4. Install nginx proxy: `sudo bash /opt/paradox-bridge/deploy/setup-nginx.sh`
5. Generate an invite QR code from the web dashboard (admin only).
6. Scan the QR code with the Android app to register.

## Project Structure

```
Remote Paradox/
├── .env.example              # Template — copy to .env
├── flash-sd.sh               # SD card flasher (sources .env)
├── PLAN.md                   # Detailed project plan
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
│   │   └── setup-nginx.sh            # Pi deployment script
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

Download the APK from the [latest release](https://github.com/jjziets/Remote-Paradox/releases)
or build from source:

```bash
cd android-app
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

### First-time setup
1. Admin generates an invite QR code (web app or API)
2. Scan the QR code in the Android app
3. Register with a username and password

### Returning users
The login screen shows editable **Server IP** and **Port** fields pre-populated
from saved config, plus username/password.

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
| **Authentication** | JWT bearer tokens (72-hour expiry), bcrypt password hashing |
| **Invite system** | One-time codes, 15-minute expiry, admin-only generation |
| **Audit logging** | Every arm/disarm/panic/invite action logged with username |
| **SSH** | Key-only access (password auth disabled by deploy script) |
| **FastAPI** | Bound to 127.0.0.1 (not network-exposed), all endpoints require auth |
| **XSS** | All dynamic content in web app escaped via `esc()` |
| **Android** | Credentials in AES-256-GCM EncryptedSharedPreferences |

## Deployment (Pi)

```bash
# 1. Rsync code to the Pi
rsync -avz --exclude='.git' --exclude='venv' --exclude='__pycache__' \
  . home@remote-paradox:/opt/paradox-bridge/

# 2. Install nginx + harden SSH
ssh home@remote-paradox 'sudo bash /opt/paradox-bridge/deploy/setup-nginx.sh'

# 3. Restart the service
ssh home@remote-paradox 'sudo systemctl restart paradox-bridge'
```

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
