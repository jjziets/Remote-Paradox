"""Prove diagnostics preserve commands and exclude sensitive input."""

import asyncio
import json
import logging
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient

from paradox_bridge import diagnostics as diag


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
        handler.emit(logging.LogRecord("paradox.paradox", logging.ERROR, "", 0, message, (), None))
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
    pai_logger = logging.getLogger("paradox")
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
