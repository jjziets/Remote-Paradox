# Remote Paradox — Project Plan

## Overview

Turn a **Raspberry Pi Zero 2 W** into a local bridge between a **Paradox SP6000** alarm
panel and an **Android app**, enabling arm/disarm and live zone monitoring over WiFi — no
cloud, no LTE, no external accounts.

---

## Hardware

| Component | Detail |
|-----------|--------|
| Alarm Panel | Paradox SP6000 REL |
| Pi | Raspberry Pi Zero 2 W |
| SD Card | 128 GB, Pi OS Bookworm arm64 |
| Hostname | `remote-paradox` |
| Pi User | `home` / `<see .env>` |
| WiFi | `<see .env>` |
| Network | DHCP on `192.168.50.x` |
| Alarm Code | `<see .env>` |

---

## 1. Wiring — SP6000 → Pi Zero 2 W

### SP6000 Serial Header (4-pin, 2.54 mm pitch)

```
SP6000 Board        Wire        Pi Zero 2 W GPIO
──────────────      ────        ────────────────
GND          ─────  black  ───  Pin 6  (GND)
TX           ─────  yellow ───  Pin 10 (GPIO 15 / RXD)
RX           ─────  green  ───  Pin 8  (GPIO 14 / TXD)
AUX+ (12V)          DO NOT CONNECT
```

### Voltage Warning — Level Shifter Required

The SP6000 serial runs at **5V TTL**. The Pi GPIO is **3.3V**.

**You MUST use a level shifter** between the panel and the Pi. Without one, the 5V
signal will damage the Pi's GPIO pins.

Recommended: a bidirectional logic level converter (e.g. BSS138-based 4-channel module,
~R30 from Micro Robotics or Communica).

```
SP6000 TX ── 5V side ──┐
                       │  Level Shifter
SP6000 RX ── 5V side ──┤  (HV ↔ LV)
                       │
Pi RXD    ── 3.3V side ┤
Pi TXD    ── 3.3V side ┘
GND ──────── common ───── GND
```

### Serial Parameters

```
Baud rate : 9600
Data bits : 8
Parity    : None
Stop bits : 1
Port      : /dev/serial0 (symlink — works regardless of BT state)
```

---

## 2. Pi Configuration (after first boot)

### 2.1 Enable UART & Disable Bluetooth

Add to `/boot/firmware/config.txt`:

```
enable_uart=1
dtoverlay=disable-bt
```

### 2.2 Free the Serial Port from Console

Remove `console=serial0,115200` from `/boot/firmware/cmdline.txt` so the kernel
does not use the UART for console output.

Before:
```
console=serial0,115200 console=tty1 root=PARTUUID=... rootfstype=ext4 ...
```

After:
```
console=tty1 root=PARTUUID=... rootfstype=ext4 ...
```

### 2.3 Disable Serial Getty

```bash
sudo systemctl stop serial-getty@ttyAMA0.service
sudo systemctl disable serial-getty@ttyAMA0.service
```

### 2.4 Reboot

```bash
sudo reboot
```

### 2.5 Verify UART

```bash
ls -l /dev/serial0          # should symlink to /dev/ttyAMA0
```

---

## 3. Software Stack

```
┌───────────────────────────────────────────────┐
│                 Android App                    │
│         (Kotlin / Jetpack Compose)             │
└──────────────────┬────────────────────────────┘
                   │  HTTP REST + WebSocket
                   │  192.168.50.x:8080
                   ▼
┌───────────────────────────────────────────────┐
│          paradox-bridge  (FastAPI)             │
│                                               │
│  /login           → JWT token                 │
│  /alarm/status    → partition + zones         │
│  /alarm/arm-away  → arm panel                 │
│  /alarm/arm-stay  → arm stay                  │
│  /alarm/disarm    → disarm with code          │
│  /ws              → live events               │
└──────────────────┬────────────────────────────┘
                   │  Python API calls
                   ▼
┌───────────────────────────────────────────────┐
│          PAI  (Paradox Alarm Interface)        │
│          pip install paradox-alarm-interface   │
│                                               │
│  CONNECTION_TYPE : Serial                     │
│  SERIAL_PORT     : /dev/serial0               │
│  SERIAL_BAUD     : 9600                       │
│  PASSWORD        : <see .env>                  │
└──────────────────┬────────────────────────────┘
                   │  Serial  9600 8N1
                   ▼
┌───────────────────────────────────────────────┐
│       Paradox SP6000 REL  Alarm Panel         │
│          (via level shifter)                  │
└───────────────────────────────────────────────┘
```

---

## 4. PAI — Installation & Configuration

### 4.1 Install

```bash
sudo apt update && sudo apt install -y python3-pip python3-venv
python3 -m venv /opt/paradox-bridge/venv
source /opt/paradox-bridge/venv/bin/activate
pip install paradox-alarm-interface
```

### 4.2 Configure

Create `/etc/pai/pai.conf` (YAML):

```yaml
CONNECTION_TYPE: Serial
SERIAL_PORT: '/dev/serial0'
SERIAL_BAUD: 9600

LOGGING_LEVEL_CONSOLE: 20      # INFO
LOGGING_LEVEL_FILE: 10         # DEBUG

PASSWORD: '<PANEL_PC_PASSWORD from .env>'

LIMITS:
  zone: auto
  user: auto
  partition: auto
```

> **Note:** The `PASSWORD` field is the panel's **PC password** (default `0000`), not the
> user arm/disarm code. Verify your PC password via Babyware or the installer
> menu (section `[953]`).

### 4.3 Test

```bash
source /opt/paradox-bridge/venv/bin/activate
pai-run
```

Watch logs for successful connection and zone/partition status messages.

---

## 5. paradox-bridge — FastAPI Service

### 5.1 Project Structure

```
paradox-bridge/
├── pyproject.toml
├── config.example.json
├── paradox-bridge.service       # systemd unit
├── src/
│   └── paradox_bridge/
│       ├── __init__.py
│       ├── main.py              # FastAPI app, startup/shutdown
│       ├── auth.py              # JWT login + middleware
│       ├── alarm.py             # PAI wrapper — status, arm, disarm
│       ├── models.py            # Pydantic request/response schemas
│       ├── config.py            # Load config.json
│       └── ws.py                # WebSocket manager — push events
└── tests/
    ├── conftest.py              # Shared fixtures, mock PAI
    ├── test_auth.py             # Login, token validation, expiry
    ├── test_alarm.py            # Status, arm-away, arm-stay, disarm
    └── test_ws.py               # WebSocket connection, event push
```

### 5.2 Configuration

`/etc/paradox-bridge/config.json`:

```json
{
  "serial_port": "/dev/serial0",
  "baudrate": 9600,
  "api_port": 8080,
  "api_host": "0.0.0.0",
  "jwt_secret": "<generated-on-first-run>",
  "jwt_expiry_hours": 24,
  "panel_pc_password": "0000",
  "users": [
    {
      "username": "admin",
      "password_hash": "<bcrypt hash>"
    }
  ]
}
```

### 5.3 API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/login` | No | Returns JWT token |
| `GET` | `/alarm/status` | Bearer | Partition state + zone list |
| `POST` | `/alarm/arm-away` | Bearer | Arm away (uses alarm code) |
| `POST` | `/alarm/arm-stay` | Bearer | Arm stay |
| `POST` | `/alarm/disarm` | Bearer | Disarm — requires `{"code":"<alarm_code>"}` |
| `GET` | `/health` | No | Service health check |
| `WS` | `/ws` | Token in query | Live zone/partition events |

### 5.4 WebSocket Events

```json
{"event": "zone_open",         "zone_id": 1, "zone_name": "Front Door"}
{"event": "zone_closed",       "zone_id": 1, "zone_name": "Front Door"}
{"event": "partition_armed",   "partition_id": 1, "mode": "away"}
{"event": "partition_disarmed","partition_id": 1}
{"event": "alarm_triggered",   "partition_id": 1, "source": "zone_3"}
```

### 5.5 Systemd Service

`/etc/systemd/system/paradox-bridge.service`:

```ini
[Unit]
Description=Paradox Alarm Bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=home
WorkingDirectory=/opt/paradox-bridge
ExecStart=/opt/paradox-bridge/venv/bin/uvicorn paradox_bridge.main:app --host 0.0.0.0 --port 8080
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

---

## 6. Android App (Phase 2)

### 6.1 Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| HTTP | Retrofit + OkHttp |
| WebSocket | OkHttp WebSocket |
| Auth | JWT stored in EncryptedSharedPreferences |
| Architecture | MVVM + Repository pattern |

### 6.2 Screens

1. **Settings** — Enter Pi IP, port, username, password
2. **Dashboard** — Alarm status, zone list (live via WebSocket)
3. **Controls** — ARM AWAY, ARM STAY, DISARM buttons

### 6.3 Mockup

```
┌────────────────────────────┐
│  Remote Paradox        ⚙️  │
│────────────────────────────│
│                            │
│   🔒  ARMED AWAY           │
│                            │
│  ┌──────────────────────┐  │
│  │ Front Door    Closed │  │
│  │ Kitchen       Closed │  │
│  │ Garage        Open ⚠ │  │
│  │ Back Door     Closed │  │
│  │ Motion Hall   Clear  │  │
│  └──────────────────────┘  │
│                            │
│  ┌────────┐ ┌────────┐    │
│  │ARM AWAY│ │ARM STAY│    │
│  └────────┘ └────────┘    │
│       ┌──────────┐        │
│       │  DISARM  │        │
│       └──────────┘        │
└────────────────────────────┘
```

---

## 7. Implementation Order (TDD)

Each step follows **Red → Green → Refactor**.

### Phase 1 — Pi Setup (no code, hardware + config)

| # | Task | Status |
|---|------|--------|
| 1.1 | Flash SD card with Pi OS Bookworm | ✅ Done |
| 1.2 | Headless config (WiFi, SSH, user) | ✅ Done |
| 1.3 | Boot Pi, verify SSH access | ✅ Done |
| 1.4 | Wire SP6000 → level shifter → Pi | ⬜ Pending |
| 1.5 | Enable UART, disable BT, free console | ✅ Done |
| 1.6 | Verify `/dev/serial0` exists | ✅ Done |

### Phase 2 — PAI (install + verify)

| # | Task | Status |
|---|------|--------|
| 2.1 | Install PAI in venv | ✅ Done |
| 2.2 | Configure PAI for SP6000 serial | ✅ Done |
| 2.3 | Run PAI, verify panel connection | ⬜ Pending |
| 2.4 | Test arm/disarm via PAI CLI | ⬜ Pending |

### Phase 3 — paradox-bridge (FastAPI service)

| # | Task | Status |
|---|------|--------|
| 3.1 | Project scaffold (pyproject.toml, structure) | ✅ Done |
| 3.2 | Test + implement config loader | ✅ Done |
| 3.3 | Test + implement auth (login, JWT, invite, register) | ✅ Done |
| 3.4 | Test + implement `/alarm/status` | ✅ Done |
| 3.5 | Test + implement `/alarm/arm-away` | ✅ Done |
| 3.6 | Test + implement `/alarm/arm-stay` | ✅ Done |
| 3.7 | Test + implement `/alarm/disarm` | ✅ Done |
| 3.8 | Test + implement WebSocket `/ws` | ✅ Done |
| 3.9 | Systemd service + auto-start | ✅ Done |
| 3.10 | Deploy to Pi, end-to-end test | ✅ Done |

### Phase 4 — Android App

| # | Task | Status |
|---|------|--------|
| 4.1 | Project scaffold (Kotlin, Compose) | ✅ Done |
| 4.2 | QR scan + invite registration flow | ✅ Done |
| 4.3 | Auth flow (login, token storage, encrypted prefs) | ✅ Done |
| 4.4 | Dashboard screen (status + zones + controls) | ✅ Done |
| 4.5 | Lifecycle-aware foreground polling | ✅ Done |
| 4.6 | WebSocket live updates | ⬜ Phase 2 |
| 4.7 | Error handling + reconnection | ✅ Done |

---

## 8. Security (v1 — Local Only)

| Measure | Detail |
|---------|--------|
| Network | Local WiFi only — no internet exposure |
| API Auth | JWT tokens, bcrypt password hashes |
| Disarm | Requires alarm code in request body |
| SSH | Key-based auth recommended after initial setup |
| Secrets | Config file permissions `600`, owned by service user |
| No cloud | Nothing leaves the LAN |

---

## 9. Shopping List

| Item | Purpose | Est. Price (ZAR) |
|------|---------|-----------------|
| Bidirectional logic level converter (3.3V ↔ 5V) | Protect Pi GPIO from 5V | ~R30 |
| Jumper wires (female-to-female, 3x) | GND + TX + RX | ~R15 |
| *(Optional)* USB-TTL adapter (CP2102 / CH340) | Alternative to GPIO serial | ~R50 |

> If you already have a logic level converter or USB-TTL adapter, you're good to go.

---

## 10. Future Expansion

| Feature | How |
|---------|-----|
| Remote access via VPN | Tailscale on the Pi |
| Cloud relay | Pi → MQTT → cloud → app |
| LTE module | SIM7600 HAT for cellular backup |
| Home Assistant | PAI → MQTT → HA integration |
| Push notifications | Firebase Cloud Messaging via bridge |
| Multiple users | Role-based access in the API |

---

## 11. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| 5V on Pi GPIO without level shifter | Destroys GPIO pins | **Always** use level shifter |
| Wrong PC password | PAI can't connect to panel | Check via Babyware or installer menu `[953]` |
| SP6000 firmware encryption (7.50+) | Serial breaks | Verify firmware version; do NOT upgrade |
| Pi loses power | Service stops | Auto-restart via systemd; UPS optional |
| WiFi drops | Alarm still works, app loses visibility | PAI auto-reconnects; app shows offline status |

---

*Last updated: 2026-03-09*
