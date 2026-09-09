"""Regression coverage for the September stale-status incident, without hardware."""

import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import AsyncMock

import pytest

from paradox_bridge.database import Database
from paradox_bridge.ws import ConnectionManager, StatusDispatcher


def test_concurrent_events_and_audit_keep_all_commits(tmp_path):
    db = Database(str(tmp_path / "events.db"))
    db.init()

    def writer(worker):
        for i in range(50):
            db.insert_event("partition", str(worker), "mode", str(i), "2026-09-09T12:00:00")
            db.log_action(str(worker), "test", str(i))
            db.get_events(1)

    try:
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(writer, range(8)))
        assert len(db.get_events(1000)) == 400
        assert len(db.get_audit_log(1000)) == 400
    finally:
        db.close()


@pytest.mark.asyncio
async def test_slow_socket_does_not_hold_up_healthy_socket():
    manager = ConnectionManager(send_timeout=0.05)
    slow, healthy = AsyncMock(), AsyncMock()
    never = asyncio.Event()
    delivered = asyncio.Event()

    async def stall(data):
        await never.wait()

    async def deliver(data):
        delivered.set()

    slow.send_json.side_effect = stall
    healthy.send_json.side_effect = deliver
    await manager.connect(slow, "slow")
    await manager.connect(healthy, "healthy")
    task = asyncio.create_task(manager.broadcast({"type": "status"}))
    await asyncio.wait_for(delivered.wait(), timeout=0.03)
    await task
    assert manager.active_count == 1
    slow.close.assert_awaited_once()
    assert await manager.send(slow, {}) is False


@pytest.mark.asyncio
async def test_socket_sends_do_not_overlap():
    manager = ConnectionManager()
    socket = AsyncMock()
    sending = False

    async def send(data):
        nonlocal sending
        assert not sending
        sending = True
        await asyncio.sleep(0)
        sending = False

    socket.send_json.side_effect = send
    await manager.connect(socket, "test")
    await asyncio.gather(*(manager.broadcast({"n": i}) for i in range(10)))
    assert socket.send_json.await_count == 10


@pytest.mark.asyncio
async def test_pai_thread_callbacks_run_on_api_loop_and_coalesce():
    owner = threading.get_ident()
    started, finish = asyncio.Event(), asyncio.Event()
    calls = []

    async def publish():
        calls.append(threading.get_ident())
        started.set()
        await finish.wait()

    dispatcher = StatusDispatcher(publish)
    try:
        await asyncio.to_thread(lambda: [dispatcher.notify() for _ in range(100)])
        await asyncio.wait_for(started.wait(), 1)
        finish.set()
        await asyncio.sleep(0.01)
        assert 1 <= len(calls) <= 2
        assert all(thread == owner for thread in calls)
    finally:
        await dispatcher.close()
    count = len(calls)
    await asyncio.to_thread(dispatcher.notify)
    await asyncio.sleep(0)
    assert len(calls) == count


@pytest.mark.asyncio
async def test_dispatcher_recovers_from_failed_publication():
    delivered = asyncio.Event()
    attempts = 0

    async def publish():
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise RuntimeError("test")
        delivered.set()

    dispatcher = StatusDispatcher(publish, retry_delay=0.01)
    try:
        dispatcher.notify()
        await asyncio.wait_for(delivered.wait(), 1)
        assert attempts == 2
    finally:
        await dispatcher.close()


@pytest.mark.asyncio
async def test_dispatcher_bounds_retries_and_heartbeat_cannot_skip_cooldown():
    loop = asyncio.get_running_loop()
    attempts = []

    async def publish():
        attempts.append(loop.time())
        raise RuntimeError("test")

    dispatcher = StatusDispatcher(publish, retry_delay=0.03, max_retries=2)
    try:
        dispatcher.notify()
        await asyncio.sleep(0)
        await asyncio.wait_for(asyncio.shield(dispatcher._task), 1)
        assert len(attempts) == 3
        await asyncio.sleep(0.01)
        assert len(attempts) == 3

        for _ in range(100):
            dispatcher.notify()
        await asyncio.sleep(0)
        await asyncio.wait_for(asyncio.shield(dispatcher._task), 1)
        assert len(attempts) == 6
        assert all(b - a >= 0.025 for a, b in zip(attempts, attempts[1:]))
    finally:
        await dispatcher.close()


@pytest.mark.asyncio
async def test_dispatcher_close_cancels_retry_at_heartbeat_interval():
    failed = asyncio.Event()

    async def publish():
        failed.set()
        raise RuntimeError("test")

    dispatcher = StatusDispatcher(publish)
    try:
        dispatcher.notify()
        await asyncio.wait_for(failed.wait(), 1)
        task = dispatcher._task
        assert task is not None and not task.done()
        assert dispatcher._retry_at - asyncio.get_running_loop().time() > 4
        for _ in range(100):
            dispatcher.notify()
        await asyncio.wait_for(dispatcher.close(), 0.1)
        assert task.done()
        assert dispatcher._task is None
        assert dispatcher._pending is False
    finally:
        await dispatcher.close()


@pytest.mark.asyncio
async def test_dispatcher_close_cancels_active_publication():
    started, stopped = asyncio.Event(), asyncio.Event()

    async def publish():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    dispatcher = StatusDispatcher(publish)
    try:
        dispatcher.notify()
        await asyncio.wait_for(started.wait(), 1)
        await asyncio.wait_for(dispatcher.close(), 0.1)
        assert stopped.is_set()
        assert dispatcher._task is None
    finally:
        await dispatcher.close()


@pytest.mark.asyncio
async def test_disconnected_panel_is_broadcast_instead_of_leaving_old_status(monkeypatch):
    from types import SimpleNamespace
    import paradox_bridge.main as main

    manager = SimpleNamespace(active_count=1, broadcast=AsyncMock())
    alarm = SimpleNamespace(is_connected=False, get_zone_history=lambda limit: [])
    monkeypatch.setattr(main, "_ws_manager", manager)
    monkeypatch.setattr(main, "_alarm", alarm)
    await main._broadcast_status()
    manager.broadcast.assert_awaited_once_with({
        "type": "status", "partitions": [], "connected": False, "events": [],
    })
