"""Pydantic request/response schemas for the API."""

from pydantic import BaseModel, Field


class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    token: str
    username: str
    role: str


class RegisterRequest(BaseModel):
    invite_code: str
    username: str
    password: str = Field(min_length=4)


class RegisterResponse(BaseModel):
    token: str
    username: str


class InviteResponse(BaseModel):
    code: str
    uri: str
    expires_in_seconds: int
    qr_data_uri: str = ""


class ZoneResponse(BaseModel):
    id: int
    name: str
    open: bool
    bypassed: bool = False
    partition_id: int = 1
    alarm: bool = False
    was_in_alarm: bool = False
    tamper: bool = False


class PartitionResponse(BaseModel):
    id: int
    name: str
    armed: bool
    mode: str
    entry_delay: bool = False
    ready: bool = True
    zones: list[ZoneResponse]


class AlarmStatusResponse(BaseModel):
    partitions: list[PartitionResponse]
    connected: bool


class ArmRequest(BaseModel):
    code: str
    partition_id: int = 1


class BypassRequest(BaseModel):
    zone_id: int
    bypass: bool


class ZoneToggleRequest(BaseModel):
    zone_id: int
    open: bool


class PanicRequest(BaseModel):
    partition_id: int = 1
    panic_type: str = "emergency"  # emergency | fire | medical


class EventResponse(BaseModel):
    type: str           # "zone" | "partition"
    label: str
    property: str       # "open", "alarm", "entry_delay", "panic", etc.
    value: object       # bool or str
    timestamp: str
    user: str | None = None
    device: str | None = None


class EventHistoryResponse(BaseModel):
    events: list[EventResponse]


class ActionResult(BaseModel):
    success: bool
    action: str
    message: str = ""


class AuditEntry(BaseModel):
    timestamp: str
    username: str
    action: str
    detail: str | None = None
    device: str | None = None


class AuditLogResponse(BaseModel):
    entries: list[AuditEntry]


class HealthResponse(BaseModel):
    status: str
    alarm_connected: bool
    websocket_clients: int
    demo_mode: bool = False


class UserInfo(BaseModel):
    username: str
    role: str
    created_at: str


class UserListResponse(BaseModel):
    users: list[UserInfo]


class RoleUpdateRequest(BaseModel):
    role: str


class PasswordResetRequest(BaseModel):
    password: str = Field(min_length=6)


class SystemResourcesResponse(BaseModel):
    cpu_percent: float
    memory_used_mb: int
    memory_total_mb: int
    memory_percent: float
    disk_used_gb: float
    disk_total_gb: float
    disk_percent: float
    uptime_seconds: int


class WifiInfoResponse(BaseModel):
    ssid: str = ""
    ip_address: str = ""
    signal_dbm: int | None = None
    signal_percent: int | None = None


class BleClientInfo(BaseModel):
    address: str
    name: str
    username: str | None = None
    connected_at: str


class BleClientsResponse(BaseModel):
    clients: list[BleClientInfo]
    count: int


class PaiStatusResponse(BaseModel):
    connected: bool
    pc_password_masked: str
    serial_port: str
    baud: int

class PcPasswordUpdateRequest(BaseModel):
    pc_password: str

class ErrorResponse(BaseModel):
    detail: str
