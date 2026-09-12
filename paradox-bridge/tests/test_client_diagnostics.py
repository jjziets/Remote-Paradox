"""Authenticated diagnostic uploads never become a raw log or control channel."""

import asyncio
import copy
import json
import os
import stat
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
from unittest.mock import AsyncMock, Mock

import httpx
import pytest
from fastapi.testclient import TestClient

import paradox_bridge.client_diagnostics as cd
import paradox_bridge.main as main


def report(watch_status="unavailable"):
    event = {
        "timeMs": 1789171200000, "monotonicMs": 12345, "processId": str(uuid4()),
        "sequence": 1, "kind": "status_received", "source": "phone_app",
        "route": "/alarm/status", "connected": True, "mode": "disarmed",
        "openZones": 0, "bypassedZones": 1,
    }
    phone = {"device": "phone", "appVersion": "1.2.31", "buildCode": 81,
             "capturedAtMs": 1789171200000, "truncated": False, "events": [event]}
    value = {"schemaVersion": 1, "reportId": str(uuid4()), "watchStatus": watch_status, "phone": phone}
    if watch_status == "included":
        value["watch"] = copy.deepcopy(phone)
        value["watch"].update(device="watch", buildCode=25)
        value["watch"]["events"][0]["source"] = "watch_tile"
    return value


def encoded(value):
    return json.dumps(value, separators=(",", ":")).encode()


@pytest.fixture
def services(tmp_path, monkeypatch):
    monkeypatch.delenv("PARADOX_CLIENT_DIAGNOSTICS_DIR", raising=False)
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"demo_mode": True, "tls_cert_path": "", "tls_key_path": ""}))
    main.init_services(str(path))
    main._db.create_user("alice", "unused_hash")
    main._db.create_user("bob", "unused_hash")
    client = TestClient(main.app, raise_server_exceptions=False)
    token = main._auth._create_token("alice", "user")
    yield SimpleNamespace(client=client, headers={"Authorization": "Bearer " + token},
                          store=main._client_diagnostics, alarm=main._alarm)
    client.close()
    main.shutdown_services()


async def asgi_upload(headers, chunks):
    reads = 0
    messages = []
    chunks = iter(chunks)

    async def receive():
        nonlocal reads
        reads += 1
        return next(chunks)

    async def send(message):
        messages.append(message)

    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
             "method": "POST", "scheme": "https", "path": "/system/diagnostics",
             "raw_path": b"/system/diagnostics", "query_string": b"", "headers": headers,
             "server": ("testserver", 443), "client": ("127.0.0.1", 1234), "root_path": ""}
    await main.app(scope, receive, send)
    status = next(message["status"] for message in messages if message["type"] == "http.response.start")
    body = b"".join(message.get("body", b"") for message in messages)
    return status, reads, body


@pytest.mark.asyncio
@pytest.mark.parametrize("authorization", [None, "Bearer invalid", "expired", "deleted"])
async def test_authentication_precedes_all_body_reads(services, authorization):
    if authorization == "expired":
        authorization = "Bearer " + main._auth._create_expired_token_for_test("alice", "user")
    if authorization == "deleted":
        authorization = "Bearer " + main._auth._create_token("deleted", "user")
    headers = [(b"content-length", b"99999999")]
    if authorization:
        headers.append((b"authorization", authorization.encode()))
    status, reads, body = await asgi_upload(headers, [])
    assert status in (401, 403)
    assert reads == 0
    assert not services.store.directory.exists()


@pytest.mark.parametrize("watch_status", ["included", "unavailable", "scope_mismatch"])
def test_receipt_private_storage_and_trusted_metadata(services, watch_status):
    value = report(watch_status)
    correlation = str(uuid4())
    response = services.client.post("/system/diagnostics", json=value,
                                   headers={**services.headers, "X-Diagnostic-Request-Id": correlation})
    assert response.status_code == 200
    receipt = response.json()
    assert set(receipt) == {"reportId", "receivedAtMs", "expiresAtMs", "sources"}
    assert receipt["reportId"] == value["reportId"]
    assert receipt["expiresAtMs"] - receipt["receivedAtMs"] == cd.RETENTION_MS
    assert receipt["sources"] == (["phone", "watch"] if watch_status == "included" else ["phone"])
    path = services.store.directory / (value["reportId"] + ".json")
    saved = json.loads(path.read_bytes())
    assert saved["uploader"] == {"username": "alice", "role": "user"}
    assert saved["clientReport"] == value
    assert saved["clientRequestId"] == correlation
    assert saved["serverRequestId"] != correlation
    assert saved["panelSnapshot"]["connected"] is True
    assert "unused_hash" not in path.read_text()
    assert "Bearer" not in path.read_text()
    assert stat.S_IMODE(services.store.directory.stat().st_mode) == 0o700
    assert all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in services.store.directory.iterdir())
    assert services.client.get("/system/diagnostics", headers=services.headers).status_code == 405
    assert services.client.get("/system/diagnostics/" + value["reportId"], headers=services.headers).status_code == 404


def test_kotlin_fixture_validates_and_uploads_without_reserialization(services):
    fixture = (Path(__file__).resolve().parents[2] / "android-app" / "diagnostics"
               / "src" / "test" / "resources" / "client-report-v1.json")
    body = fixture.read_bytes()
    parsed = cd.validate_report(body)
    assert parsed.watchStatus == "included"
    assert parsed.phone.device == "phone" and parsed.phone.events
    assert parsed.watch is not None
    assert parsed.watch.device == "watch" and parsed.watch.events

    response = services.client.post(
        "/system/diagnostics", content=body,
        headers={**services.headers, "Content-Type": "application/json"},
    )
    assert response.status_code == 200, response.text
    receipt = response.json()
    assert set(receipt) == {"reportId", "receivedAtMs", "expiresAtMs", "sources"}
    assert receipt["reportId"] == parsed.reportId
    assert receipt["sources"] == ["phone", "watch"]
    assert receipt["expiresAtMs"] - receipt["receivedAtMs"] == cd.RETENTION_MS
    saved = json.loads((services.store.directory / (receipt["reportId"] + ".json")).read_bytes())
    assert saved["uploader"] == {"username": "alice", "role": "user"}
    assert saved["clientReport"] == parsed.model_dump(exclude_none=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("length", [str(cd.MAX_BODY_BYTES + 1), "1", None])
async def test_size_limit_for_declared_and_chunked_bodies(services, length):
    headers = [(b"authorization", services.headers["Authorization"].encode())]
    if length is not None:
        headers.append((b"content-length", length.encode()))
    chunks = [{"type": "http.request", "body": b" " * cd.MAX_BODY_BYTES, "more_body": True},
              {"type": "http.request", "body": b"x", "more_body": False}]
    status, reads, _ = await asgi_upload(headers, chunks)
    assert status == 413
    assert reads == (0 if length == str(cd.MAX_BODY_BYTES + 1) else 2)
    assert not services.store.directory.exists()


def test_exact_size_limit_is_accepted(services):
    body = encoded(report()).ljust(cd.MAX_BODY_BYTES, b" ")
    response = services.client.post("/system/diagnostics", content=body, headers=services.headers)
    assert response.status_code == 200


@pytest.mark.parametrize("location", ["report", "phone", "event"])
def test_unknown_fields_and_secrets_never_echo_or_persist(services, caplog, location):
    value = report()
    target = {"report": value, "phone": value["phone"], "event": value["phone"]["events"][0]}[location]
    target["PRIVATE_FIELD"] = "PRIVATE_TOKEN_PIN_BODY"
    response = services.client.post("/system/diagnostics", json=value, headers=services.headers)
    assert response.status_code == 422
    assert response.json() == {"detail": "Invalid diagnostic report"}
    assert "PRIVATE" not in caplog.text + response.text
    assert not services.store.directory.exists()


@pytest.mark.parametrize("field,value", [
    ("kind", "PRIVATE_MESSAGE"), ("source", "PRIVATE_URL"), ("route", "/alarm/status?token=PRIVATE"),
    ("mode", "PRIVATE"), ("error", "PRIVATE_STACK"), ("processId", "../../PRIVATE"),
    ("requestId", "PRIVATE_TOKEN"), ("partitionId", 33), ("zoneId", 513), ("httpStatus", 600),
    ("timeMs", -1), ("elapsedMs", -1), ("sequence", 0), ("monotonicMs", cd.MAX_LONG + 1),
    ("openZones", 513), ("bypassedZones", -1), ("success", 1), ("connected", "true"),
])
def test_event_allowlist_and_strict_types(services, field, value):
    payload = report()
    payload["phone"]["events"][0][field] = value
    response = services.client.post("/system/diagnostics", json=payload, headers=services.headers)
    assert response.status_code == 422
    assert response.json() == {"detail": "Invalid diagnostic report"}


@pytest.mark.parametrize("field,value", [("appVersion", "PRIVATE"), ("appVersion", "01.2.3"),
                                         ("buildCode", 0), ("buildCode", True), ("truncated", 1)])
def test_device_metadata_is_strict(services, field, value):
    payload = report()
    payload["phone"][field] = value
    assert services.client.post("/system/diagnostics", json=payload, headers=services.headers).status_code == 422


def test_event_count_and_schema_version_limit(services):
    payload = report()
    payload["phone"]["events"] *= cd.MAX_EVENTS + 1
    with pytest.raises(cd.ReportRejected) as exc:
        cd.validate_report(encoded(payload))
    assert exc.value.status_code == 422
    payload = report()
    payload["schemaVersion"] = True
    assert services.client.post("/system/diagnostics", json=payload, headers=services.headers).status_code == 422


@pytest.mark.parametrize("case", ["missing", "unexpected", "wrong_phone", "wrong_watch"])
def test_watch_status_matches_logs(services, case):
    payload = report("included")
    if case == "missing":
        payload.pop("watch")
    elif case == "unexpected":
        payload["watchStatus"] = "scope_mismatch"
    elif case == "wrong_phone":
        payload["phone"]["device"] = "watch"
    else:
        payload["watch"]["device"] = "phone"
    assert services.client.post("/system/diagnostics", json=payload, headers=services.headers).status_code == 422


def test_duplicate_keys_and_malformed_json_are_generic(services):
    for body in (b'{"reportId":"PRIVATE","reportId":"OTHER"}', b'{"token":"PRIVATE"', b'\xff', b'[' * 2000):
        response = services.client.post("/system/diagnostics", content=body, headers=services.headers)
        assert response.status_code == 422
        assert response.json() == {"detail": "Invalid diagnostic report"}


def test_idempotency_precedes_rate_limit_and_conflicts_are_rejected(services):
    services.store.reports_per_user = 1
    value = report()
    first = services.client.post("/system/diagnostics", json=value, headers=services.headers)
    retry = services.client.post("/system/diagnostics", content=json.dumps(value, indent=2), headers=services.headers)
    assert retry.status_code == 200 and retry.json() == first.json()
    different = services.client.post("/system/diagnostics", json=report(), headers=services.headers)
    assert different.status_code == 429 and int(different.headers["Retry-After"]) > 0
    value["phone"]["truncated"] = True
    assert services.client.post("/system/diagnostics", json=value, headers=services.headers).status_code == 409
    bob = {"Authorization": "Bearer " + main._auth._create_token("bob", "user")}
    assert services.client.post("/system/diagnostics", json=value, headers=bob).status_code == 409
    assert len(list(services.store.directory.glob("*.json"))) == 1


def test_rate_limit_survives_store_restart_and_is_per_user(services):
    services.store.reports_per_user = 1
    value = report()
    assert services.client.post("/system/diagnostics", json=value, headers=services.headers).status_code == 200
    main._client_diagnostics = cd.ClientDiagnosticsStore(services.store.directory, reports_per_user=1)
    assert services.client.post("/system/diagnostics", json=report(), headers=services.headers).status_code == 429
    bob = {"Authorization": "Bearer " + main._auth._create_token("bob", "user")}
    assert services.client.post("/system/diagnostics", json=report(), headers=bob).status_code == 200


def test_retention_uses_server_clock_and_retries_do_not_extend_it(services, monkeypatch):
    clock = [1800000000.0]
    monkeypatch.setattr(cd, "time", SimpleNamespace(time=lambda: clock[0]))
    value = report()
    value["phone"]["capturedAtMs"] = cd.MAX_LONG
    first = services.client.post("/system/diagnostics", json=value, headers=services.headers).json()
    clock[0] += 23 * 3600
    retry = services.client.post("/system/diagnostics", json=value, headers=services.headers).json()
    assert retry == first
    services.store.cleanup()
    assert len(list(services.store.directory.glob("*.json"))) == 1
    clock[0] += 3600
    services.store.cleanup()
    assert list(services.store.directory.glob("*.json")) == []


@pytest.mark.asyncio
async def test_scheduled_cleanup_expires_reports_without_new_upload(services, monkeypatch):
    clock = [1800000000.0]
    monkeypatch.setattr(cd, "time", SimpleNamespace(time=lambda: clock[0]))
    services.store.accept(encoded(report()), {"username": "alice", "role": "user"}, services.alarm)
    clock[0] += 24 * 3600
    monkeypatch.setattr(cd, "CLEANUP_INTERVAL", 0.01)
    cleaned = asyncio.Event()
    loop = asyncio.get_running_loop()
    original = services.store.cleanup

    def cleanup():
        original()
        loop.call_soon_threadsafe(cleaned.set)

    monkeypatch.setattr(services.store, "cleanup", cleanup)
    task = asyncio.create_task(cd.cleanup_client_reports(services.store))
    try:
        await asyncio.wait_for(cleaned.wait(), 1)
        assert list(services.store.directory.glob("*.json")) == []
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.parametrize("quota", ["files", "bytes"])
def test_global_quota_preserves_existing_reports_and_allows_retry(services, quota):
    payload = report()
    first = services.client.post("/system/diagnostics", json=payload, headers=services.headers)
    if quota == "files":
        services.store.max_files = 1
    else:
        services.store.max_bytes = sum(p.stat().st_size for p in services.store.directory.glob("*.json"))
    assert services.client.post("/system/diagnostics", json=report(), headers=services.headers).status_code == 507
    retry = services.client.post("/system/diagnostics", json=payload, headers=services.headers)
    assert retry.status_code == 200 and retry.json() == first.json()
    assert len(list(services.store.directory.glob("*.json"))) == 1


def test_concurrent_stores_share_idempotency_and_quota_lock(services):
    payload = encoded(report())
    stores = [cd.ClientDiagnosticsStore(services.store.directory, max_files=1) for _ in range(8)]
    uploader = {"username": "alice", "role": "user"}
    with ThreadPoolExecutor(max_workers=8) as pool:
        receipts = list(pool.map(lambda store: store.accept(payload, uploader, services.alarm), stores))
    assert all(receipt == receipts[0] for receipt in receipts)
    assert len(list(services.store.directory.glob("*.json"))) == 1

    def new_upload(store):
        try:
            store.accept(encoded(report()), uploader, services.alarm)
        except cd.ReportRejected as exc:
            return exc.status_code
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert list(pool.map(new_upload, stores)) == [507] * 8


@pytest.mark.asyncio
async def test_heavy_work_is_off_loop_and_uploads_are_bounded(services, monkeypatch):
    owner = threading.get_ident()
    started = asyncio.Event()
    release = threading.Event()
    loop = asyncio.get_running_loop()
    original = services.store.accept

    def blocked(*args):
        assert threading.get_ident() != owner
        loop.call_soon_threadsafe(started.set)
        assert release.wait(2)
        return original(*args)

    monkeypatch.setattr(services.store, "accept", blocked)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="https://testserver") as client:
        tasks = [asyncio.create_task(client.post("/system/diagnostics", json=report(), headers=services.headers))
                 for _ in range(4)]
        try:
            await asyncio.wait_for(started.wait(), 1)
            response = await asyncio.wait_for(client.get("/health"), 0.5)
            assert response.status_code == 200
            response = await client.post("/system/diagnostics", json=report(), headers=services.headers)
            assert response.status_code == 503
        finally:
            release.set()
            responses = await asyncio.gather(*tasks)
        assert all(response.status_code == 200 for response in responses)


def test_storage_failure_is_generic_and_does_not_break_other_routes(services, monkeypatch):
    def failed(*args):
        raise OSError("PRIVATE_PATH_OR_SECRET")

    monkeypatch.setattr(services.store, "accept", failed)
    response = services.client.post("/system/diagnostics", json=report(), headers=services.headers)
    assert response.status_code == 503 and "PRIVATE" not in response.text
    assert services.client.get("/health").status_code == 200


def test_storage_override_is_stable_and_private(tmp_path, monkeypatch):
    directory = tmp_path / "override"
    monkeypatch.setenv("PARADOX_CLIENT_DIAGNOSTICS_DIR", str(directory))
    store = cd.ClientDiagnosticsStore.for_config(str(tmp_path / "elsewhere" / "config.json"))
    assert store.directory == directory
    store.accept(encoded(report()), {"username": "alice", "role": "user"}, None)
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700


def test_snapshot_never_invokes_status_or_controls(services, monkeypatch):
    for name in ("get_status", "connect", "disconnect", "arm_away", "disarm", "bypass_zone", "send_panic"):
        monkeypatch.setattr(services.alarm, name, Mock(side_effect=AssertionError("Unexpected panel operation")))
    response = services.client.post("/system/diagnostics", json=report(), headers=services.headers)
    assert response.status_code == 200


def test_corrupt_report_does_not_block_cleanup_or_get_overwritten(services, monkeypatch):
    clock = [1800000000.0]
    monkeypatch.setattr(cd, "time", SimpleNamespace(time=lambda: clock[0]))
    value = report()
    services.store.accept(encoded(value), {"username": "alice", "role": "user"}, services.alarm)
    path = services.store.directory / (value["reportId"] + ".json")
    path.write_bytes(b'{"broken":')
    os.utime(path, (clock[0], clock[0]))
    response = services.client.post("/system/diagnostics", json=value, headers=services.headers)
    assert response.status_code == 409
    healthy = services.store.accept(encoded(report()), {"username": "alice", "role": "user"}, services.alarm)
    assert healthy.reportId != value["reportId"]
    clock[0] += 24 * 3600
    services.store.cleanup()
    assert list(services.store.directory.glob("*.json")) == []


def test_failed_atomic_write_leaves_no_partial_report(services, monkeypatch):
    value = report()
    replace = cd.os.replace

    def fail(*args):
        raise OSError("PRIVATE_STORAGE_ERROR")

    monkeypatch.setattr(cd.os, "replace", fail)
    response = services.client.post("/system/diagnostics", json=value, headers=services.headers)
    assert response.status_code == 503 and "PRIVATE" not in response.text
    assert [p.name for p in services.store.directory.iterdir()] == [".lock"]
    monkeypatch.setattr(cd.os, "replace", replace)
    assert services.client.post("/system/diagnostics", json=value, headers=services.headers).status_code == 200


@pytest.mark.asyncio
async def test_cancelled_upload_keeps_slot_until_writer_finishes(services, monkeypatch):
    started, finished = asyncio.Event(), asyncio.Event()
    release = threading.Event()
    loop = asyncio.get_running_loop()
    original = services.store.accept
    payload = report()

    def blocked(*args):
        loop.call_soon_threadsafe(started.set)
        try:
            assert release.wait(2)
            return original(*args)
        finally:
            loop.call_soon_threadsafe(finished.set)

    monkeypatch.setattr(services.store, "accept", blocked)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="https://testserver") as client:
        task = asyncio.create_task(client.post("/system/diagnostics", json=payload, headers=services.headers))
        await asyncio.wait_for(started.wait(), 1)
        task.cancel()
        try:
            with pytest.raises(asyncio.CancelledError):
                await task
            acquired = [services.store.upload_slots.acquire(blocking=False) for _ in range(4)]
            assert acquired == [True, True, True, False]
            for acquired_slot in acquired:
                if acquired_slot:
                    services.store.upload_slots.release()
        finally:
            release.set()
            await asyncio.wait_for(finished.wait(), 1)
        monkeypatch.setattr(services.store, "accept", original)
        response = await client.post("/system/diagnostics", json=payload, headers=services.headers)
        assert response.status_code == 200
    assert len(list(services.store.directory.glob("*.json"))) == 1


@pytest.mark.asyncio
async def test_cleanup_failure_is_retried_and_task_cancels(services, monkeypatch):
    calls = []
    recovered = asyncio.Event()
    loop = asyncio.get_running_loop()

    def cleanup():
        calls.append(True)
        if len(calls) == 1:
            raise OSError("PRIVATE")
        loop.call_soon_threadsafe(recovered.set)

    monkeypatch.setattr(services.store, "cleanup", cleanup)
    monkeypatch.setattr(cd, "CLEANUP_INTERVAL", 0.01)
    task = asyncio.create_task(cd.cleanup_client_reports(services.store))
    try:
        await asyncio.wait_for(recovered.wait(), 1)
        assert len(calls) >= 2
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert task.cancelled()


@pytest.mark.asyncio
@pytest.mark.parametrize("exceptional_exit", [False, True])
async def test_lifespan_starts_and_stops_scheduled_cleanup(services, monkeypatch, exceptional_exit):
    started, stopped = asyncio.Event(), asyncio.Event()

    async def cleanup(store):
        assert store is services.store
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    monkeypatch.setattr(main, "init_services", lambda: None)
    monkeypatch.setattr(main, "cleanup_client_reports", cleanup)
    monkeypatch.delenv("PARADOX_DIAGNOSTICS_DIR", raising=False)
    for name in ("_ws_heartbeat_loop", "_event_purge_loop", "_alert_monitor_loop", "_demo_zone_trigger_loop"):
        monkeypatch.setattr(main, name, AsyncMock())

    async def exercise_lifespan():
        async with main.lifespan(main.app):
            await asyncio.wait_for(started.wait(), 1)
            if exceptional_exit:
                raise RuntimeError("Application failure")

    if exceptional_exit:
        with pytest.raises(RuntimeError, match="Application failure"):
            await exercise_lifespan()
    else:
        await exercise_lifespan()
    assert stopped.is_set()


@pytest.mark.asyncio
async def test_failed_startup_does_not_start_client_cleanup(services, monkeypatch):
    cleanup = AsyncMock()
    monkeypatch.setattr(main, "init_services", lambda: None)
    monkeypatch.setattr(main, "cleanup_client_reports", cleanup)
    monkeypatch.setattr(main, "start_diagnostics", Mock(side_effect=RuntimeError("Startup failure")))
    with pytest.raises(RuntimeError, match="Startup failure"):
        async with main.lifespan(main.app):
            pytest.fail("Startup should fail")
    cleanup.assert_not_called()
