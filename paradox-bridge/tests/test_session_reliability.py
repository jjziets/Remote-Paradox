"""Session/serial reliability regressions. All transports are simulated."""

import asyncio
import sys
import time
import tomllib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from paradox_bridge.alarm import AlarmService
from paradox_bridge.pai_adapter import (
    ControlOperation, PanelSession, SessionCleanupError, SessionParadoxMixin,
    _publisher_session, control_operation,
)


class FakeBase:
    pass


class Core(SessionParadoxMixin, FakeBase):
    def __init__(self, session):
        self.session = session
        self.request_lock = asyncio.Lock()
        self.connection = SimpleNamespace(connected=True, write=Mock(),
                                         wait_for_message=AsyncMock(return_value=object()))


@pytest.fixture
def real_alarm():
    alarm = AlarmService("/dev/null", 9600, "0000")
    session = PanelSession(lambda: alarm._session is session, lambda: alarm.is_connected,
                           status_lock=alarm._status_lock)
    alarm._session = session
    alarm._pai = session.pai = Core(session)
    alarm._last_panel_status_at = time.monotonic()
    alarm._connected = True
    return alarm


@pytest.mark.asyncio
async def test_lost_ack_control_writes_once_and_retires(real_alarm):
    alarm = real_alarm
    alarm._pai.connection.wait_for_message.side_effect = TimeoutError

    async def send(*args):
        try:
            await alarm._pai.send_wait(message=b"control", reply_expected=4)
        except TimeoutError:
            return False  # PAI wrapper swallows its timeout

    alarm._pai.control_partition = send
    assert await alarm.disarm("0000") is False
    alarm._pai.connection.write.assert_called_once_with(b"control")
    assert not alarm.is_connected


@pytest.mark.asyncio
async def test_safe_reads_keep_five_retries(real_alarm):
    core = real_alarm._pai
    core.connection.wait_for_message.side_effect = TimeoutError
    with pytest.raises(TimeoutError):
        await core.send_wait(message=b"read", reply_expected=5)
    assert core.connection.write.call_count == 5


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["expired", "stale", "retired", "invalidated", "used"])
async def test_postlock_gate_blocks_writes(real_alarm, reason):
    alarm, core = real_alarm, real_alarm._pai
    op = ControlOperation(alarm._session, time.monotonic() + 1)
    token = control_operation.set(op)
    await core.request_lock.acquire()
    try:
        task = asyncio.create_task(core.send_wait(message=b"control", reply_expected=4))
        await asyncio.sleep(0)
        if reason == "expired":
            op.deadline = time.monotonic() - 1
        elif reason == "stale":
            alarm._last_panel_status_at -= 31
        elif reason == "retired":
            alarm._session.invalidated = True
        elif reason == "invalidated":
            op.invalidated = True
        else:
            op.write_attempt = True
        core.request_lock.release()
        with pytest.raises(ConnectionError):
            await task
        core.connection.write.assert_not_called()
    finally:
        control_operation.reset(token)


@pytest.mark.asyncio
async def test_control_deadline_counts_poll_lock_wait(real_alarm):
    alarm, core = real_alarm, real_alarm._pai
    alarm.CONTROL_TIMEOUT = 0.02
    await core.request_lock.acquire()
    core.control_partition = lambda *args: core.send_wait(message=b"control", reply_expected=4)
    assert await alarm.disarm("0000") is False
    core.request_lock.release()
    await asyncio.sleep(0)
    core.connection.write.assert_not_called()
    assert alarm._session.invalidated


@pytest.mark.asyncio
async def test_busy_control_rejected_without_queue(real_alarm):
    alarm = real_alarm
    started, finish = asyncio.Event(), asyncio.Event()

    async def send(*args):
        started.set()
        await finish.wait()
        return True

    alarm._pai.control_partition = send
    first = asyncio.create_task(alarm.disarm("0000"))
    await started.wait()
    assert await asyncio.wait_for(alarm.arm_away("0000"), 0.03) is False
    finish.set()
    assert await first is True
    assert control_operation.get() is None


@pytest.mark.asyncio
async def test_swallowed_cancel_cannot_report_late_success(real_alarm):
    alarm = real_alarm
    alarm.CONTROL_TIMEOUT = 0.01
    finished = asyncio.Event()

    async def send(*args):
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            finished.set()
            return True

    alarm._pai.control_partition = send
    assert await alarm.disarm("0000") is False
    await finished.wait()
    assert not alarm.is_connected


@pytest.mark.asyncio
async def test_caller_cancel_invalidates_before_child_can_write(real_alarm):
    alarm, core = real_alarm, real_alarm._pai
    started, finished = asyncio.Event(), asyncio.Event()

    async def send(*args):
        started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            try:
                await core.send_wait(message=b"late", reply_expected=4)
            except ConnectionError:
                pass
            finished.set()
            return True

    core.control_partition = send
    caller = asyncio.create_task(alarm.disarm("0000"))
    await started.wait()
    caller.cancel()
    with pytest.raises(asyncio.CancelledError):
        await caller
    await finished.wait()
    core.connection.write.assert_not_called()
    assert not alarm.is_connected


def test_stale_data_unavailable_and_availability_callback(real_alarm):
    alarm = real_alarm
    cb = Mock()
    alarm.set_status_change_callback(cb)
    alarm._last_panel_status_at -= 31
    assert not alarm.is_connected
    with pytest.raises(ConnectionError):
        alarm.get_status()
    cb.assert_called_once()
    assert not alarm.is_connected
    cb.assert_called_once()


@pytest.mark.asyncio
async def test_stale_http_health_ws_empty(real_alarm, monkeypatch):
    import paradox_bridge.main as main

    real_alarm._last_panel_status_at -= 31
    monkeypatch.setattr(main, "_alarm", real_alarm)
    manager = SimpleNamespace(active_count=1, broadcast=AsyncMock())
    monkeypatch.setattr(main, "_ws_manager", manager)
    assert main.alarm_status({}, real_alarm).model_dump() == {"partitions": [], "connected": False}
    assert not main.health().alarm_connected
    await main._broadcast_status()
    assert manager.broadcast.await_args.args[0]["partitions"] == []
    assert manager.broadcast.await_args.args[0]["connected"] is False


@pytest.mark.asyncio
@pytest.mark.parametrize("bypass", [True, False])
async def test_bypass_false_reaches_response_and_audit(bypass):
    import paradox_bridge.main as main
    from paradox_bridge.models import BypassRequest

    alarm = SimpleNamespace(bypass_zone=AsyncMock(return_value=False),
                            unbypass_zone=AsyncMock(return_value=False))
    audit = Mock()
    result = await main.bypass_zone(BypassRequest(zone_id=16, bypass=bypass), {"sub": "test"}, alarm, audit)
    assert result.success is False
    assert "success=False" in audit.record.call_args.args[2]


def _cleanup_core(core, events):
    core._connection = core.connection
    core._connection._protocol = object()
    core.detach = Mock()

    async def close(**kwargs):
        events.append("close")
        core._connection.connected = False
        core._connection._protocol = None

    core.disconnect = close


@pytest.mark.asyncio
async def test_failed_gather_sibling_drained_before_uart_replacement(real_alarm, monkeypatch):
    alarm, core = real_alarm, real_alarm._pai
    events = []
    started, release = asyncio.Event(), asyncio.Event()
    _cleanup_core(core, events)

    async def fail():
        await started.wait()
        raise TimeoutError

    async def sibling():
        started.set()
        while not release.is_set():
            try:
                await release.wait()
            except asyncio.CancelledError:
                pass
        events.append("drained")

    core.panel = SimpleNamespace(get_status_requests=lambda: [fail(), sibling()])
    core.loop = core.poll_once
    poll = alarm._session.spawn(alarm._run_pai_loop(alarm._session))
    await poll
    assert not alarm.is_connected
    assert any(not task.done() for task in alarm._session.tasks)

    async def open_replacement():
        events.append("open")

    monkeypatch.setattr(alarm, "_connect_locked", open_replacement)
    replacement = asyncio.create_task(alarm.connect())
    await asyncio.sleep(0.01)
    assert events == []
    assert not replacement.done()
    release.set()
    await replacement
    assert events == ["drained", "close", "open"]


@pytest.mark.asyncio
async def test_cleanup_deadline_holds_automatic_uart_replacement(real_alarm, monkeypatch):
    alarm, core = real_alarm, real_alarm._pai
    alarm.CLEANUP_TIMEOUT = 0.01
    release, started = asyncio.Event(), asyncio.Event()
    events = []
    _cleanup_core(core, events)

    async def uncooperative():
        started.set()
        while not release.is_set():
            try:
                await release.wait()
            except asyncio.CancelledError:
                pass

    old_task = alarm._session.spawn(uncooperative())
    await started.wait()
    alarm._retire_session(alarm._session)
    opened = AsyncMock()
    monkeypatch.setattr(alarm, "_connect_locked", opened)
    try:
        with pytest.raises(SessionCleanupError):
            await alarm.connect()
        assert alarm._session is not None and not alarm.reconnect_allowed
        assert events == []
        with pytest.raises(ConnectionError):
            await alarm.connect()
        opened.assert_not_awaited()
    finally:
        release.set()
        await old_task
    # Once tasks stop, replacement remains held until an explicit qualified start.
    with pytest.raises(ConnectionError):
        await alarm.connect()
    await alarm.start()
    assert events == ["close"]
    opened.assert_awaited_once()


@pytest.mark.asyncio
async def test_close_failure_keeps_old_session_unavailable(real_alarm, monkeypatch):
    alarm, core = real_alarm, real_alarm._pai
    events = []
    _cleanup_core(core, events)
    core.disconnect = AsyncMock(side_effect=TimeoutError)
    alarm._retire_session(alarm._session)
    old_session = alarm._session
    opened = AsyncMock()
    monkeypatch.setattr(alarm, "_connect_locked", opened)
    with pytest.raises(SessionCleanupError):
        await alarm.connect()
    assert alarm._session is old_session
    assert not alarm.is_connected and not alarm.reconnect_allowed
    with pytest.raises(ConnectionError):
        await alarm.connect()
    opened.assert_not_awaited()


@pytest.mark.asyncio
async def test_old_poll_finalizer_and_completion_cannot_touch_replacement(real_alarm):
    alarm = real_alarm
    old_session = alarm._session
    finish = asyncio.Event()
    old_session.pai.loop = finish.wait
    old_poll = old_session.spawn(alarm._run_pai_loop(old_session))
    await asyncio.sleep(0)
    new = PanelSession(lambda: alarm._session is new, lambda: alarm.is_connected, pai=Mock())
    alarm._session = new
    alarm._pai = new.pai
    updated = alarm._last_panel_status_at
    alarm._full_poll_completed(old_session)
    assert alarm._last_panel_status_at == updated
    finish.set()
    await old_poll
    assert alarm.is_connected and not new.invalidated


def test_global_publisher_identity_required_even_for_active_subscriber(real_alarm):
    old = real_alarm._session
    labels = Mock()

    class Base:
        def _on_labels_load(self, data):
            labels(data)

    class Subscriber(SessionParadoxMixin, Base):
        pass

    new = PanelSession(lambda: True, lambda: True)
    subscriber = Subscriber()
    subscriber.session = new
    subscriber._on_labels_load({})  # unknown global publisher
    token = _publisher_session.set(old)
    try:
        subscriber._on_labels_load({})  # current subscriber, wrong publisher
    finally:
        _publisher_session.reset(token)
    labels.assert_not_called()
    token = _publisher_session.set(new)
    try:
        subscriber._on_labels_load({"own": True})
    finally:
        _publisher_session.reset(token)
    labels.assert_called_once_with({"own": True})


@pytest.mark.asyncio
async def test_poll_children_do_not_inherit_control_allowance(real_alarm):
    session = real_alarm._session
    op = ControlOperation(session, time.monotonic() + 1)
    token = control_operation.set(op)
    try:
        async def read():
            assert control_operation.get() is None
            assert _publisher_session.get() is session
        await session.spawn(read())
    finally:
        control_operation.reset(token)


@pytest.mark.asyncio
async def test_admin_stop_does_not_depend_on_fresh_connection(real_alarm):
    alarm, core = real_alarm, real_alarm._pai
    events = []
    _cleanup_core(core, events)
    alarm._last_panel_status_at -= 31
    assert not alarm.is_connected
    await alarm.stop()
    assert events == ["close"]
    assert not alarm.reconnect_allowed and alarm._session is None
    with pytest.raises(ConnectionError):
        await alarm.connect()


@pytest.mark.asyncio
async def test_stop_wins_against_start_waiting_for_lifecycle_lock(real_alarm, monkeypatch):
    alarm = real_alarm
    _cleanup_core(alarm._pai, [])
    await alarm._lifecycle_lock.acquire()
    opened = AsyncMock()
    monkeypatch.setattr(alarm, "_connect_locked", opened)
    start = asyncio.create_task(alarm.start())
    await asyncio.sleep(0)
    stop = asyncio.create_task(alarm.stop())
    await asyncio.sleep(0)
    alarm._lifecycle_lock.release()
    with pytest.raises(ConnectionError):
        await start
    await stop
    opened.assert_not_awaited()
    assert not alarm.reconnect_allowed


@pytest.mark.parametrize("age, connected", [(9.9, True), (25.0, True), (29.9, True), (30.0, False), (107.65, False)])
def test_freshness_boundary_conservative_and_bounded(real_alarm, age, connected, monkeypatch):
    monkeypatch.setattr(time, "monotonic", lambda: real_alarm._last_panel_status_at + age)
    assert real_alarm.is_connected is connected


def test_first_full_poll_required_but_startup_grace_bounded(real_alarm, monkeypatch):
    real_alarm._connected = False
    real_alarm._last_panel_status_at = None
    start = real_alarm._session.started_at
    monkeypatch.setattr(time, "monotonic", lambda: start + 25)
    assert not real_alarm.is_connected and not real_alarm.needs_reconnect
    monkeypatch.setattr(time, "monotonic", lambda: start + 30)
    assert real_alarm.needs_reconnect


@pytest.mark.asyncio
async def test_write_allowance_consumed_even_if_write_raises(real_alarm):
    core, session = real_alarm._pai, real_alarm._session
    core.connection.write.side_effect = OSError("after possible send")
    op = ControlOperation(session, time.monotonic() + 1)
    token = control_operation.set(op)
    try:
        with pytest.raises(OSError):
            await core.send_wait(message=b"control")
        assert op.write_attempt
        with pytest.raises(ConnectionError):
            await core.send_wait(message=b"control")
        core.connection.write.assert_called_once()
    finally:
        control_operation.reset(token)


@pytest.mark.asyncio
async def test_swallowed_child_self_cancellation_cannot_report_success(real_alarm):
    async def send(*args):
        asyncio.current_task().cancel()
        try:
            await asyncio.sleep(0)
        except asyncio.CancelledError:
            return True

    real_alarm._pai.control_partition = send
    assert await real_alarm.disarm("0000") is False
    assert not real_alarm.is_connected


@pytest.mark.asyncio
async def test_uart_close_deadline_never_opens_replacement_until_verified(real_alarm, monkeypatch):
    alarm, core = real_alarm, real_alarm._pai
    events = []
    _cleanup_core(core, events)
    alarm.CLEANUP_TIMEOUT = 0.01
    release = asyncio.Event()
    close = core.disconnect

    async def stalled_close(**kwargs):
        await release.wait()
        await close()

    core.disconnect = stalled_close
    alarm._retire_session(alarm._session)
    opened = AsyncMock()
    monkeypatch.setattr(alarm, "_connect_locked", opened)
    with pytest.raises(SessionCleanupError):
        await alarm.connect()
    assert not alarm.is_connected and not alarm.reconnect_allowed
    assert alarm._cleanup_task is not None and not alarm._cleanup_task.done()
    opened.assert_not_awaited()
    release.set()
    await alarm._cleanup_task
    with pytest.raises(ConnectionError):
        await alarm.connect()
    await alarm.start()
    assert events == ["close"]
    opened.assert_awaited_once()


@pytest.mark.asyncio
async def test_stale_controls_never_start_or_write(real_alarm):
    core = real_alarm._pai
    core.control_partition = AsyncMock(return_value=True)
    core.control_zone = AsyncMock(return_value=True)
    real_alarm._last_panel_status_at -= 31
    with pytest.raises(ConnectionError):
        await real_alarm.disarm("0000")
    with pytest.raises(ConnectionError):
        await real_alarm.bypass_zone(16)
    core.control_partition.assert_not_awaited()
    core.control_zone.assert_not_awaited()
    core.connection.write.assert_not_called()


@pytest.mark.asyncio
async def test_stale_initial_websocket_sends_empty_disconnected(real_alarm, monkeypatch):
    import paradox_bridge.main as main

    real_alarm._last_panel_status_at -= 31
    manager = SimpleNamespace(connect=AsyncMock(), send=AsyncMock(return_value=False), active_count=1)
    auth = SimpleNamespace(decode_token=lambda token: {"sub": "test"})
    monkeypatch.setattr(main, "get_alarm", lambda: real_alarm)
    monkeypatch.setattr(main, "get_auth", lambda: auth)
    monkeypatch.setattr(main, "get_ws", lambda: manager)
    await main.websocket_endpoint(Mock(), "test-token")
    assert manager.send.await_args.args[1] == {
        "type": "status", "partitions": [], "connected": False, "events": [],
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("connect_elapsed", [45.0, 59.0])
async def test_slow_full_connect_starts_initial_poll_grace_after_success(monkeypatch, connect_elapsed):
    import paradox_bridge.alarm as module

    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    clock = [100.0]
    monkeypatch.setattr(module, "time", SimpleNamespace(monotonic=lambda: clock[0]))
    monkeypatch.setitem(sys.modules, "paradox.config", SimpleNamespace(config=SimpleNamespace()))
    monkeypatch.setitem(sys.modules, "paradox.lib.encodings", SimpleNamespace(register_encodings=lambda: None))
    monkeypatch.setattr(module, "PanelSession", lambda *args, **kwargs: PanelSession(
        *args, **kwargs, started_at=clock[0],
    ))
    created = []

    def create(session, *args):
        core = Core(session)
        _cleanup_core(core, [])

        async def full_connect():
            clock[0] += connect_elapsed
            return True

        core.full_connect = full_connect
        core.loop = asyncio.Event().wait
        created.append(core)
        return core

    monkeypatch.setattr(module, "create_session_pai", create)
    try:
        await alarm.connect()
        completed_connect_at = clock[0]
        clock[0] += 10
        assert not alarm.needs_reconnect
        assert alarm._session.started_at == completed_connect_at
        assert alarm._last_panel_status_at is None and not alarm.is_connected
        with pytest.raises(ConnectionError):
            await alarm.disarm("0000")
        created[0].connection.write.assert_not_called()
        clock[0] = completed_connect_at + 29.9
        assert not alarm.needs_reconnect
        clock[0] = completed_connect_at + 30
        assert alarm.needs_reconnect
        assert alarm._last_panel_status_at is None
    finally:
        clock[0] = time.monotonic()
        await alarm.disconnect()


def test_production_pai_dependency_is_pinned():
    metadata = tomllib.loads((Path(__file__).parents[1] / "pyproject.toml").read_text())
    assert metadata["project"]["optional-dependencies"]["pi"] == ["paradox-alarm-interface==3.7.0"]


@pytest.mark.asyncio
async def test_wrong_pai_version_keeps_service_unavailable_without_session_or_uart(monkeypatch):
    import paradox_bridge.pai_adapter as adapter

    monkeypatch.setattr(adapter, "version", lambda name: "3.8.0")
    monkeypatch.setitem(sys.modules, "paradox.config", SimpleNamespace(config=SimpleNamespace()))
    monkeypatch.setitem(sys.modules, "paradox.lib.encodings", SimpleNamespace(register_encodings=lambda: None))
    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    for _ in range(2):
        with pytest.raises(ImportError, match="3.7.0"):
            await alarm.connect()
        assert not alarm.is_connected
        assert alarm._pai is None and alarm._session is None
        assert alarm._cleanup_task is None and not alarm._cleanup_failed
