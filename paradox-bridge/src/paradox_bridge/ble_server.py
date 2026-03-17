"""BLE GATT server for Remote Paradox Pi using BlueZ D-Bus API.

Advertises as "Remote Paradox" with a Nordic UART Service (NUS) for
JSON command/response communication with the Android app.

BLE is always discoverable. Access model:
  PUBLIC (no auth):    status, admin_setup (once), auth
  AUTHENTICATED:       wifi_scan, wifi_set (admin), alarm_*, system_*
  Any untrusted device gets nothing until authenticated.

Reset trust by shorting GPIO17->GND.
"""

import json
import logging
import os
import subprocess
import time
from pathlib import Path
from typing import Optional

logger = logging.getLogger("ble_server")

TRUST_FILE = Path("/opt/paradox-bridge/ble_trust.json")
VERSION_FILE = Path("/opt/paradox-bridge/CURRENT_VERSION")
_CONFIG_PATH = os.environ.get("PARADOX_CONFIG", "/etc/paradox-bridge/config.json")

NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

BLUEZ_SERVICE = "org.bluez"
DBUS_OM_IFACE = "org.freedesktop.DBus.ObjectManager"
DBUS_PROP_IFACE = "org.freedesktop.DBus.Properties"
GATT_MANAGER_IFACE = "org.bluez.GattManager1"
GATT_SERVICE_IFACE = "org.bluez.GattService1"
GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"
GATT_DESC_IFACE = "org.bluez.GattDescriptor1"
LE_ADVERTISING_MANAGER_IFACE = "org.bluez.LEAdvertisingManager1"
LE_ADVERTISEMENT_IFACE = "org.bluez.LEAdvertisement1"
ADAPTER_IFACE = "org.bluez.Adapter1"
DEVICE_IFACE = "org.bluez.Device1"
AGENT_MANAGER_IFACE = "org.bluez.AgentManager1"
AGENT_IFACE = "org.bluez.Agent1"

AGENT_PATH = "/org/bluez/paradox/agent"
AGENT_CAPABILITY = "NoInputNoOutput"

ALLOWED_SERVICE_UUIDS = {
    NUS_SERVICE_UUID,
    "00001800-0000-1000-8000-00805f9b34fb",  # Generic Access
    "00001801-0000-1000-8000-00805f9b34fb",  # Generic Attribute
    "0000180a-0000-1000-8000-00805f9b34fb",  # Device Information
}

REJECTED_AUDIO_UUIDS = {
    "0000110a-0000-1000-8000-00805f9b34fb",  # Audio Source
    "0000110b-0000-1000-8000-00805f9b34fb",  # Audio Sink
    "0000110c-0000-1000-8000-00805f9b34fb",  # A/V Remote Control Target
    "0000110d-0000-1000-8000-00805f9b34fb",  # Advanced Audio Distribution
    "0000110e-0000-1000-8000-00805f9b34fb",  # A/V Remote Control
    "0000111e-0000-1000-8000-00805f9b34fb",  # Handsfree
    "0000111f-0000-1000-8000-00805f9b34fb",  # Handsfree Audio Gateway
}

PUBLIC_COMMANDS = {"status", "admin_setup", "auth"}
ADMIN_ONLY_COMMANDS = {"wifi_set", "system_reboot"}
AUTHENTICATED_COMMANDS = {
    "wifi_scan", "wifi_set",
    "arm_away", "arm_stay", "disarm", "panic", "alarm_status",
    "system_resources", "system_wifi", "system_reboot",
    "bypass", "ble_clients", "event_history", "audit_logs",
    "list_users", "create_invite", "update_role", "delete_user",
    "reset_password",
}


# ── Client tracker ──


_BLE_CLIENTS_FILE = Path("/tmp/ble_clients.json")


class BleClientTracker:
    """Track connected BLE clients and persist to shared file."""

    def __init__(self):
        self._clients: dict[str, dict] = {}

    def _persist(self) -> None:
        try:
            _BLE_CLIENTS_FILE.write_text(json.dumps({
                "clients": list(self._clients.values()),
                "count": len(self._clients),
            }))
        except Exception:
            pass

    def client_connected(self, address: str, name: str = "Unknown") -> None:
        if address in self._clients:
            if name != "Unknown":
                self._clients[address]["name"] = name
            return
        self._clients.clear()
        self._clients[address] = {
            "address": address,
            "name": name,
            "username": None,
            "connected_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        }
        logger.info("BLE client connected: %s (%s)", address, name)
        self._persist()

    def client_disconnected(self, address: str) -> None:
        removed = self._clients.pop(address, None)
        if removed:
            logger.info("BLE client disconnected: %s", address)
        self._persist()

    def set_username(self, address: str, username: str) -> None:
        if address in self._clients:
            self._clients[address]["username"] = username
            self._persist()

    def set_device_name(self, address: str, device: str) -> None:
        if address in self._clients and device:
            self._clients[address]["name"] = device
            self._persist()

    def get_clients(self) -> list[dict]:
        return list(self._clients.values())

    @property
    def count(self) -> int:
        return len(self._clients)


_tracker = BleClientTracker()


def get_tracker() -> BleClientTracker:
    """Read BLE client state from shared file (works cross-process)."""
    try:
        data = json.loads(_BLE_CLIENTS_FILE.read_text())
        _tracker._clients = {c["address"]: c for c in data.get("clients", [])}
    except Exception:
        pass
    return _tracker


# ── Utility functions ──


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
    if TRUST_FILE.exists():
        return True
    # Also check if admin users exist in database (setup via HTTP)
    try:
        from paradox_bridge.config import load_config
        from paradox_bridge.database import Database
        cfg = load_config(_CONFIG_PATH)
        db = Database(cfg.db_path)
        return len(db.list_users()) > 0
    except Exception:
        return False


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
        logger.error("WiFi scan failed: %s", e)
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
        from paradox_bridge.database import Database
        from paradox_bridge.auth import AuthService
        from paradox_bridge.config import load_config
        cfg = load_config(_CONFIG_PATH)
        db = Database(cfg.db_path)
        auth = AuthService(db, cfg)  # db first, config second
        auth.setup_admin(username, password)
        return "ok"
    except Exception as e:
        return f"error: {e}"


def _validate_token(token: str) -> dict | None:
    if not token:
        return None
    try:
        from paradox_bridge.auth import AuthService
        from paradox_bridge.config import load_config
        from paradox_bridge.database import Database
        cfg = load_config(_CONFIG_PATH)
        db = Database(cfg.db_path)
        auth = AuthService(db, cfg)  # db first, config second
        return auth.decode_token(token)
    except Exception as e:
        logger.warning("Token validation failed: %s", e)
        return None


# ── Command handler ──


def handle_command(data: str) -> str:
    """Process a JSON command and return a JSON response."""
    try:
        cmd = json.loads(data)
    except json.JSONDecodeError:
        return json.dumps({"error": "invalid JSON"})

    action = cmd.get("cmd", "")

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
        username = payload.get("sub", "")
        device = cmd.get("device", "")
        for addr in list(_tracker._clients.keys()):
            _tracker.set_username(addr, username)
            if device:
                _tracker.set_device_name(addr, device)
        return json.dumps({
            "result": "authenticated",
            "username": payload.get("sub"),
            "role": payload.get("role", "user"),
        })

    if action in AUTHENTICATED_COMMANDS:
        token = cmd.get("token", "")
        payload = _validate_token(token)
        if payload is None:
            return json.dumps({"error": "authentication required"})

        username = payload.get("sub", "")
        if username:
            for addr in list(_tracker._clients.keys()):
                _tracker.set_username(addr, username)

        if action in ADMIN_ONLY_COMMANDS and payload.get("role") != "admin":
            return json.dumps({"error": "admin privileges required"})

        if action == "wifi_scan":
            return json.dumps({"networks": wifi_scan()})
        if action == "wifi_set":
            if not cmd.get("ssid"):
                return json.dumps({"error": "ssid required"})
            result = apply_wifi(cmd["ssid"], cmd.get("password", ""))
            return json.dumps({"result": result})
        if action in ("arm_away", "arm_stay", "disarm", "panic", "alarm_status"):
            return _handle_alarm_command(action, cmd)
        if action == "system_resources":
            return _proxy_get("/system/resources", token)
        if action == "system_wifi":
            return _proxy_get("/system/wifi", token)
        if action == "system_reboot":
            return _proxy_post("/system/reboot", token)
        if action == "bypass":
            zone_id = cmd.get("zone_id", 0)
            bypass = cmd.get("bypass", True)
            return _proxy_post("/alarm/bypass", token, {"zone_id": zone_id, "bypass": bypass})
        if action == "ble_clients":
            clients = _tracker.get_clients()
            return json.dumps({"clients": clients, "count": _tracker.count})
        if action == "event_history":
            limit = cmd.get("limit", 50)
            return _proxy_get(f"/alarm/history?limit={limit}", token)
        if action == "audit_logs":
            limit = cmd.get("limit", 100)
            return _proxy_get(f"/alarm/logs?limit={limit}", token)
        if action == "list_users":
            return _proxy_get("/auth/users", token)
        if action == "create_invite":
            return _proxy_post("/auth/invite", token)
        if action == "update_role":
            username = cmd.get("username", "")
            role = cmd.get("role", "")
            return _proxy_put(f"/auth/users/{username}/role", token, {"role": role})
        if action == "delete_user":
            username = cmd.get("username", "")
            return _proxy_delete(f"/auth/users/{username}", token)
        if action == "reset_password":
            username = cmd.get("username", "")
            password = cmd.get("password", "")
            return _proxy_put(f"/auth/users/{username}/password", token, {"password": password})

    return json.dumps({"error": f"unknown command: {action}"})


def _proxy_get(path: str, token: str) -> str:
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



def _proxy_put(path: str, token: str, body: dict | None = None) -> str:
    import urllib.request
    try:
        data = json.dumps(body).encode() if body else None
        req = urllib.request.Request(
            f"http://127.0.0.1:8080{path}",
            data=data,
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            method="PUT",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.read().decode()
    except Exception as e:
        return json.dumps({"error": f"proxy failed: {e}"})


def _proxy_delete(path: str, token: str) -> str:
    import urllib.request
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:8080{path}",
            headers={"Authorization": f"Bearer {token}"},
            method="DELETE",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.read().decode()
    except Exception as e:
        return json.dumps({"error": f"proxy failed: {e}"})

def _handle_alarm_command(action: str, cmd: dict) -> str:
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


# ── D-Bus GATT Server (only runs on the Pi with BlueZ) ──


def _find_adapter(bus):
    """Find the first BLE adapter (hci0) object path."""
    import dbus
    om = dbus.Interface(bus.get_object(BLUEZ_SERVICE, "/"), DBUS_OM_IFACE)
    objects = om.GetManagedObjects()
    for path, ifaces in objects.items():
        if GATT_MANAGER_IFACE in ifaces:
            return path
    return None


class Application(object):
    """BlueZ GATT Application registered with GattManager1."""

    DBUS_PATH = "/org/bluez/paradox"

    def __init__(self, bus):
        import dbus
        import dbus.service
        self._bus = bus
        self._path = self.DBUS_PATH
        self._services = []
        self._dbus_obj = _ApplicationDBus(bus, self._path, self)

    def add_service(self, service):
        self._services.append(service)

    def get_path(self):
        return self._path

    def get_managed_objects(self):
        response = {}
        for service in self._services:
            response[service.get_path()] = service.get_properties()
            for chrc in service.get_characteristics():
                response[chrc.get_path()] = chrc.get_properties()
        return response


class _ApplicationDBus(object):
    """D-Bus ObjectManager wrapper for Application."""

    def __init__(self, bus, path, app):
        import dbus
        import dbus.service

        class AppObj(dbus.service.Object):
            def __init__(self, _bus, _path, _app):
                self._app = _app
                dbus.service.Object.__init__(self, _bus, _path)

            @dbus.service.method(DBUS_OM_IFACE, out_signature="a{oa{sa{sv}}}")
            def GetManagedObjects(self):
                return self._app.get_managed_objects()

        self._obj = AppObj(bus, path, app)


class Service(object):
    """BlueZ GATT Service."""

    def __init__(self, bus, index, uuid, primary):
        import dbus
        import dbus.service
        self._bus = bus
        self._path = Application.DBUS_PATH + "/service" + str(index)
        self._uuid = uuid
        self._primary = primary
        self._characteristics = []

        class SvcObj(dbus.service.Object):
            def __init__(self_inner, _bus, _path):
                dbus.service.Object.__init__(self_inner, _bus, _path)

            @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
            def GetAll(self_inner, iface):
                if iface != GATT_SERVICE_IFACE:
                    raise dbus.exceptions.DBusException(
                        "org.freedesktop.DBus.Error.InvalidArgs")
                return self.get_properties()[GATT_SERVICE_IFACE]

        self._dbus_obj = SvcObj(bus, self._path)

    def get_properties(self):
        import dbus
        return {
            GATT_SERVICE_IFACE: {
                "UUID": self._uuid,
                "Primary": dbus.Boolean(self._primary),
                "Characteristics": dbus.Array(
                    [dbus.ObjectPath(c.get_path()) for c in self._characteristics],
                    signature="o",
                ),
            }
        }

    def get_path(self):
        return self._path

    def add_characteristic(self, chrc):
        self._characteristics.append(chrc)

    def get_characteristics(self):
        return self._characteristics


class Characteristic(object):
    """Base GATT Characteristic."""

    def __init__(self, bus, index, uuid, flags, service):
        import dbus
        import dbus.service
        self._bus = bus
        self._path = service.get_path() + "/char" + str(index)
        self._uuid = uuid
        self._flags = flags
        self._service = service
        self._value = []
        self._notifying = False
        self._notify_cb = None
        parent = self

        class ChrcObj(dbus.service.Object):
            def __init__(self_inner, _bus, _path):
                dbus.service.Object.__init__(self_inner, _bus, _path)

            @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
            def GetAll(self_inner, iface):
                if iface != GATT_CHRC_IFACE:
                    raise dbus.exceptions.DBusException(
                        "org.freedesktop.DBus.Error.InvalidArgs")
                return parent.get_properties()[GATT_CHRC_IFACE]

            @dbus.service.method(GATT_CHRC_IFACE, in_signature="a{sv}", out_signature="ay")
            def ReadValue(self_inner, options):
                return parent.read_value(options)

            @dbus.service.method(GATT_CHRC_IFACE, in_signature="aya{sv}")
            def WriteValue(self_inner, value, options):
                parent.write_value(value, options)

            @dbus.service.method(GATT_CHRC_IFACE)
            def StartNotify(self_inner):
                parent.start_notify()

            @dbus.service.method(GATT_CHRC_IFACE)
            def StopNotify(self_inner):
                parent.stop_notify()

            @dbus.service.signal(DBUS_PROP_IFACE, signature="sa{sv}as")
            def PropertiesChanged(self_inner, iface, changed, invalidated):
                pass

        self._dbus_obj = ChrcObj(bus, self._path)

    def get_properties(self):
        import dbus
        return {
            GATT_CHRC_IFACE: {
                "Service": dbus.ObjectPath(self._service.get_path()),
                "UUID": self._uuid,
                "Flags": dbus.Array(self._flags, signature="s"),
            }
        }

    def get_path(self):
        return self._path

    def read_value(self, options):
        return self._value

    def write_value(self, value, options):
        # Track connected client from GATT write
        device = str(options.get("device", ""))
        if "/dev_" in device:
            addr = device.split("/")[-1].replace("dev_", "").replace("_", ":")
            _tracker.client_connected(addr)
        self._value = value

    def start_notify(self):
        self._notifying = True

    def stop_notify(self):
        self._notifying = False

    def send_notification(self, data: bytes):
        if not self._notifying:
            return
        import dbus
        self._value = dbus.Array([dbus.Byte(b) for b in data], signature="y")
        self._dbus_obj.PropertiesChanged(
            GATT_CHRC_IFACE, {"Value": self._value}, [],
        )


class NusRxCharacteristic(Characteristic):
    """NUS RX — app writes commands here, Pi processes and responds via TX."""

    def __init__(self, bus, service, tx_chrc):
        super().__init__(bus, 0, NUS_RX_UUID, ["write", "write-without-response"], service)
        self._tx = tx_chrc

    def write_value(self, value, options):
        # Track connected client from GATT write
        device = str(options.get("device", ""))
        if "/dev_" in device:
            addr = device.split("/")[-1].replace("dev_", "").replace("_", ":")
            _tracker.client_connected(addr)
        data = bytes(value).decode("utf-8", errors="replace")
        logger.info("BLE RX: %s", data[:200])
        response = handle_command(data)
        logger.info("BLE TX: %s", response[:200])
        self._send_chunked(response.encode("utf-8"))

    def _send_chunked(self, data: bytes, mtu: int = 182):
        """Send response in MTU-sized chunks via TX notifications."""
        for i in range(0, len(data), mtu):
            chunk = data[i:i + mtu]
            self._tx.send_notification(chunk)


class NusTxCharacteristic(Characteristic):
    """NUS TX — Pi sends responses here via notifications."""

    def __init__(self, bus, service):
        super().__init__(bus, 1, NUS_TX_UUID, ["notify"], service)


class NusService(Service):
    """Nordic UART Service."""

    def __init__(self, bus):
        super().__init__(bus, 0, NUS_SERVICE_UUID, True)
        self.tx = NusTxCharacteristic(bus, self)
        self.rx = NusRxCharacteristic(bus, self, self.tx)
        self.add_characteristic(self.tx)
        self.add_characteristic(self.rx)


class Advertisement(object):
    """BLE LE Advertisement."""

    def __init__(self, bus, index):
        import dbus
        import dbus.service
        self._path = "/org/bluez/paradox/adv" + str(index)
        self._bus = bus
        parent = self

        class AdvObj(dbus.service.Object):
            def __init__(self_inner, _bus, _path):
                dbus.service.Object.__init__(self_inner, _bus, _path)

            @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
            def GetAll(self_inner, iface):
                if iface != LE_ADVERTISEMENT_IFACE:
                    raise dbus.exceptions.DBusException(
                        "org.freedesktop.DBus.Error.InvalidArgs")
                return parent.get_properties()[LE_ADVERTISEMENT_IFACE]

            @dbus.service.method(LE_ADVERTISEMENT_IFACE, in_signature="", out_signature="")
            def Release(self_inner):
                logger.info("Advertisement released")

        self._dbus_obj = AdvObj(bus, self._path)

    def get_path(self):
        return self._path

    def get_properties(self):
        import dbus
        return {
            LE_ADVERTISEMENT_IFACE: {
                "Type": "peripheral",
                "ServiceUUIDs": dbus.Array([NUS_SERVICE_UUID], signature="s"),
                "LocalName": dbus.String("Remote Paradox"),
            }
        }


def _re_enable_advertising():
    """Re-enable LE advertising via hcitool after a disconnect (BlueZ workaround)."""
    try:
        subprocess.run(
            ["sudo", "hcitool", "-i", "hci0", "cmd", "0x08", "0x000a", "01"],
            capture_output=True, timeout=5,
        )
        logger.info("LE advertising re-enabled after disconnect")
    except Exception as e:
        logger.warning("Failed to re-enable advertising: %s", e)


def _setup_device_monitoring(bus):
    """Monitor BlueZ Device1 property changes for connection tracking."""
    import dbus

    def _properties_changed(iface, changed, invalidated, path=None):
        if iface != DEVICE_IFACE:
            return
        if "Connected" not in changed:
            return

        connected = bool(changed["Connected"])
        try:
            device_obj = bus.get_object(BLUEZ_SERVICE, path)
            props = dbus.Interface(device_obj, DBUS_PROP_IFACE)
            address = str(props.Get(DEVICE_IFACE, "Address"))
            try:
                name = str(props.Get(DEVICE_IFACE, "Name"))
            except Exception:
                name = "Unknown"
        except Exception:
            address = str(path).split("/")[-1].replace("_", ":") if path else "unknown"
            name = "Unknown"

        if connected:
            _tracker.client_connected(address, name)
        else:
            _tracker.client_disconnected(address)
            _re_enable_advertising()

    bus.add_signal_receiver(
        _properties_changed,
        dbus_interface=DBUS_PROP_IFACE,
        signal_name="PropertiesChanged",
        path_keyword="path",
    )
    logger.info("BLE device connection monitoring started")


def register_pairing_agent(bus):
    """Register a NoInputNoOutput pairing agent so devices can pair via Just Works."""
    import dbus
    import dbus.service

    class PairingAgent(dbus.service.Object):
        @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
        def Release(self):
            logger.info("Pairing agent released")

        @dbus.service.method(AGENT_IFACE, in_signature="os", out_signature="")
        def AuthorizeService(self, device, uuid):
            uuid_lower = str(uuid).lower()
            if uuid_lower in REJECTED_AUDIO_UUIDS:
                logger.info("Rejected audio service %s for %s", uuid, device)
                raise dbus.exceptions.DBusException(
                    "org.bluez.Error.Rejected",
                    f"Audio service {uuid} not supported",
                )
            logger.info("Authorized service %s for %s", uuid, device)

        @dbus.service.method(AGENT_IFACE, in_signature="o", out_signature="")
        def RequestAuthorization(self, device):
            logger.info("Pairing authorization granted for %s", device)

        @dbus.service.method(AGENT_IFACE, in_signature="", out_signature="")
        def Cancel(self):
            logger.info("Pairing cancelled")

    agent = PairingAgent(bus, AGENT_PATH)
    agent_manager = dbus.Interface(
        bus.get_object(BLUEZ_SERVICE, "/org/bluez"), AGENT_MANAGER_IFACE,
    )
    agent_manager.RegisterAgent(AGENT_PATH, AGENT_CAPABILITY)
    agent_manager.RequestDefaultAgent(AGENT_PATH)
    logger.info("Pairing agent registered (%s)", AGENT_CAPABILITY)
    return agent


def run_ble_server():
    """Start BLE GATT server using BlueZ D-Bus API."""
    import dbus
    import dbus.mainloop.glib
    from gi.repository import GLib

    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
    logger.info("Starting Remote Paradox BLE server...")

    subprocess.run(["sudo", "hciconfig", "hci0", "up"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "le", "on"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "connectable", "on"], capture_output=True)
    subprocess.run(["sudo", "btmgmt", "bondable", "off"], capture_output=True)
    # Set longer BLE supervision timeout (600 * 10ms = 6s) to prevent premature disconnects
    try:
        with open("/sys/kernel/debug/bluetooth/hci0/supervision_timeout", "w") as f: f.write("600")
        with open("/sys/kernel/debug/bluetooth/hci0/conn_min_interval", "w") as f: f.write("6")
        with open("/sys/kernel/debug/bluetooth/hci0/conn_max_interval", "w") as f: f.write("24")
    except Exception as e:
        logger.warning("Could not set BLE connection params: %s", e)

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    adapter_path = _find_adapter(bus)
    if not adapter_path:
        logger.error("No BLE adapter found — cannot start GATT server")
        logger.info("Falling back to discoverable-only mode")
        mainloop = GLib.MainLoop()
        GLib.timeout_add_seconds(10, check_gpio_reset)
        mainloop.run()
        return

    logger.info("Using adapter: %s", adapter_path)

    adapter_props = dbus.Interface(
        bus.get_object(BLUEZ_SERVICE, adapter_path), DBUS_PROP_IFACE,
    )
    adapter_props.Set(ADAPTER_IFACE, "Powered", dbus.Boolean(True))

    app = Application(bus)
    nus = NusService(bus)
    app.add_service(nus)

    gatt_manager = dbus.Interface(
        bus.get_object(BLUEZ_SERVICE, adapter_path), GATT_MANAGER_IFACE,
    )

    adv = Advertisement(bus, 0)
    adv_manager = dbus.Interface(
        bus.get_object(BLUEZ_SERVICE, adapter_path), LE_ADVERTISING_MANAGER_IFACE,
    )

    _setup_device_monitoring(bus)

    def _on_gatt_registered():
        logger.info("GATT application registered successfully")

    def _on_gatt_error(error):
        logger.error("Failed to register GATT application: %s", error)

    def _on_adv_registered():
        logger.info("LE advertisement registered successfully")
        # Disable pairing after registration — BlueZ re-enables these during GATT setup
        subprocess.run(["sudo", "btmgmt", "sc", "off"], capture_output=True)
        subprocess.run(["sudo", "btmgmt", "bondable", "off"], capture_output=True)
        logger.info("Disabled secure-conn and bondable to suppress pairing popups")

    def _on_adv_error(error):
        logger.error("Failed to register LE advertisement: %s", error)

    gatt_manager.RegisterApplication(
        app.get_path(), {},
        reply_handler=_on_gatt_registered,
        error_handler=_on_gatt_error,
    )

    adv_manager.RegisterAdvertisement(
        adv.get_path(), {},
        reply_handler=_on_adv_registered,
        error_handler=_on_adv_error,
    )

    logger.info("Trust: %s", "established" if is_trusted() else "first-come-first-served")
    logger.info("IP: %s, SSID: %s, Version: %s", get_ip(), get_current_ssid(), get_version())
    logger.info("BLE GATT server running — waiting for connections...")

    def _gpio_check():
        check_gpio_reset()
        return True

    GLib.timeout_add_seconds(10, _gpio_check)

    mainloop = GLib.MainLoop()
    try:
        mainloop.run()
    except KeyboardInterrupt:
        logger.info("BLE server stopped by user")


def main():
    run_ble_server()


if __name__ == "__main__":
    main()
