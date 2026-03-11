"""FastAPI application — routes, dependency injection, lifespan."""

import logging
import os
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
    ErrorResponse,
    HealthResponse,
    InviteResponse,
    LoginRequest,
    LoginResponse,
    RegisterRequest,
    RegisterResponse,
    UserInfo,
    UserListResponse,
    ZoneResponse,
)
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


@asynccontextmanager
async def lifespan(application: FastAPI):
    init_services()
    admin_user = os.environ.get("PARADOX_ADMIN_USER", "admin")
    admin_pass = os.environ.get("PARADOX_ADMIN_PASS")
    if admin_pass and _auth:
        _auth.setup_admin(admin_user, admin_pass)
    yield
    shutdown_services()


app = FastAPI(title="Paradox Bridge", version="0.1.0", lifespan=lifespan)


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
    uri = auth.build_invite_uri(
        code, host=_get_local_ip(), port=cfg.api_port, fingerprint=_cert_fingerprint,
    )
    audit.record(user["sub"], "create_invite", f"code={code}")
    return InviteResponse(code=code, uri=uri, expires_in_seconds=cfg.invite_expiry_seconds)


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

@app.get("/alarm/status", response_model=AlarmStatusResponse)
def alarm_status(
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
):
    if not alarm.is_connected:
        return AlarmStatusResponse(armed=False, mode="unknown", zones=[], connected=False)
    try:
        st = alarm.get_status()
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Alarm error: {exc}")
    return AlarmStatusResponse(
        armed=st.armed,
        mode=st.mode,
        zones=[ZoneResponse(id=z.id, name=z.name, open=z.open) for z in st.zones],
        connected=True,
    )


@app.post("/alarm/arm-away", response_model=ActionResult)
def arm_away(
    req: ArmRequest,
    user: Annotated[dict, Depends(get_current_user)],
    alarm: Annotated[AlarmService, Depends(get_alarm)],
    audit: Annotated[AuditService, Depends(get_audit)],
):
    if not alarm.is_connected:
        raise HTTPException(status_code=503, detail="Alarm not connected")
    ok = alarm.arm_away(code=req.code)
    audit.record(user["sub"], "arm_away", f"success={ok}")
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
    ok = alarm.arm_stay(code=req.code)
    audit.record(user["sub"], "arm_stay", f"success={ok}")
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
    ok = alarm.disarm(code=req.code)
    audit.record(user["sub"], "disarm", f"success={ok}")
    return ActionResult(success=ok, action="disarm")


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
    )
