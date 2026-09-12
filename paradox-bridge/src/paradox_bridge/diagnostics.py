"""Opt-in, bounded command diagnostics. Never log bodies, credentials or packets."""

import asyncio
import json
import logging
import os
import re
import time
import uuid
from contextlib import suppress
from contextvars import ContextVar
from datetime import datetime, timezone
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path


logger = logging.getLogger("paradox_bridge.command_diagnostics")
logger.setLevel(logging.INFO)
logger.propagate = False
logger.addHandler(logging.NullHandler())
request_id = ContextVar("alarm_request_id", default=None)
client_request_id = ContextVar("alarm_client_request_id", default=None)
_PATHS = {"/alarm/arm-away", "/alarm/arm-stay", "/alarm/disarm", "/alarm/bypass", "/alarm/panic"}
_CLIENT_ID = re.compile(rb"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_PAI_SIGNALS = {
    "control_partition timeout": "command_timeout",
    "control_partition canceled": "command_cancelled",
    "control_zone timeout": "zone_command_timeout",
    "control_zone canceled": "zone_command_cancelled",
    "No partitions selected": "partition_not_found",
    "Lost communication with panel": "polling_timeout",
    "Connection to panel was lost": "connection_lost",
    "Error parsing message": "message_parse_error",
    "Unexpected exception during send_wait": "send_wait_error",
}


class PaiDiagnosticHandler(logging.Handler):
    def emit(self, record):
        # Match known templates only: library debug/raw-packet output can contain secrets.
        signal = _PAI_SIGNALS.get(record.msg) if isinstance(record.msg, str) else None
        if signal:
            emit("pai_signal", signal=signal)


def emit(event: str, **fields) -> None:
    # Diagnostic failures must never prevent an alarm operation.
    try:
        logger.info(json.dumps({
            "time": datetime.now(timezone.utc).isoformat(),
            "event": event, "request_id": request_id.get(), **fields,
            "client_request_id": client_request_id.get(),
        }, separators=(",", ":")))
    except Exception:
        pass


def panel_snapshot(alarm) -> dict:
    """Read cached panel state only; no serial I/O or database event generation."""
    try:
        pai = alarm._pai
        updated = getattr(alarm, "_last_panel_status_at", None)
        age = round(time.monotonic() - updated, 2) if updated is not None else None
        result = {
            "connected": bool(alarm._connected),
            "status_age_s": age,
            "status_stale": age is None or age > 15,
            "poll_task_done": alarm._pai_loop_task.done() if alarm._pai_loop_task else None,
        }
        if pai is None:
            return result
        result["serial_connected"] = bool(pai.connection.connected)
        result["request_locked"] = bool(pai.request_lock.locked())
        result["partitions"] = {
            str(pid): {key: bool(data[key]) if key in data else None for key in (
                "arm", "arm_stay", "arm_sleep", "exit_delay", "entry_delay",
                "ready_status", "audible_alarm", "silent_alarm", "fire",
            )}
            for pid, data in list(pai.storage.get_container("partition").items())
        }
        result["open_zones"] = [
            str(zid) for zid, data in list(pai.storage.get_container("zone").items())
            if data.get("open") and not data.get("bypassed")
            and data.get("definition") != "disabled"
        ]
        return result
    except Exception as exc:
        return {"snapshot_error": type(exc).__name__}


async def trace_partition_command(alarm, partition_id, command, send):
    return await _trace_command(alarm, {"partition": partition_id, "command": command},
                                send, str(partition_id), command)


async def trace_zone_command(alarm, zone_id, command, send):
    return await _trace_command(alarm, {"zone": zone_id, "command": command},
                                send, str(zone_id), command)


async def trace_panic_command(alarm, partition_id, panic_type, send):
    return await _trace_command(alarm, {"partition": partition_id, "command": "panic"},
                                send, str(partition_id), panic_type, "1")


async def _trace_command(alarm, fields, send, *args):
    command_id = uuid.uuid4().hex[:16]
    started = time.monotonic()
    fields = {"command_id": command_id, **fields}
    emit("command_started", **fields, panel=panel_snapshot(alarm))

    async def waiting():
        await asyncio.sleep(2)
        while True:
            emit("command_waiting", **fields,
                 elapsed_ms=round((time.monotonic() - started) * 1000),
                 panel=panel_snapshot(alarm))
            await asyncio.sleep(10)

    watcher = asyncio.create_task(waiting())
    try:
        accepted = await send(*args)
        emit("command_result", **fields, accepted=bool(accepted),
             elapsed_ms=round((time.monotonic() - started) * 1000),
             panel=panel_snapshot(alarm))
        return accepted
    except BaseException as exc:
        emit("command_error", **fields, error_type=type(exc).__name__,
             elapsed_ms=round((time.monotonic() - started) * 1000),
             panel=panel_snapshot(alarm))
        raise
    finally:
        watcher.cancel()
        # Consume the watcher's cancellation while preserving caller cancellation.
        await asyncio.gather(watcher, return_exceptions=True)


class CommandDiagnosticsMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        token = request_id.set(uuid.uuid4().hex[:16])
        values = [value for name, value in scope.get("headers", [])
                  if name.lower() == b"x-diagnostic-request-id"]
        client_id = (values[0].decode("ascii").lower()
                     if len(values) == 1 and _CLIENT_ID.fullmatch(values[0]) else None)
        client_token = client_request_id.set(client_id)
        try:
            if scope.get("path") not in _PATHS:
                return await self.app(scope, receive, send)
            return await self._trace_request(scope, receive, send)
        finally:
            client_request_id.reset(client_token)
            request_id.reset(token)

    async def _trace_request(self, scope, receive, send):
        started = time.monotonic()
        status = None
        response_bytes = bytearray()
        truncated = False
        emit("request_started", method=scope.get("method"), path=scope["path"])

        async def capture(message):
            nonlocal status, truncated
            if message["type"] == "http.response.start":
                status = message["status"]
            elif message["type"] == "http.response.body":
                body = message.get("body", b"")
                if len(response_bytes) + len(body) <= 4096:
                    response_bytes.extend(body)
                else:
                    truncated = True
            await send(message)

        try:
            await self.app(scope, receive, capture)
        except BaseException as exc:
            emit("request_error", error_type=type(exc).__name__)
            raise
        finally:
            accepted = None
            if not truncated:
                with suppress(ValueError, AttributeError, UnicodeDecodeError):
                    value = json.loads(response_bytes).get("success")
                    if isinstance(value, bool):
                        accepted = value
            emit("request_finished", http_status=status, accepted=accepted,
                 elapsed_ms=round((time.monotonic() - started) * 1000))


async def monitor_panel(alarm):
    previous = None
    heartbeat = 0.0
    while True:
        state = panel_snapshot(alarm)
        # Timing fields vary each poll; record changes to state and freshness.
        signature = {key: value for key, value in state.items()
                     if key not in ("status_age_s", "request_locked")}
        now = time.monotonic()
        if signature != previous or now - heartbeat >= 30:
            emit("panel_state", panel=state)
            previous = signature
            heartbeat = now
        await asyncio.sleep(5)


def start_diagnostics(alarm):
    directory = os.environ.get("PARADOX_DIAGNOSTICS_DIR")
    if not directory:
        return None
    handler = None
    pai_handler = None
    pai_logger = logging.getLogger("PAI")
    try:
        # Installer creates this private directory with the bridge user's ownership.
        handler = TimedRotatingFileHandler(
            Path(directory) / "commands.jsonl", when="H", interval=1,
            backupCount=23, utc=True, encoding="utf-8",
        )
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)
        pai_handler = PaiDiagnosticHandler(level=logging.WARNING)
        pai_logger.addHandler(pai_handler)
        emit("diagnostics_started", pid=os.getpid(),
             boot_id=Path("/proc/sys/kernel/random/boot_id").read_text().strip())
    except OSError:
        for owner, installed in ((logger, handler), (pai_logger, pai_handler)):
            if installed is not None:
                owner.removeHandler(installed)
                with suppress(OSError):
                    installed.close()
        logging.getLogger(__name__).warning("Could not start command diagnostics")
        return None
    return asyncio.create_task(monitor_panel(alarm))


async def stop_diagnostics(task):
    if task is not None:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        emit("diagnostics_stopped")
    for handler in list(logger.handlers):
        if isinstance(handler, TimedRotatingFileHandler):
            logger.removeHandler(handler)
            handler.close()
    pai_logger = logging.getLogger("PAI")
    for handler in list(pai_logger.handlers):
        if isinstance(handler, PaiDiagnosticHandler):
            pai_logger.removeHandler(handler)
            handler.close()
