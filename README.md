# Remote Paradox

Local-network alarm controller: **Paradox SP6000** alarm panel ↔ **Raspberry Pi** ↔ **Android app**.

Arm, disarm, and monitor zones from your phone over WiFi — no cloud, no subscriptions.

## Architecture

```
┌─────────────────────┐
│    Android App      │   HTTPS + cert pinning
│  (Jetpack Compose)  │◄──────────────────────►┐
└─────────────────────┘                        │
                                               ▼
                                  ┌─────────────────────┐
                                  │  paradox-bridge      │
                                  │  (FastAPI + TLS)     │
                                  │  Raspberry Pi Zero 2W│
                                  └──────────┬──────────┘
                                             │  Serial 9600 8N1
                                             ▼
                                  ┌─────────────────────┐
                                  │  Paradox SP6000 REL  │
                                  │  Alarm Panel         │
                                  └─────────────────────┘
```

## Quick Start

1. Copy `.env.example` to `.env` and fill in your values.
2. Flash the SD card: `sudo bash flash-sd.sh`
3. Boot the Pi and SSH in: `ssh <user>@remote-paradox.local`
4. The `paradox-bridge` service starts automatically on boot.
5. Generate an invite QR code: `paradox-invite --print`
6. Scan the QR code with the Android app to register.

## Project Structure

```
Remote Paradox/
├── .env.example          # Template — copy to .env
├── flash-sd.sh           # SD card flasher (sources .env)
├── PLAN.md               # Detailed project plan
├── paradox-bridge/       # FastAPI service (Python)
│   ├── src/paradox_bridge/
│   ├── tests/
│   └── pyproject.toml
└── android-app/          # Android app (Kotlin / Compose)
    ├── app/
    └── build.gradle.kts
```

## Security

- **Self-signed TLS** — the API is served over HTTPS.
- **Certificate pinning** — the cert's SHA-256 fingerprint is embedded in the QR invite code; the Android app trusts only that cert.
- **JWT auth** — one-time invite codes for registration, JWT bearer tokens for API access.
- **Audit logging** — every arm/disarm action is logged with the username.

## Configuration

All secrets live in `.env` (never committed). See `.env.example` for the template.

## Connecting to the Pi

```bash
# mDNS
ssh home@remote-paradox.local

# Or by IP (check router DHCP leases)
ssh home@192.168.50.32
```

## License

Private — all rights reserved.
