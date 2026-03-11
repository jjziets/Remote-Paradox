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


class ZoneResponse(BaseModel):
    id: int
    name: str
    open: bool
    bypassed: bool = False
    partition_id: int = 1


class PartitionResponse(BaseModel):
    id: int
    name: str
    armed: bool
    mode: str
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


class ZoneEventResponse(BaseModel):
    zone_id: int
    zone_name: str
    partition_id: int
    event: str
    timestamp: str


class ZoneHistoryResponse(BaseModel):
    events: list[ZoneEventResponse]


class ActionResult(BaseModel):
    success: bool
    action: str
    message: str = ""


class AuditEntry(BaseModel):
    timestamp: str
    username: str
    action: str
    detail: str | None = None


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


class ErrorResponse(BaseModel):
    detail: str
