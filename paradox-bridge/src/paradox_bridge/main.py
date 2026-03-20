"""FastAPI application — routes, dependency injection, lifespan."""

import asyncio
import logging
import os
import random
import socket
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from paradox_bridge.alarm import AlarmService
from paradox_bridge.audit import AuditService
from paradox_bridge.auth import AuthService
from paradox_bridge.config import AppConfig, load_config, save_config_field
from paradox_bridge.database import Database
from paradox_bridge.models import (
    ActionResult,
    AlarmStatusResponse,
    ArmRequest,
    AuditEntry,
    AuditLogResponse,
    BleClientInfo,
    BleClientsResponse,
    BypassRequest,
    ErrorResponse,
    EventHistoryResponse,
    EventResponse,
    HealthResponse,
    InviteResponse,
    LoginRequest,
    LoginResponse,
    PanicRequest,
    PartitionResponse,
    PasswordResetRequest,
    RegisterRequest,
    RegisterResponse,
    SystemResourcesResponse,
    UserInfo,
    UserListResponse,
    PaiStatusResponse,
    PcPasswordUpdateRequest,
    RoleUpdateRequest,
    WifiInfoResponse,
    ZoneResponse,
    ZoneToggleRequest,
)
from paradox_bridge.dashboard import router as dashboard_router
from paradox_bridge.tls import generate_self_signed_cert, get_cert_fingerprint
from paradox_bridge.ws import ConnectionManager

logger = logging.getLogger(__name__)

_CONFIG_PATH = os.environ.get("PARADOX_CONFIG", "/etc/paradox-bridge/config.json")

_db: Database | None = None
_config: AppConfig | None = None
_auth: AuthService | None = None
_audit: AuditService | None = None
_alarm: AlarmService | None = None
_ws_manager: ConnectionManager | None = None
_cert_fingerprint: str = ""
_demo_trigger_task: asyncio.Task | None = None
_reconnect_task: asyncio.Task | None = None
_ws_heartbeat_task: asyncio.Task | None = None
_event_purge_task: asyncio.Task | None = None

_CONNECT_RETRY_DELAY = 30  # seconds between reconnection attempts
_CONNECT_MAX_RETRIES = 3   # limit retries per cycle to avoid panel lockout
_RECONNECT_CHECK_INTERVAL = 15  # seconds between connection health checks


def get_db() -> Database:
    assert _db is not None
    return _db


def get_auth() -> AuthService:
    assert _auth is not None
    return _auth


def get_audit() -> AuditService:
    assert _audit is not None
    return _audit


def get_alarm() -> AlarmService:
    assert _alarm is not None
    return _alarm


def get_ws() -> ConnectionManager:
    assert _ws_manager is not None
    return _ws_manager


def get_config() -> AppConfig:
    assert _config is not None
    return _config


_bearer = HTTPBearer()


def get_current_user(
    creds: Annotated[HTTPAuthorizationCredentials, Depends(_bearer)],
    auth: Annotated[AuthService, Depends(get_auth)],
) -> dict:
    try:
        return auth.decode_token(creds.credentials)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc))


def require_admin(user: Annotated[dict, Depends(get_current_user)]) -> dict:
    if user.get("role") != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin required")
    return user


def get_device_name(request: Request) -> str | None:
    return request.headers.get("x-device-name") or None


def _get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def init_services(config_path: str | None = None) -> None:
    """Initialise all services. Called by lifespan or tests."""
    global _db, _config, _auth, _audit, _alarm, _ws_manager, _cert_fingerprint
    path = config_path or _CONFIG_PATH
    _config = load_config(path)

    if _config.tls_enabled and not Path(_config.tls_cert_path).exists():
        local_ip = _get_local_ip()
        ip_sans = [local_ip] if local_ip != "127.0.0.1" else []
        generate_self_signed_cert(
            _config.tls_cert_path, _config.tls_key_path,
            hostname="remote-paradox", ip_addresses=ip_sans,
        )
        logger.info("Generated self-signed TLS cert at %s", _config.tls_cert_path)

    if _config.tls_enabled and Path(_config.tls_cert_path).exists():
        _cert_fingerprint = get_cert_fingerprint(_config.tls_cert_path)

    _db = Database(_config.db_path)
    _db.init()
    _auth = AuthService(db=_db, config=_config)
    _audit = AuditService(db=_db)
    _alarm = AlarmService(
        serial_port=_config.serial_port,
        baud=_config.serial_baud,
        pc_password=_config.panel_pc_password,
        demo_mode=_config.demo_mode,
        db=_db,
    )
    _ws_manager = ConnectionManager()


def shutdown_services() -> None:
    global _db, _alarm
    if _db:
        _db.close()
        _db = None
    _alarm = None


def _on_alarm_status_changed() -> None:
    """Called (synchronously) by AlarmService when PAI status changes.
    Schedules an async broadcast to all WebSocket clients."""
    if not _ws_manager or _ws_manager.active_count == 0:
        return
    try:
        asyncio.ensure_future(_broadcast_status())
    except RuntimeError:
        pass


async def _broadcast_status() -> None:
    """Build a full status snapshot and push it to all WebSocket clients."""
    if not _alarm or not _ws_manager:
        return
    try:
        resp = _build_status_response(_alarm)
        events = _alarm.get_zone_history(limit=20)
        payload = {
            "type": "status",
            "partitions": [p.model_dump() for p in resp.partitions],
            "connected": resp.connected,
            "events": events,
        }
        await _ws_manager.broadcast(payload)
    except Exception:
        logger.debug("WS broadcast failed", exc_info=True)


_WS_HEARTBEAT_INTERVAL = 5  # seconds — fallback push even without PAI pubsub events


async def _ws_heartbeat_loop() -> None:
    """Periodically push status to WS clients as a fallback heartbeat.
    For demo mode this is the primary push mechanism."""
    while True:
        await asyncio.sleep(_WS_HEARTBEAT_INTERVAL)
        if _ws_manager and _ws_manager.active_count > 0:
            await _broadcast_status()


_EVENT_PURGE_INTERVAL = 86400  # 24 hours


async def _event_purge_loop() -> None:
    """Purge alarm events older than 90 days, once per day."""
    while True:
        await asyncio.sleep(_EVENT_PURGE_INTERVAL)
        if _db is not None:
            try:
                purged = _db.purge_old_events(days=90)
                if purged:
                    logger.info("Purged %d alarm events older than 90 days", purged)
            except Exception:
                logger.exception("Event purge failed")


async def _demo_zone_trigger_loop() -> None:
    """In demo mode, randomly trigger a zone every ~30s to simulate activity."""
    assert _alarm is not None
    zone_ids = [z["id"] for z in _alarm.list_all_zones()]
    while True:
        await asyncio.sleep(30)
        zid = random.choice(zone_ids)
        try:
            _alarm.set_zone_open(zid, True)
            logger.info("Demo trigger: zone %d opened", zid)
            await asyncio.sleep(5)
            _alarm.set_zone_open(zid, False)
            logger.info("Demo trigger: zone %d closed", zid)
        except Exception:
            pass


async def _connect_alarm() -> None:
    """Connect to the real alarm panel, then monitor and auto-reconnect on drop."""
    if _alarm is None or _alarm.demo_mode:
        return

    async def _try_connect() -> bool:
        for attempt in range(1, _CONNECT_MAX_RETRIES + 1):
            try:
                await _alarm.disconnect()
                await _alarm.connect()
                logger.info("Connected to alarm panel on attempt %d", attempt)
                return True
            except Exception as exc:
                logger.warning("Alarm connect attempt %d failed: %s", attempt, exc)
                if attempt < _CONNECT_MAX_RETRIES:
                    await asyncio.sleep(_CONNECT_RETRY_DELAY)
        return False

    await _try_connect()

    while True:
        await asyncio.sleep(_RECONNECT_CHECK_INTERVAL)
        if not _alarm.is_connected:
            logger.warning("Connection lost — attempting reconnect...")
            await _try_connect()


async def _disconnect_alarm() -> None:
    """Disconnect from the real alarm panel."""
    if _alarm is None or _alarm.demo_mode:
        return
    await _alarm.disconnect()
    logger.info("Disconnected from alarm panel")


@asynccontextmanager
async def lifespan(application: FastAPI):
    global _demo_trigger_task, _reconnect_task, _ws_heartbeat_task, _event_purge_task
    init_services()
    admin_user = os.environ.get("PARADOX_ADMIN_USER", "admin")
    admin_pass = os.environ.get("PARADOX_ADMIN_PASS")
    if admin_pass and _auth:
        _auth.setup_admin(admin_user, admin_pass)
    if _alarm:
        _alarm.set_status_change_callback(_on_alarm_status_changed)
    _ws_heartbeat_task = asyncio.create_task(_ws_heartbeat_loop())
    _event_purge_task = asyncio.create_task(_event_purge_loop())
    if _alarm and _alarm.demo_mode:
        _demo_trigger_task = asyncio.create_task(_demo_zone_trigger_loop())
    elif _alarm and not _alarm.demo_mode:
        _reconnect_task = asyncio.create_task(_connect_alarm())
    yield
    if _ws_heartbeat_task:
        _ws_heartbeat_task.cancel()
        _ws_heartbeat_task = None
    if _event_purge_task:
        _event_purge_task.cancel()
        _event_purge_task = None
    if _demo_trigger_task:
        _demo_trigger_task.cancel()
        _demo_trigger_task = None
    if _reconnect_task:
        _reconnect_task.cancel()
        _reconnect_task = None
    if _alarm and not _alarm.demo_mode:
        await _disconnect_alarm()
    shutdown_services()


app = FastAPI(title="Paradox Bridge", version="0.5.1", lifespan=lifespan)


def _maybe_add_cors() -> None:
    """Add CORS middleware in demo mode only (local dev testing).
    In production, nginx handles CORS."""
    cfg_path = os.environ.get("PARADOX_CONFIG", _CONFIG_PATH)
    try:
        import json
        from pathlib import Path
        p = Path(cfg_path)
        if p.exists():
            data = json.loads(p.read_text())
            if data.get("demo_mode"):
                from fastapi.middleware.cors import CORSMiddleware
                app.add_middleware(
                    CORSMiddleware,
                    allow_origins=["*"],
                    allow_methods=["*"],
                    allow_headers=["*"],
                )
    except Exception:
        pass


_maybe_add_cors()

app.include_router(dashboard_router)


# ── Auth routes ──

@app.post("/auth/login", response_model=LoginResponse)
def login(req: LoginRequest, auth: Annotated[AuthService, Depends(get_auth)]):
    try:
        token = auth.login(req.username, req.password)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")
    user = auth._db.get_user(req.username)
    return LoginResponse(token=token, username=req.username, role=user["role"])


@app.post("/auth/register", response_model=RegisterResponse)
def register(
    req: RegisterRequest,
    auth: Annotated[AuthService, Depends(get_auth)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    try:
        token = auth.register(
            invite_code=req.invite_code, username=req.username, password=req.password
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    audit.record(req.username, "register", "Registered via invite code")
    return RegisterResponse(token=token, username=req.username)


@app.post("/auth/invite", response_model=InviteResponse)
def create_invite(
    user: Annotated[dict, Depends(require_admin)],
    auth: Annotated[AuthService, Depends(get_auth)],
    cfg: Annotated[AppConfig, Depends(get_config)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    code = auth.generate_invite(user["sub"])
    invite_host = cfg.public_host or _get_local_ip()
    invite_port = cfg.public_port or cfg.api_port
    uri = auth.build_invite_uri(
        code, host=invite_host, port=invite_port, fingerprint=_cert_fingerprint,
    )
    audit.record(user["sub"], "create_invite", f"code={code}")
    qr_data = _generate_qr_data_uri(uri)
    return InviteResponse(
        code=code, uri=uri,
        expires_in_seconds=cfg.invite_expiry_seconds,
        qr_data_uri=qr_data,
    )


def _generate_qr_data_uri(data: str) -> str:
    try:
        import base64
        import io
        import qrcode
        qr = qrcode.QRCode(
            version=None, error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=8, border=2,
        )
        qr.add_data(data)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        b64 = base64.b64encode(buf.getvalue()).decode("ascii")
        return f"data:image/png;base64,{b64}"
    except ImportError:
        return ""


@app.get("/auth/users", response_model=UserListResponse)
def list_users(
    _admin: Annotated[dict, Depends(require_admin)],
    db: Annotated[Database, Depends(get_db)],
):
    users = db.list_users()
    return UserListResponse(
        users=[UserInfo(username=u["username"], role=u["role"], created_at=u["created_at"]) for u in users]
    )


@app.put("/auth/users/{username}/role", response_model=ActionResult)
def update_user_role(
    username: str,
    req: RoleUpdateRequest,
    admin: Annotated[dict, Depends(require_admin)],
    db: Annotated[Database, Depends(get_db)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if username == admin["sub"]:
        raise HTTPException(status_code=400, detail="Cannot change your own role")
    try:
        db.update_user_role(username, req.role)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    audit.record(admin["sub"], "update_role", f"{username} -> {req.role}")
    return ActionResult(success=True, action="update_role", message=f"User '{username}' is now {req.role}")


@app.delete("/auth/users/{username}", response_model=ActionResult)
def delete_user(
    username: str,
    admin: Annotated[dict, Depends(require_admin)],
    db: Annotated[Database, Depends(get_db)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if username == admin["sub"]:
        raise HTTPException(status_code=400, detail="Cannot delete yourself")
    try:
        db.delete_user(username)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    audit.record(admin["sub"], "delete_user", f"deleted {username}")
    return ActionResult(success=True, action="delete_user", message=f"User '{username}' deleted")


@app.put("/auth/users/{username}/password", response_model=ActionResult)
def reset_user_password(
    username: str,
    req: PasswordResetRequest,
    admin: Annotated[dict, Depends(require_admin)],
    auth: Annotated[AuthService, Depends(get_auth)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if username == admin["sub"]:
        raise HTTPException(status_code=400, detail="Cannot reset your own password")
    try:
        auth.reset_password(admin["sub"], username, req.password)
    except (ValueError, PermissionError) as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    audit.record(admin["sub"], "reset_password", f"Reset password for {username}")
    return ActionResult(success=True, action="reset_password", message=f"Password reset for '{username}'")


# ── System update routes ──

import json
import subprocess

UPDATE_STATUS_FILE = Path("/opt/paradox-bridge/update_status.json")
VERSION_FILE = Path("/opt/paradox-bridge/CURRENT_VERSION")
APPLY_SCRIPT = Path("/opt/paradox-bridge/scripts/apply_update.sh")


@app.get("/system/version")
def system_version():
    ver = VERSION_FILE.read_text().strip() if VERSION_FILE.exists() else "0.0.0"
    return {"version": ver}


@app.get("/system/update-status")
def update_status(_admin: Annotated[dict, Depends(require_admin)]):
    if not UPDATE_STATUS_FILE.exists():
        return {"pending": False, "current_version": VERSION_FILE.read_text().strip() if VERSION_FILE.exists() else "0.0.0"}
    try:
        return json.loads(UPDATE_STATUS_FILE.read_text())
    except Exception:
        return {"pending": False}


@app.post("/system/check-update")
def check_update(_admin: Annotated[dict, Depends(require_admin)]):
    try:
        result = subprocess.run(
            ["python3", "/opt/paradox-bridge/scripts/updater.py"],
            capture_output=True, text=True, timeout=60,
        )
        return {"success": True, "output": result.stdout + result.stderr}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/system/apply-update")
def apply_update(
    admin: Annotated[dict, Depends(require_admin)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not UPDATE_STATUS_FILE.exists():
        raise HTTPException(status_code=404, detail="No pending update")
    try:
        data = json.loads(UPDATE_STATUS_FILE.read_text())
    except Exception:
        raise HTTPException(status_code=500, detail="Cannot read update status")
    if not data.get("pending"):
        raise HTTPException(status_code=400, detail="No pending update")
    audit.record(admin["sub"], "apply_update", f"Applying {data.get('new_version', '?')}")
    try:
        subprocess.Popen(
            ["sudo", str(APPLY_SCRIPT)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        return {"success": True, "message": f"Applying update to {data.get('new_version')}. Service will restart."}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


import shutil


def _read_cpu_percent() -> float:
    """Read aggregate CPU usage from /proc/stat (two samples 0.5s apart)."""
    import time

    def _read_idle_total():
        with open("/proc/stat") as f:
            fields = f.readline().split()[1:]
        vals = [int(x) for x in fields]
        idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
        return idle, sum(vals)

    try:
        idle1, total1 = _read_idle_total()
        time.sleep(0.5)
        idle2, total2 = _read_idle_total()
        delta_total = total2 - total1
        delta_idle = idle2 - idle1
        if delta_total == 0:
            return 0.0
        return round((1 - delta_idle / delta_total) * 100, 1)
    except Exception:
        return 0.0


def _read_memory() -> tuple[int, int, float]:
    """Return (used_mb, total_mb, percent) from /proc/meminfo."""
    try:
        info: dict[str, int] = {}
        with open("/proc/meminfo") as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 2:
                    info[parts[0].rstrip(":")] = int(parts[1])
        total_kb = info.get("MemTotal", 0)
        avail_kb = info.get("MemAvailable", info.get("MemFree", 0))
        used_kb = total_kb - avail_kb
        total_mb = total_kb // 1024
        used_mb = used_kb // 1024
        pct = round(used_kb / total_kb * 100, 1) if total_kb else 0.0
        return used_mb, total_mb, pct
    except Exception:
        return 0, 0, 0.0


def _read_uptime() -> int:
    try:
        with open("/proc/uptime") as f:
            return int(float(f.read().split()[0]))
    except Exception:
        return 0


@app.get("/system/resources", response_model=SystemResourcesResponse)
def system_resources(_admin: Annotated[dict, Depends(require_admin)]):
    cpu = _read_cpu_percent()
    mem_used, mem_total, mem_pct = _read_memory()
    try:
        usage = shutil.disk_usage("/")
        disk_used = round(usage.used / (1024 ** 3), 2)
        disk_total = round(usage.total / (1024 ** 3), 2)
        disk_pct = round(usage.used / usage.total * 100, 1) if usage.total else 0.0
    except Exception:
        disk_used, disk_total, disk_pct = 0.0, 0.0, 0.0
    return SystemResourcesResponse(
        cpu_percent=cpu,
        memory_used_mb=mem_used,
        memory_total_mb=mem_total,
        memory_percent=mem_pct,
        disk_used_gb=disk_used,
        disk_total_gb=disk_total,
        disk_percent=disk_pct,
        uptime_seconds=_read_uptime(),
    )


@app.get("/system/wifi", response_model=WifiInfoResponse)
def system_wifi(_admin: Annotated[dict, Depends(require_admin)]):
    ssid = ""
    ip_addr = ""
    signal_dbm = None
    signal_pct = None
    try:
        r = subprocess.run(["iwgetid", "-r"], capture_output=True, text=True, timeout=5)
        ssid = r.stdout.strip()
    except Exception:
        pass
    try:
        r = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=5)
        ip_addr = r.stdout.strip().split()[0] if r.stdout.strip() else ""
    except Exception:
        pass
    try:
        r = subprocess.run(["iwconfig", "wlan0"], capture_output=True, text=True, timeout=5)
        for line in r.stdout.split("\n"):
            if "Signal level" in line:
                import re
                m = re.search(r"Signal level[=:](-?\d+)", line)
                if m:
                    signal_dbm = int(m.group(1))
                    signal_pct = max(0, min(100, 2 * (signal_dbm + 100)))
    except Exception:
        pass
    return WifiInfoResponse(
        ssid=ssid, ip_address=ip_addr,
        signal_dbm=signal_dbm, signal_percent=signal_pct,
    )


@app.post("/system/reboot", response_model=ActionResult)
def system_reboot(
    admin: Annotated[dict, Depends(require_admin)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    audit.record(admin["sub"], "reboot", "Admin initiated Pi reboot")
    try:
        subprocess.Popen(
            ["sudo", "/sbin/reboot"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        return ActionResult(success=True, action="reboot", message="Pi is rebooting...")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/system/pai-stop", response_model=ActionResult)
async def system_pai_stop(
    admin: Annotated[dict, Depends(require_admin)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if alarm.demo_mode:
        raise HTTPException(status_code=400, detail="Not available in demo mode")
    if not alarm.is_connected:
        return ActionResult(success=True, action="pai_stop", message="PAI already disconnected")
    await alarm.disconnect()
    audit.record(admin["sub"], "pai_stop", "Admin disconnected PAI from panel")
    return ActionResult(success=True, action="pai_stop", message="PAI disconnected — panel keypad is free")


@app.post("/system/pai-start", response_model=ActionResult)
async def system_pai_start(
    admin: Annotated[dict, Depends(require_admin)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if alarm.demo_mode:
        raise HTTPException(status_code=400, detail="Not available in demo mode")
    if alarm.is_connected:
        return ActionResult(success=True, action="pai_start", message="PAI already connected")
    try:
        await alarm._connect_pai()
        audit.record(admin["sub"], "pai_start", "Admin reconnected PAI to panel")
        return ActionResult(success=True, action="pai_start", message="PAI reconnected to panel")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to reconnect: {e}")


@app.get("/system/pai-status", response_model=PaiStatusResponse)
def system_pai_status(
    _admin: Annotated[dict, Depends(require_admin)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
):
    pw = alarm._pc_password or ""
    masked = pw[:1] + "*" * (len(pw) - 1) if len(pw) > 1 else "****"
    return PaiStatusResponse(
        connected=alarm.is_connected,
        pc_password_masked=masked,
        serial_port=alarm._serial_port,
        baud=alarm._baud,
    )


@app.post("/system/pai-password", response_model=ActionResult)
async def system_pai_password(
    body: PcPasswordUpdateRequest,
    admin: Annotated[dict, Depends(require_admin)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if alarm.demo_mode:
        raise HTTPException(status_code=400, detail="Not available in demo mode")
    if alarm.is_connected:
        raise HTTPException(
            status_code=409,
            detail="Stop PAI first before changing the PC password",
        )
    new_pw = body.pc_password.strip()
    if not new_pw:
        raise HTTPException(status_code=400, detail="Password cannot be empty")
    alarm._pc_password = new_pw
    save_config_field(_config.config_path, "panel_pc_password", new_pw)
    audit.record(admin["sub"], "pai_password", "Admin changed panel PC password")
    return ActionResult(
        success=True,
        action="pai_password",
        message="PC password updated. Start PAI to connect with the new password.",
    )


@app.get("/system/ble-clients", response_model=BleClientsResponse)
def system_ble_clients(_admin: Annotated[dict, Depends(require_admin)]):
    try:
        from paradox_bridge.ble_server import get_tracker
        tracker = get_tracker()
        clients = tracker.get_clients()
        return BleClientsResponse(
            clients=[BleClientInfo(**c) for c in clients],
            count=tracker.count,
        )
    except ImportError:
        return BleClientsResponse(clients=[], count=0)


# ── Alarm routes ──

def _build_status_response(alarm: AlarmService) -> AlarmStatusResponse:
    st = alarm.get_status()
    return AlarmStatusResponse(
        partitions=[
            PartitionResponse(
                id=p.id, name=p.name, armed=p.armed, mode=p.mode,
                entry_delay=p.entry_delay, ready=p.ready,
                zones=[
                    ZoneResponse(
                        id=z.id, name=z.name, open=z.open,
                        bypassed=z.bypassed, partition_id=z.partition_id,
                        alarm=z.alarm, was_in_alarm=z.was_in_alarm,
                        tamper=z.tamper,
                    )
                    for z in p.zones
                ],
            )
            for p in st.partitions
        ],
        connected=True,
    )


@app.get("/alarm/status", response_model=AlarmStatusResponse)
def alarm_status(
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
):
    if not alarm.is_connected:
        return AlarmStatusResponse(partitions=[], connected=False)
    try:
        return _build_status_response(alarm)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Alarm error: {exc}")


@app.post("/alarm/arm-away", response_model=ActionResult)
async def arm_away(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
    device: Annotated[str | None, Depends(get_device_name)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    try:
        ok = await alarm.arm_away(code=req.code, partition_id=req.partition_id)
    except ConnectionError:
        raise HTTPException(status_code=503, detail="Alarm connection lost")
    audit.record(user["sub"], "arm_away", f"partition={req.partition_id} success={ok}", device=device)
    return ActionResult(success=ok, action="arm_away")


@app.post("/alarm/arm-stay", response_model=ActionResult)
async def arm_stay(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
    device: Annotated[str | None, Depends(get_device_name)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    try:
        ok = await alarm.arm_stay(code=req.code, partition_id=req.partition_id)
    except ConnectionError:
        raise HTTPException(status_code=503, detail="Alarm connection lost")
    audit.record(user["sub"], "arm_stay", f"partition={req.partition_id} success={ok}", device=device)
    return ActionResult(success=ok, action="arm_stay")


@app.post("/alarm/disarm", response_model=ActionResult)
async def disarm(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
    device: Annotated[str | None, Depends(get_device_name)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    try:
        ok = await alarm.disarm(code=req.code, partition_id=req.partition_id)
    except ConnectionError:
        raise HTTPException(status_code=503, detail="Alarm connection lost")
    audit.record(user["sub"], "disarm", f"partition={req.partition_id} success={ok}", device=device)
    return ActionResult(success=ok, action="disarm")


# ── Bypass ──

@app.post("/alarm/bypass", response_model=ActionResult)
async def bypass_zone(
    req: BypassRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    try:
        if req.bypass:
            await alarm.bypass_zone(req.zone_id)
        else:
            await alarm.unbypass_zone(req.zone_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    action = "bypass" if req.bypass else "unbypass"
    audit.record(user["sub"], action, f"zone={req.zone_id}")
    return ActionResult(success=True, action=action)


# ── Zone toggle (demo debug) ──

@app.post("/alarm/zone-toggle", response_model=ActionResult)
def zone_toggle(
    req: ZoneToggleRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not alarm.demo_mode:
        raise HTTPException(status_code=403, detail="Zone toggle only available in demo mode")
    try:
        alarm.set_zone_open(req.zone_id, req.open)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    action = "zone_opened" if req.open else "zone_closed"
    audit.record(user["sub"], action, f"zone={req.zone_id}")
    return ActionResult(success=True, action=action, message=f"Zone {req.zone_id}")


# ── Panic ──

@app.post("/alarm/panic", response_model=ActionResult)
async def panic(
    req: PanicRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
    device: Annotated[str | None, Depends(get_device_name)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    try:
        ok = await alarm.send_panic(partition_id=req.partition_id, panic_type=req.panic_type)
    except ConnectionError:
        raise HTTPException(status_code=503, detail="Alarm connection lost")
    audit.record(user["sub"], "panic", f"partition={req.partition_id} type={req.panic_type}", device=device)
    return ActionResult(success=ok, action="panic")


# ── Event history ──

@app.get("/alarm/history", response_model=EventHistoryResponse)
def event_history(
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    limit: int = Query(default=50, le=200),
):
    events = alarm.get_zone_history(limit=limit)
    return EventHistoryResponse(
        events=[EventResponse(**e) for e in events]
    )


# ── Audit ──

@app.get("/alarm/logs", response_model=AuditLogResponse)
def audit_logs(
    user: Annotated[dict, Depends(get_current_user)],
    audit: Annotated[AuditService, Depends(get_audit)],
    limit: int = Query(default=50, le=200),
):
    logs = audit.recent(limit=limit)
    return AuditLogResponse(
        entries=[AuditEntry(**{k: v for k, v in e.items() if k != "id"}) for e in logs]
    )


# ── WebSocket ──

@app.websocket("/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    token: str = Query(...),
):
    auth = get_auth()
    mgr = get_ws()
    try:
        payload = auth.decode_token(token)
    except ValueError:
        await websocket.close(code=4001, reason="Invalid token")
        return
    await mgr.connect(websocket, username=payload["sub"])
    logger.info("WS client connected: %s (total: %d)", payload["sub"], mgr.active_count)
    try:
        alarm = get_alarm()
        if alarm.is_connected:
            resp = _build_status_response(alarm)
            events = alarm.get_zone_history(limit=20)
            await websocket.send_json({
                "type": "status",
                "partitions": [p.model_dump() for p in resp.partitions],
                "connected": resp.connected,
                "events": events,
            })
    except Exception:
        logger.debug("Failed to send initial WS status", exc_info=True)
    try:
        while True:
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_json({"type": "pong"})
    except WebSocketDisconnect:
        mgr.disconnect(websocket)
        logger.info("WS client disconnected (remaining: %d)", mgr.active_count)


# ── Health ──

@app.get("/health", response_model=HealthResponse)
def health():
    alarm = get_alarm()
    mgr = get_ws()
    return HealthResponse(
        status="ok",
        alarm_connected=alarm.is_connected,
        websocket_clients=mgr.active_count,
        demo_mode=alarm.demo_mode,
    )


@app.get("/debug/zone-raw")
def debug_zone_raw(
    _user: Annotated[dict, Depends(require_admin)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
):
    if alarm.demo_mode or not alarm._pai:
        return {"error": "only available in real mode with active connection"}
    zc = alarm._pai.storage.get_container("zone")
    result = {}
    for zid, zdata in zc.items():
        result[str(zid)] = {k: str(v) for k, v in zdata.items()}
    pc = alarm._pai.storage.get_container("partition")
    for pid, pdata in pc.items():
        result[f"part_{pid}"] = {k: str(v) for k, v in pdata.items()}
    return result
