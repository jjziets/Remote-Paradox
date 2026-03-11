"""FastAPI application — routes, dependency injection, lifespan."""

import asyncio
import logging
import os
import random
import socket
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from paradox_bridge.alarm import AlarmService
from paradox_bridge.audit import AuditService
from paradox_bridge.auth import AuthService
from paradox_bridge.config import AppConfig, load_config
from paradox_bridge.database import Database
from paradox_bridge.models import (
    ActionResult,
    AlarmStatusResponse,
    ArmRequest,
    AuditEntry,
    AuditLogResponse,
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
    RegisterRequest,
    RegisterResponse,
    UserInfo,
    UserListResponse,
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
    )
    _ws_manager = ConnectionManager()


def shutdown_services() -> None:
    global _db, _alarm
    if _db:
        _db.close()
        _db = None
    _alarm = None


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


@asynccontextmanager
async def lifespan(application: FastAPI):
    global _demo_trigger_task
    init_services()
    admin_user = os.environ.get("PARADOX_ADMIN_USER", "admin")
    admin_pass = os.environ.get("PARADOX_ADMIN_PASS")
    if admin_pass and _auth:
        _auth.setup_admin(admin_user, admin_pass)
    if _alarm and _alarm.demo_mode:
        _demo_trigger_task = asyncio.create_task(_demo_zone_trigger_loop())
    yield
    if _demo_trigger_task:
        _demo_trigger_task.cancel()
        _demo_trigger_task = None
    shutdown_services()


app = FastAPI(title="Paradox Bridge", version="0.2.0", lifespan=lifespan)


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
        raise HTTPException(status_code=404, detail=str(exc))
    audit.record(admin["sub"], "delete_user", f"deleted {username}")
    return ActionResult(success=True, action="delete_user", message=f"User '{username}' deleted")


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
def arm_away(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    ok = alarm.arm_away(code=req.code, partition_id=req.partition_id)
    audit.record(user["sub"], "arm_away", f"partition={req.partition_id} success={ok}")
    return ActionResult(success=ok, action="arm_away")


@app.post("/alarm/arm-stay", response_model=ActionResult)
def arm_stay(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    ok = alarm.arm_stay(code=req.code, partition_id=req.partition_id)
    audit.record(user["sub"], "arm_stay", f"partition={req.partition_id} success={ok}")
    return ActionResult(success=ok, action="arm_stay")


@app.post("/alarm/disarm", response_model=ActionResult)
def disarm(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    ok = alarm.disarm(code=req.code, partition_id=req.partition_id)
    audit.record(user["sub"], "disarm", f"partition={req.partition_id} success={ok}")
    return ActionResult(success=ok, action="disarm")


# ── Bypass ──

@app.post("/alarm/bypass", response_model=ActionResult)
def bypass_zone(
    req: BypassRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    try:
        if req.bypass:
            alarm.bypass_zone(req.zone_id)
        else:
            alarm.unbypass_zone(req.zone_id)
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
def panic(
    req: PanicRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    ok = alarm.send_panic(partition_id=req.partition_id, panic_type=req.panic_type)
    audit.record(user["sub"], "panic", f"partition={req.partition_id} type={req.panic_type}")
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
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        mgr.disconnect(websocket)


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
