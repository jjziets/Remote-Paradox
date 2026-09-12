"""Strict client reports, kept separate from trusted command diagnostic logs."""

import asyncio
import fcntl
import hashlib
import json
import logging
import os
import re
import stat
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from threading import BoundedSemaphore, RLock
from typing import Annotated, Literal

from fastapi import HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError, model_validator
from starlette.requests import ClientDisconnect

from paradox_bridge.diagnostics import client_request_id, panel_snapshot, request_id

MAX_BODY_BYTES = 128 * 1024
MAX_EVENTS = 512
RETENTION_MS = 24 * 60 * 60 * 1000
CLEANUP_INTERVAL = 60
MAX_REPORT_FILES = 64
MAX_STORAGE_BYTES = 8 * 1024 * 1024
MAX_RECORD_BYTES = 160 * 1024
RATE_WINDOW_MS = 60 * 60 * 1000
REPORTS_PER_USER = 5
MAX_LONG = 2**63 - 1
UUID_PATTERN = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
_PRERELEASE = r"(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
_SEMVER = (r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
           rf"(?:-{_PRERELEASE}(?:\.{_PRERELEASE})*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$")
Uuid = Annotated[str, StringConstraints(pattern=f"^{UUID_PATTERN}$", min_length=36, max_length=36)]
NonnegativeLong = Annotated[int, Field(ge=0, le=MAX_LONG)]
PositiveLong = Annotated[int, Field(ge=1, le=MAX_LONG)]
Device = Literal["phone", "watch"]
logger = logging.getLogger(__name__)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)


class DiagnosticEvent(StrictModel):
    timeMs: NonnegativeLong
    monotonicMs: NonnegativeLong
    processId: Uuid
    sequence: PositiveLong
    kind: Literal[
        "app_start", "foreground", "background", "command_requested", "command_finished",
        "http_started", "http_finished", "http_failed", "status_received", "ws_open",
        "ws_closed", "ws_failed", "tile_render", "tile_action", "report_requested",
    ]
    source: Literal["phone_app", "watch_app", "watch_tile", "http", "ws", "ble", "system"]
    requestId: Uuid | None = None
    route: Literal[
        "/alarm/arm-away", "/alarm/arm-stay", "/alarm/disarm", "/alarm/bypass",
        "/alarm/panic", "/alarm/status", "/ws",
    ] | None = None
    partitionId: Annotated[int, Field(ge=1, le=32)] | None = None
    zoneId: Annotated[int, Field(ge=1, le=512)] | None = None
    httpStatus: Annotated[int, Field(ge=100, le=599)] | None = None
    elapsedMs: NonnegativeLong | None = None
    success: bool | None = None
    connected: bool | None = None
    mode: Literal["disarmed", "arming", "armed_away", "armed_home", "triggered", "unknown"] | None = None
    openZones: Annotated[int, Field(ge=0, le=512)] | None = None
    bypassedZones: Annotated[int, Field(ge=0, le=512)] | None = None
    error: Literal["timeout", "connection", "http", "parse", "cancelled", "unknown"] | None = None


class DeviceLog(StrictModel):
    device: Device
    appVersion: Annotated[str, StringConstraints(pattern=_SEMVER, max_length=64)]
    buildCode: PositiveLong
    capturedAtMs: NonnegativeLong
    truncated: bool
    events: Annotated[list[DiagnosticEvent], Field(max_length=MAX_EVENTS)]


class DiagnosticReport(StrictModel):
    schemaVersion: Annotated[int, Field(ge=1, le=1)]
    reportId: Uuid
    watchStatus: Literal["included", "unavailable", "scope_mismatch"]
    phone: DeviceLog
    watch: DeviceLog | None = None

    @model_validator(mode="after")
    def consistent_devices(self):
        if self.phone.device != "phone":
            raise ValueError("Invalid phone device")
        if (self.watchStatus == "included") != (self.watch is not None):
            raise ValueError("Inconsistent watch status")
        if self.watch is not None and self.watch.device != "watch":
            raise ValueError("Invalid watch device")
        return self


class DiagnosticReceipt(StrictModel):
    reportId: Uuid
    receivedAtMs: NonnegativeLong
    expiresAtMs: NonnegativeLong
    sources: list[Device]


class ReportRejected(Exception):
    def __init__(self, status_code: int, detail: str, retry_after: int | None = None):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail
        self.retry_after = retry_after


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def validate_report(body: bytes) -> DiagnosticReport:
    if len(body) > MAX_BODY_BYTES:
        raise ReportRejected(413, "Diagnostic report too large")
    try:
        value = json.loads(body, object_pairs_hook=_unique_object)
        return DiagnosticReport.model_validate(value)
    except (ValueError, ValidationError, RecursionError):
        # Never expose Pydantic's input values, supplied keys, or JSON excerpts.
        raise ReportRejected(422, "Invalid diagnostic report") from None


def _encode(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


class ClientDiagnosticsStore:
    def __init__(self, directory: Path, *, max_files=MAX_REPORT_FILES,
                 max_bytes=MAX_STORAGE_BYTES, reports_per_user=REPORTS_PER_USER):
        self.directory = directory.absolute()
        self.max_files = max_files
        self.max_bytes = max_bytes
        self.reports_per_user = reports_per_user
        self._lock = RLock()
        self.upload_slots = BoundedSemaphore(4)

    @classmethod
    def for_config(cls, config_path: str):
        override = os.environ.get("PARADOX_CLIENT_DIAGNOSTICS_DIR")
        return cls(Path(override) if override else Path(config_path).parent / "client-diagnostics")

    @contextmanager
    def _locked(self):
        with self._lock:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            if self.directory.is_symlink():
                raise OSError("Invalid diagnostics directory")
            self.directory.chmod(0o700)
            fd = os.open(self.directory / ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                os.fchmod(fd, 0o600)
                fcntl.flock(fd, fcntl.LOCK_EX)
                yield
            finally:
                os.close(fd)

    def _records(self, now_ms: int):
        records = []
        total_bytes = 0
        total_files = 0
        for path in self.directory.iterdir():
            if path.name == ".lock":
                continue
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode):
                raise OSError("Unexpected diagnostics storage entry")
            if path.name.startswith(".upload-"):
                # The process lock excludes live uploads; these are crash leftovers.
                path.unlink()
                continue
            if re.fullmatch(UUID_PATTERN + r"\.json", path.name):
                path.chmod(0o600)
                try:
                    if info.st_size > MAX_RECORD_BYTES:
                        raise ValueError("Oversized stored report")
                    with path.open("rb") as stream:
                        record = json.load(stream)
                    receipt = DiagnosticReceipt.model_validate(record["receipt"])
                    if (receipt.expiresAtMs != receipt.receivedAtMs + RETENTION_MS
                            or receipt.reportId + ".json" != path.name):
                        raise ValueError("Invalid stored receipt")
                    expires_at = receipt.expiresAtMs
                except (ValueError, KeyError, TypeError, RecursionError):
                    # Damaged evidence must not prevent expiry of other reports.
                    record = None
                    expires_at = int(info.st_mtime * 1000) + RETENTION_MS
                if expires_at <= now_ms:
                    path.unlink()
                    continue
                if record is not None:
                    records.append((path.name, record))
            total_files += 1
            total_bytes += info.st_size
        return records, total_files, total_bytes

    def cleanup(self) -> None:
        if not self.directory.exists():
            return
        with self._locked():
            self._records(int(time.time() * 1000))

    def accept(self, body: bytes, uploader: dict, alarm) -> DiagnosticReceipt:
        report = validate_report(body)
        client_report = report.model_dump(exclude_none=True)
        client_report["reportId"] = report.reportId.lower()
        digest = hashlib.sha256(_encode(client_report)).hexdigest()
        filename = client_report["reportId"] + ".json"
        with self._locked():
            now_ms = int(time.time() * 1000)
            records, total_files, total_bytes = self._records(now_ms)
            for name, record in records:
                if name == filename:
                    if record["uploader"]["username"] != uploader["username"] or record["bodySha256"] != digest:
                        raise ReportRejected(409, "Diagnostic report ID conflict")
                    return DiagnosticReceipt.model_validate(record["receipt"])
            if (self.directory / filename).exists():
                raise ReportRejected(409, "Diagnostic report ID conflict")
            recent = [record["receipt"]["receivedAtMs"] for _, record in records
                      if record["uploader"]["username"] == uploader["username"]
                      and record["receipt"]["receivedAtMs"] > now_ms - RATE_WINDOW_MS]
            if len(recent) >= self.reports_per_user:
                retry_after = max(1, (min(recent) + RATE_WINDOW_MS - now_ms + 999) // 1000)
                raise ReportRejected(429, "Diagnostic report rate limit reached", retry_after)
            receipt = DiagnosticReceipt(
                reportId=client_report["reportId"], receivedAtMs=now_ms,
                expiresAtMs=now_ms + RETENTION_MS,
                sources=["phone", "watch"] if report.watch is not None else ["phone"],
            )
            record = {
                "receipt": receipt.model_dump(), "uploader": uploader,
                "serverRequestId": request_id.get(), "clientRequestId": client_request_id.get(),
                "panelSnapshot": panel_snapshot(alarm), "bodySha256": digest,
                "clientReport": client_report,
            }
            encoded = _encode(record)
            if (len(encoded) > MAX_RECORD_BYTES or total_files >= self.max_files
                    or total_bytes + len(encoded) > self.max_bytes):
                raise ReportRejected(507, "Diagnostic report storage is full")
            fd, temporary = tempfile.mkstemp(prefix=".upload-", dir=self.directory)
            try:
                with os.fdopen(fd, "wb") as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(encoded)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, self.directory / filename)
                directory_fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
            return receipt


async def cleanup_client_reports(store: ClientDiagnosticsStore) -> None:
    while True:
        try:
            await asyncio.to_thread(store.cleanup)
        except Exception:
            logger.warning("Client diagnostic cleanup failed")
        await asyncio.sleep(CLEANUP_INTERVAL)


async def receive_report(request: Request, store: ClientDiagnosticsStore, uploader: dict, alarm):
    # This function is called only after bearer and existing-user dependencies finish.
    lengths = request.headers.getlist("content-length")
    if lengths:
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,12}", lengths[0]):
            raise HTTPException(400, "Invalid diagnostic request")
        if int(lengths[0]) > MAX_BODY_BYTES:
            raise HTTPException(413, "Diagnostic report too large")
    if request.headers.get("content-encoding", "identity").lower() != "identity":
        raise HTTPException(415, "Unsupported diagnostic encoding")
    if not store.upload_slots.acquire(blocking=False):
        raise HTTPException(503, "Diagnostic uploads busy", headers={"Retry-After": "1"})
    worker = None
    try:
        body = bytearray()
        try:
            async with asyncio.timeout(15):
                async for chunk in request.stream():
                    if len(body) + len(chunk) > MAX_BODY_BYTES:
                        raise HTTPException(413, "Diagnostic report too large")
                    body.extend(chunk)
        except TimeoutError:
            raise HTTPException(408, "Diagnostic upload timed out") from None
        except ClientDisconnect:
            raise HTTPException(400, "Diagnostic upload interrupted") from None
        worker = asyncio.create_task(asyncio.to_thread(store.accept, bytes(body), uploader, alarm))

        def finished(task):
            store.upload_slots.release()
            if not task.cancelled():
                task.exception()

        worker.add_done_callback(finished)
        try:
            return await asyncio.shield(worker)
        except ReportRejected as exc:
            headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after is not None else None
            raise HTTPException(exc.status_code, exc.detail, headers=headers) from None
        except Exception:
            raise HTTPException(503, "Diagnostic storage unavailable") from None
    finally:
        if worker is None:
            store.upload_slots.release()
