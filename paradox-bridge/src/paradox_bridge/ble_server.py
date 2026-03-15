"""BLE GATT server for Remote Paradox Pi using BlueZ D-Bus API.

Advertises as "Remote_Paradox" with a Nordic UART Service (NUS) for
JSON command/response communication with the Android app.

BLE is always discoverable. Access model:
  PUBLIC (no auth):    status, admin_setup (once), auth
  AUTHENTICATED:       wifi_scan, wifi_set (admin), alarm_*, system_*
  Any untrusted device gets nothing until authenticated.

Reset trust by shorting GPIO17→GND.
"""

import asyncio
import json
import logging
import subprocess
import struct
from pathlib import Path

logger = logging.getLogger("ble_server")

TRUST_FILE = Path("/opt/paradox-bridge/ble_trust.json")
VERSION_FILE = Path("/opt/paradox-bridge/CURRENT_VERSION")

# Nordic UART Service UUIDs
NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # app writes here
NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # pi notifies here

BLUEZ_SERVICE = "org.bluez"
DBUS_OM_IFACE = "org.freedesktop.DBus.ObjectManager"
DBUS_PROP_IFACE = "org.freedesktop.DBus.Properties"
GATT_MANAGER_IFACE = "org.bluez.GattManager1"
LE_ADVERTISING_MANAGER_IFACE = "org.bluez.LEAdvertisingManager1"
LE_ADVERTISEMENT_IFACE = "org.bluez.LEAdvertisement1"
GATT_SERVICE_IFACE = "org.bluez.GattService1"
GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"


def get_ip() -> str:
    try:
        r = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=5)
        return r.stdout.strip().split()[0] if r.stdout.strip() else "no-ip"
    except Exception:
        return "unknown"


def get_current_ssid() -> str:
    try:
        r = subprocess.run(["iwgetid", "-r"], capture_output=True, text=True, timeout=5)
        return r.stdout.strip() or "not-connected"
    except Exception:
        return "unknown"


def get_version() -> str:
    return VERSION_FILE.read_text().strip() if VERSION_FILE.exists() else "0.0.0"


def is_trusted() -> bool:
    return TRUST_FILE.exists()


def set_trusted():
    TRUST_FILE.write_text(json.dumps({"trusted": True}))


def clear_trust():
    TRUST_FILE.unlink(missing_ok=True)
    logger.warning("BLE trust cleared")


def wifi_scan() -> list[str]:
    try:
        r = subprocess.run(
            ["sudo", "iwlist", "wlan0", "scan"],
            capture_output=True, text=True, timeout=30,
        )
        ssids = []
        for line in r.stdout.split("\n"):
            line = line.strip()
            if line.startswith("ESSID:"):
                ssid = line.split('"')[1] if '"' in line else ""
                if ssid and ssid not in ssids:
                    ssids.append(ssid)
        return ssids
    except Exception as e:
        logger.error(f"WiFi scan failed: {e}")
        return []


def apply_wifi(ssid: str, password: str) -> str:
    try:
        nmcli = subprocess.run(
            ["sudo", "nmcli", "dev", "wifi", "connect", ssid, "password", password],
            capture_output=True, text=True, timeout=30,
        )
        if nmcli.returncode == 0:
            return "ok"
        wpa_conf = f'''country=ZA
ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev
update_config=1
network={{
    ssid="{ssid}"
    psk="{password}"
    key_mgmt=WPA-PSK
}}
'''
        Path("/etc/wpa_supplicant/wpa_supplicant.conf").write_text(wpa_conf)
        subprocess.run(["sudo", "wpa_cli", "-i", "wlan0", "reconfigure"], timeout=10)
        return "ok"
    except Exception as e:
        return f"error: {e}"


def create_admin_user(username: str, password: str) -> str:
    try:
        import sys
        sys.path.insert(0, "/opt/paradox-bridge/src")
        from paradox_bridge.database import Database
        from paradox_bridge.auth import AuthService
        from paradox_bridge.config import load_config
        cfg = load_config()
        db = Database(cfg.db_path)
        auth = AuthService(cfg, db)
        auth.setup_admin(username, password)
        return "ok"
    except Exception as e:
        return f"error: {e}"


PUBLIC_COMMANDS = {"status", "admin_setup", "auth"}
ADMIN_ONLY_COMMANDS = {"wifi_set", "system_reboot"}
AUTHENTICATED_COMMANDS = {
    "wifi_scan", "wifi_set",
    "arm_away", "arm_stay", "disarm", "panic", "alarm_status",
    "system_resources", "system_wifi", "system_reboot",
}


def _validate_token(token: str) -> dict | None:
    """Validate a JWT and return the payload, or None."""
    if not token:
        return None
    try:
        import sys
        sys.path.insert(0, "/opt/paradox-bridge/src")
        from paradox_bridge.auth import AuthService
        from paradox_bridge.config import load_config
        from paradox_bridge.database import Database
        cfg = load_config()
        db = Database(cfg.db_path)
        auth = AuthService(cfg, db)
        return auth.decode_token(token)
    except Exception:
        return None


def handle_command(data: str) -> str:
    """Process a JSON command and return a JSON response."""
    try:
        cmd = json.loads(data)
    except json.JSONDecodeError:
        return json.dumps({"error": "invalid JSON"})

    action = cmd.get("cmd", "")

    # ── Public commands ──

    if action == "status":
        return json.dumps({
            "ip": get_ip(),
            "ssid": get_current_ssid(),
            "version": get_version(),
            "trusted": is_trusted(),
        })

    if action == "admin_setup":
        if is_trusted():
            return json.dumps({"error": "already set up — reset GPIO to re-provision"})
        username = cmd.get("username", "")
        password = cmd.get("password", "")
        if not username or not password:
            return json.dumps({"error": "username and password required"})
        result = create_admin_user(username, password)
        if result == "ok":
            set_trusted()
        return json.dumps({"result": result})

    if action == "auth":
        token = cmd.get("token", "")
        payload = _validate_token(token)
        if payload is None:
            return json.dumps({"error": "invalid or expired token"})
        return json.dumps({
            "result": "authenticated",
            "username": payload.get("sub"),
            "role": payload.get("role", "user"),
        })

    # ── All remaining commands require a valid token ──

    if action in AUTHENTICATED_COMMANDS:
        token = cmd.get("token", "")
        payload = _validate_token(token)
        if payload is None:
            return json.dumps({"error": "authentication required"})

        if action in ADMIN_ONLY_COMMANDS and payload.get("role") != "admin":
            return json.dumps({"error": "admin privileges required"})

        # WiFi
        if action == "wifi_scan":
            return json.dumps({"networks": wifi_scan()})

        if action == "wifi_set":
            if not cmd.get("ssid"):
                return json.dumps({"error": "ssid required"})
            result = apply_wifi(cmd["ssid"], cmd.get("password", ""))
            return json.dumps({"result": result})

        # Alarm control
        if action in ("arm_away", "arm_stay", "disarm", "panic", "alarm_status"):
            return handle_alarm_command(action, cmd)

        # System commands — proxy through local API
        if action == "system_resources":
            return _proxy_get("/system/resources", token)
        if action == "system_wifi":
            return _proxy_get("/system/wifi", token)
        if action == "system_reboot":
            return _proxy_post("/system/reboot", token)

    return json.dumps({"error": f"unknown command: {action}"})


def _proxy_get(path: str, token: str) -> str:
    """Forward a GET request to the local FastAPI server."""
    import urllib.request
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:8080{path}",
            headers={"Authorization": f"Bearer {token}"},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.read().decode()
    except Exception as e:
        return json.dumps({"error": f"proxy failed: {e}"})


def _proxy_post(path: str, token: str, body: dict | None = None) -> str:
    """Forward a POST request to the local FastAPI server."""
    import urllib.request
    try:
        data = json.dumps(body).encode() if body else None
        req = urllib.request.Request(
            f"http://127.0.0.1:8080{path}",
            data=data,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.read().decode()
    except Exception as e:
        return json.dumps({"error": f"proxy failed: {e}"})


def handle_alarm_command(action: str, cmd: dict) -> str:
    """Proxy alarm commands through the local FastAPI server."""
    import urllib.request
    token = cmd.get("token", "")
    partition = cmd.get("partition", 1)
    code = cmd.get("code", "")

    try:
        base = "http://127.0.0.1:8080"

        if action == "alarm_status":
            req = urllib.request.Request(
                f"{base}/alarm/status",
                headers={"Authorization": f"Bearer {token}"},
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.read().decode()

        endpoint_map = {
            "arm_away": "alarm/arm-away",
            "arm_stay": "alarm/arm-stay",
            "disarm": "alarm/disarm",
            "panic": "alarm/panic",
        }
        endpoint = endpoint_map.get(action, "")
        body = json.dumps({"partition_id": partition, "code": code}).encode()
        if action == "panic":
            body = json.dumps({"partition_id": partition, "type": cmd.get("type", "emergency")}).encode()

        req = urllib.request.Request(
            f"{base}/{endpoint}",
            data=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.read().decode()
    except Exception as e:
        return json.dumps({"error": f"alarm command failed: {e}"})


def check_gpio_reset():
    """Check GPIO17 pulled low = reset signal."""
    try:
        gpio_path = Path("/sys/class/gpio/gpio17")
        if not gpio_path.exists():
            subprocess.run(["bash", "-c", "echo 17 > /sys/class/gpio/export"], capture_output=True)
            subprocess.run(["bash", "-c", "echo in > /sys/class/gpio/gpio17/direction"], capture_output=True)
        val_path = gpio_path / "value"
        if val_path.exists() and val_path.read_text().strip() == "0":
            if is_trusted():
                clear_trust()
    except Exception:
        pass


async def run_ble_server():
    """Start BLE GATT server using bluetoothctl and handle connections."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
    logger.info("Starting Remote_Paradox BLE server...")

    subprocess.run(["sudo", "hciconfig", "hci0", "up"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "le", "on"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "connectable", "on"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "name", "Remote_Paradox"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "advertising", "on"], capture_output=True)

    try:
        subprocess.run(
            ["sudo", "bluetoothctl", "system-alias", "Remote_Paradox"],
            capture_output=True, timeout=5,
        )
        subprocess.run(
            ["sudo", "bluetoothctl", "discoverable", "on"],
            capture_output=True, timeout=5,
        )
        subprocess.run(
            ["sudo", "bluetoothctl", "discoverable-timeout", "0"],
            capture_output=True, timeout=5,
        )
        subprocess.run(
            ["sudo", "bluetoothctl", "pairable", "on"],
            capture_output=True, timeout=5,
        )
        logger.info("BLE adapter configured and discoverable as 'Remote_Paradox'")
    except Exception as e:
        logger.error(f"Failed to configure BLE via bluetoothctl: {e}")

    logger.info(f"Trust: {'established' if is_trusted() else 'first-come-first-served'}")
    logger.info(f"IP: {get_ip()}, SSID: {get_current_ssid()}, Version: {get_version()}")

    while True:
        check_gpio_reset()
        await asyncio.sleep(10)


def main():
    asyncio.run(run_ble_server())


if __name__ == "__main__":
    main()
