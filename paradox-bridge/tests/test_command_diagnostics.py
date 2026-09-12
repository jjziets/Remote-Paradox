"""Prove diagnostics preserve commands and exclude sensitive input."""

import asyncio
import json
import logging
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient

from paradox_bridge import diagnostics as diag
from paradox_bridge.alarm import AlarmService


@pytest.fixture
def records():
    captured = []

    class Capture(logging.Handler):
        def emit(self, record):
            captured.append(json.loads(record.getMessage()))

    handler = Capture()
    diag.logger.addHandler(handler)
    yield captured
    diag.logger.removeHandler(handler)


@pytest.fixture
def alarm():
    containers = {
        "partition": {1: {"arm": False, "ready_status": False, "label": "PRIVATE LABEL"}},
        "zone": {2: {"open": True}, 3: {"open": True, "bypassed": True}},
    }
    return SimpleNamespace(
        _pai=SimpleNamespace(
            connection=SimpleNamespace(connected=True),
            request_lock=asyncio.Lock(),
            storage=SimpleNamespace(get_container=containers.__getitem__),
        ),
        _connected=True, _pai_loop_task=None, _last_panel_status_at=time.monotonic()-20,
        _pc_password="PRIVATE PASSWORD",
    )


def test_snapshot_exposes_stale_polling_and_readiness_without_labels(alarm):
    state = diag.panel_snapshot(alarm)
    assert state["status_stale"] is True
    assert state["status_age_s"] >= 20
    assert state["partitions"]["1"]["ready_status"] is False
    assert state["open_zones"] == ["2"]
    assert "PRIVATE" not in json.dumps(state)


@pytest.mark.asyncio
@pytest.mark.parametrize("accepted", [True, False])
async def test_command_result_preserved_and_sent_once(alarm, records, accepted):
    send = AsyncMock(return_value=accepted)
    assert await diag.trace_partition_command(alarm, 1, "disarm", send) is accepted
    send.assert_awaited_once_with("1", "disarm")
    assert [r["event"] for r in records] == ["command_started", "command_result"]
    assert records[-1]["accepted"] is accepted
    assert records[-1]["elapsed_ms"] >= 0
    assert records[0]["command_id"] == records[-1]["command_id"]


@pytest.mark.asyncio
@pytest.mark.parametrize("error", [ConnectionError, TimeoutError, asyncio.CancelledError])
async def test_exception_preserved_without_logging_its_sensitive_message(alarm, records, error):
    send = AsyncMock(side_effect=error("PRIVATE PIN"))
    with pytest.raises(error):
        await diag.trace_partition_command(alarm, 1, "arm", send)
    assert records[-1]["event"] == "command_error"
    assert records[-1]["error_type"] == error.__name__
    assert "PRIVATE" not in json.dumps(records)


@pytest.mark.asyncio
async def test_caller_cancellation_during_watcher_cleanup_propagates(alarm):
    before = asyncio.all_tasks()

    async def send(partition, command):
        asyncio.get_running_loop().call_soon(asyncio.current_task().cancel)
        return True

    task = asyncio.create_task(diag.trace_partition_command(alarm, 1, "arm", send))
    with pytest.raises(asyncio.CancelledError):
        await task
    assert task.cancelled()
    assert not (asyncio.all_tasks() - before)


@pytest.mark.asyncio
async def test_caller_cancellation_during_send_cleans_up_watcher(alarm, records):
    before = asyncio.all_tasks()
    started = asyncio.Event()

    async def send(partition, command):
        started.set()
        await asyncio.Event().wait()

    task = asyncio.create_task(diag.trace_partition_command(alarm, 1, "arm", send))
    await asyncio.wait_for(started.wait(), 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert records[-1]["error_type"] == "CancelledError"
    assert not (asyncio.all_tasks() - before)


@pytest.mark.asyncio
async def test_broken_snapshot_cannot_block_command(alarm, records):
    alarm._pai.storage = None
    send = AsyncMock(return_value=True)
    assert await diag.trace_partition_command(alarm, 2, "arm_stay", send) is True
    assert records[-1]["panel"]["snapshot_error"] == "AttributeError"


@pytest.mark.parametrize("http_status, accepted", [(200, False), (200, True), (401, None), (503, None)])
def test_http_status_and_boolean_logged_without_body_or_tokens(records, http_status, accepted):
    app = FastAPI()
    app.add_middleware(diag.CommandDiagnosticsMiddleware)

    @app.post("/alarm/disarm")
    async def disarm(request: Request):
        await request.json()
        diag.emit("endpoint_observed")
        return JSONResponse({"success": accepted, "detail": "PRIVATE RESPONSE"}, status_code=http_status)

    with TestClient(app) as client:
        response = client.post("/alarm/disarm?token=PRIVATE QUERY", json={"code": "PRIVATE PIN"},
                               headers={"Authorization": "Bearer PRIVATE TOKEN"})
    assert response.status_code == http_status
    assert response.json()["detail"] == "PRIVATE RESPONSE"
    assert records[-1]["http_status"] == http_status
    assert records[-1]["accepted"] is accepted
    assert len({r["request_id"] for r in records}) == 1
    assert records[0]["request_id"] is not None
    assert "PRIVATE" not in json.dumps(records)
    assert diag.request_id.get() is None


def test_health_polling_not_added_to_command_log(records):
    app = FastAPI()
    app.add_middleware(diag.CommandDiagnosticsMiddleware)

    @app.get("/health")
    def health():
        return {"status": "ok"}

    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
    assert records == []


def test_pai_handler_allowlists_signals_and_ignores_raw_packets(records):
    handler = diag.PaiDiagnosticHandler()
    for message in ("control_partition timeout", "Message received: PRIVATE PACKET"):
        handler.emit(logging.LogRecord("PAI.paradox.paradox", logging.ERROR, "", 0, message, (), None))
    assert len(records) == 1
    assert records[0]["signal"] == "command_timeout"
    assert "PRIVATE" not in json.dumps(records)


@pytest.mark.asyncio
async def test_waiting_command_leaves_evidence_before_result(alarm, records):
    async def send(partition, command):
        await asyncio.sleep(2.05)
        return False

    assert await diag.trace_partition_command(alarm, 1, "disarm", send) is False
    assert [r["event"] for r in records] == ["command_started", "command_waiting", "command_result"]


def test_failed_log_write_cannot_raise_into_alarm_control(monkeypatch):
    def fail(*args, **kwargs):
        raise OSError("Disk full")

    monkeypatch.setattr(diag.logger, "info", fail)
    diag.emit("test")


@pytest.mark.asyncio
async def test_logfile_and_monitor_lifecycle(alarm, records, tmp_path, monkeypatch):
    monkeypatch.setenv("PARADOX_DIAGNOSTICS_DIR", str(tmp_path))
    # /proc is Linux-specific; use a fixture boot id for the local Mac test.
    original_read = diag.Path.read_text

    def read_text(path, *args, **kwargs):
        if str(path) == "/proc/sys/kernel/random/boot_id":
            return "fixture-boot-id"
        return original_read(path, *args, **kwargs)

    monkeypatch.setattr(diag.Path, "read_text", read_text)
    task = diag.start_diagnostics(alarm)
    assert task is not None
    await asyncio.sleep(0)
    await diag.stop_diagnostics(task)
    rows = [json.loads(line) for line in (tmp_path / "commands.jsonl").read_text().splitlines()]
    assert [row["event"] for row in rows] == ["diagnostics_started", "panel_state", "diagnostics_stopped"]
    assert not any(isinstance(h, diag.TimedRotatingFileHandler) for h in diag.logger.handlers)


@pytest.mark.asyncio
async def test_failed_startup_closes_only_its_own_handlers(alarm, tmp_path, monkeypatch):
    monkeypatch.setenv("PARADOX_DIAGNOSTICS_DIR", str(tmp_path))
    opened = []
    original_handler = diag.TimedRotatingFileHandler

    def track_handler(*args, **kwargs):
        handler = original_handler(*args, **kwargs)
        opened.append(handler)
        return handler

    def fail_boot_id(*args, **kwargs):
        raise OSError("boot id unavailable")

    monkeypatch.setattr(diag, "TimedRotatingFileHandler", track_handler)
    monkeypatch.setattr(diag.Path, "read_text", fail_boot_id)
    pai_logger = logging.getLogger("PAI")
    existing_file = original_handler(tmp_path / "existing.jsonl")
    existing_pai = diag.PaiDiagnosticHandler()
    diag.logger.addHandler(existing_file)
    pai_logger.addHandler(existing_pai)
    before_file = list(diag.logger.handlers)
    before_pai = list(pai_logger.handlers)
    before_tasks = asyncio.all_tasks()
    try:
        for _ in range(2):
            assert diag.start_diagnostics(alarm) is None
            assert diag.logger.handlers == before_file
            assert pai_logger.handlers == before_pai
            assert all(handler.stream is None for handler in opened)
            assert not existing_file.stream.closed
            assert not (asyncio.all_tasks() - before_tasks)
    finally:
        for handler in opened + [existing_file]:
            diag.logger.removeHandler(handler)
            handler.close()
        for handler in list(pai_logger.handlers):
            if handler is existing_pai or handler not in before_pai:
                pai_logger.removeHandler(handler)
                handler.close()


@pytest.mark.parametrize("path", ["/alarm/bypass", "/alarm/panic"])
@pytest.mark.parametrize("valid_id", [True, False])
def test_correlation_keeps_server_id_and_never_echoes_invalid_header(records, path, valid_id):
    app = FastAPI()
    app.add_middleware(diag.CommandDiagnosticsMiddleware)

    @app.post(path)
    async def action():
        diag.emit("action_observed")
        return {"success": False}

    correlation = str(uuid4()) if valid_id else "PRIVATE_TOKEN_OR_PIN"
    with TestClient(app) as client:
        response = client.post(path, headers={"X-Diagnostic-Request-Id": correlation})
    assert response.status_code == 200 and response.json()["success"] is False
    assert {record["client_request_id"] for record in records} == {correlation if valid_id else None}
    server_ids = {record["request_id"] for record in records}
    assert len(server_ids) == 1 and None not in server_ids and correlation not in server_ids
    assert records[-1]["event"] == "request_finished" and records[-1]["accepted"] is False
    assert "PRIVATE" not in json.dumps(records)
    assert diag.request_id.get() is None and diag.client_request_id.get() is None


@pytest.mark.asyncio
async def test_concurrent_correlations_are_isolated_and_duplicates_ignored(records):
    async def app(scope, receive, send):
        await asyncio.sleep(0)
        diag.emit("observed", path=scope["path"])
        await send({"type": "http.response.start", "status": 200})
        await send({"type": "http.response.body", "body": b'{"success":true}'})

    middleware = diag.CommandDiagnosticsMiddleware(app)
    ids = [str(uuid4()), str(uuid4())]

    async def call(index):
        values = [ids[index]] if index < 2 else ids
        scope = {"type": "http", "method": "POST", "path": "/alarm/disarm",
                 "headers": [(b"x-diagnostic-request-id", value.encode()) for value in values]}
        await middleware(scope, AsyncMock(), AsyncMock())

    await asyncio.gather(*(call(index) for index in range(3)))
    observed = [record for record in records if record["event"] == "observed"]
    assert {record["client_request_id"] for record in observed} == {*ids, None}
    assert len({record["request_id"] for record in observed}) == 3
    assert diag.request_id.get() is None and diag.client_request_id.get() is None


@pytest.mark.asyncio
@pytest.mark.parametrize("operation,expected_args", [
    ("bypass_zone", ("16", "bypass")),
    ("unbypass_zone", ("16", "clear_bypass")),
    ("send_panic", ("2", "fire", "1")),
])
@pytest.mark.parametrize("outcome", [True, False, ConnectionError, asyncio.CancelledError])
async def test_alarm_zone_and_panic_tracing_preserves_calls_and_outcomes(records, operation, expected_args, outcome):
    alarm = AlarmService("/dev/null", 9600, "PRIVATE")
    send = AsyncMock()
    if isinstance(outcome, type):
        send.side_effect = outcome("PRIVATE ERROR")
    else:
        send.return_value = outcome
    alarm._pai = SimpleNamespace(connection=SimpleNamespace(connected=True), control_zone=send, send_panic=send)
    alarm._connected = True
    command = alarm.send_panic(2, "fire") if operation == "send_panic" else getattr(alarm, operation)(16)
    if isinstance(outcome, type):
        with pytest.raises(outcome):
            await command
    else:
        assert await command is outcome
        assert records[-1]["accepted"] is outcome
    send.assert_awaited_once_with(*expected_args)
    assert records[0]["event"] == "command_started"
    assert records[-1]["event"] == ("command_error" if isinstance(outcome, type) else "command_result")
    assert "PRIVATE" not in json.dumps(records)


@pytest.mark.asyncio
async def test_real_pai_logger_namespace_and_cleanup(alarm, records, tmp_path, monkeypatch):
    monkeypatch.setenv("PARADOX_DIAGNOSTICS_DIR", str(tmp_path))
    monkeypatch.setattr(diag.Path, "read_text", lambda *args, **kwargs: "fixture-boot-id")
    before = list(logging.getLogger("PAI").handlers)
    task = diag.start_diagnostics(alarm)
    try:
        logging.getLogger("PAI.paradox.paradox").warning("control_partition timeout")
        logging.getLogger("PAI.paradox.paradox").warning("control_zone timeout")
        logging.getLogger("PAI.paradox.paradox").warning("PRIVATE PACKET")
        signals = [record["signal"] for record in records if record["event"] == "pai_signal"]
        assert signals == ["command_timeout", "zone_command_timeout"]
        assert "PRIVATE" not in json.dumps(records)
    finally:
        await diag.stop_diagnostics(task)
    assert logging.getLogger("PAI").handlers == before
