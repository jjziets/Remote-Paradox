"""Exact PAI 3.7 qualification on its supported Python 3.11 runtime.

Serial objects are never connected. Wire writes and replies are test doubles.
"""

import asyncio
import sys
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from paradox_bridge.alarm import AlarmService
from paradox_bridge.pai_adapter import ControlOperation, PanelSession, control_operation, create_session_pai
from paradox_bridge.pai_adapter import SessionCleanupError


pytestmark = pytest.mark.skipif(sys.version_info >= (3, 12), reason="PAI 3.7 dependencies require Python <3.12")


@pytest.fixture
async def pai_alarm():
    pytest.importorskip("paradox.paradox")
    from paradox.hardware.spectra_magellan.panel import Panel
    from paradox.lib import ps

    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    session = PanelSession(lambda: alarm._session is session, lambda: alarm.is_connected,
                           status_lock=alarm._status_lock)
    alarm._session = session
    loop = asyncio.get_running_loop()
    old_handler = loop.get_exception_handler()
    core = create_session_pai(session, alarm._serial_port, 9600, alarm._full_poll_completed, alarm._retire_session)
    alarm._pai = session.pai = core
    fake = SimpleNamespace(connected=True, _protocol=object(), write=Mock(),
                           wait_for_message=AsyncMock(return_value=object()))

    async def close():
        fake.connected = False
        fake._protocol = None
    fake.close = AsyncMock(side_effect=close)
    core._connection = fake
    core.panel = Panel(core, SimpleNamespace(fields=SimpleNamespace(
        value=SimpleNamespace(firmware=SimpleNamespace(version=6)),
    )))
    core.storage.get_container("partition")[1] = {"id": 1, "key": 1, "label": "Test", "arm": False}
    core.storage.get_container("zone")[16] = {"id": 16, "key": 16, "partition": 1, "definition": "instant"}
    core.storage.get_container("user")[1] = {"id": 1, "key": 1}
    alarm._connected = True
    alarm._last_panel_status_at = time.monotonic()
    before = sum(len(listeners) for listeners in ps.pub.listeners.values())
    try:
        yield alarm
    finally:
        await alarm.disconnect()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before - 5
        loop.set_exception_handler(old_handler)


@pytest.mark.asyncio
@pytest.mark.parametrize("operation", ["disarm", "arm_away", "arm_stay", "arm_force", "bypass_zone", "unbypass_zone"])
async def test_actual_wrappers_lost_ack_one_write_false_and_retire(pai_alarm, operation):
    alarm = pai_alarm
    core = alarm._pai
    core.connection.wait_for_message.side_effect = TimeoutError
    if operation.endswith("zone"):
        result = await getattr(alarm, operation)(16)
    else:
        result = await getattr(alarm, operation)("0000")
    assert result is False
    core.connection.write.assert_called_once()
    assert not alarm.is_connected


@pytest.mark.asyncio
@pytest.mark.parametrize("operation", ["disarm", "bypass_zone"])
async def test_actual_control_ack_semantics_preserved(pai_alarm, operation):
    if operation == "bypass_zone":
        result = await pai_alarm.bypass_zone(16)
    else:
        result = await pai_alarm.disarm("0000")
    assert result is True
    pai_alarm._pai.connection.write.assert_called_once()
    assert pai_alarm.is_connected


@pytest.mark.asyncio
async def test_actual_safe_status_read_retries_still_five(pai_alarm):
    core = pai_alarm._pai
    core.connection.wait_for_message.side_effect = TimeoutError
    with pytest.raises(TimeoutError):
        await core.panel.request_status(0)
    assert core.connection.write.call_count == 5


@pytest.mark.asyncio
async def test_actual_wrapper_swallowed_cancellation_false_not_success(pai_alarm):
    pai_alarm.CONTROL_TIMEOUT = 0.01
    async def never(*args, **kwargs):
        await asyncio.Event().wait()
    pai_alarm._pai.connection.wait_for_message.side_effect = never
    assert await pai_alarm.disarm("0000") is False
    await asyncio.sleep(0)
    assert not pai_alarm.is_connected
    pai_alarm._pai.connection.write.assert_called_once()


@pytest.mark.asyncio
async def test_actual_full_poll_applies_before_freshness_and_no_global_refresh(pai_alarm):
    from paradox.lib import ps

    alarm, core = pai_alarm, pai_alarm._pai
    cb = Mock()
    alarm.set_status_change_callback(cb)
    alarm._connected = False
    alarm._last_panel_status_at = None

    async def part():
        assert control_operation.get() is None
        return {"partition_status": {1: {"arm": True}}}

    async def zone():
        return {"zone_status": {16: {"open": True}}}

    core.panel.get_status_requests = lambda: [part(), zone()]
    op = ControlOperation(alarm._session, time.monotonic() + 1)
    token = control_operation.set(op)
    try:
        await alarm._session.spawn(core.poll_once())
    finally:
        control_operation.reset(token)
    assert alarm.is_connected
    assert alarm.get_status().partitions[0].armed
    cb.assert_called_once()  # availability, even without a prior zone baseline
    completed = alarm._last_panel_status_at
    await ps.pub.sendMessage(ps.PREFIX + "status_update", status={"partition": {1: {"arm": False}}})
    assert alarm._last_panel_status_at == completed
    assert alarm.get_status().partitions[0].armed


@pytest.mark.asyncio
async def test_actual_owned_serial_receive_tasks_drained_and_retired_messages_ignored(pai_alarm):
    core, session = pai_alarm._pai, pai_alarm._session
    # Construct the owned UART class only; never call connect/open.
    fake = core._connection
    core._connection = None
    serial = core.connection
    started = asyncio.Event()

    async def receive(message):
        started.set()
        await asyncio.Event().wait()

    serial.raw_handler_registry.handle = AsyncMock(side_effect=receive)
    child = serial.schedule_raw_message_handling(b"test")
    await started.wait()
    assert child in session.tasks
    session.invalidated = True
    assert serial.schedule_raw_message_handling(b"late") is None
    await session.drain(time.monotonic() + 1)
    assert child.done()
    core._connection = fake


@pytest.mark.asyncio
async def test_actual_owner_disconnect_matches_upstream_close_packet_once(pai_alarm):
    from paradox.paradox import Paradox
    core = pai_alarm._pai
    baseline_write = Mock()
    baseline = SimpleNamespace(panel=core.panel, connection=SimpleNamespace(connected=True, write=baseline_write))
    Paradox._clean_session(baseline)
    await pai_alarm.disconnect()
    core._connection.write.assert_called_once_with(baseline_write.call_args.args[0])
    core._connection.close.assert_awaited_once()
    await pai_alarm.disconnect()
    core._connection.write.assert_called_once()


@pytest.mark.asyncio
async def test_actual_close_packet_after_all_control_poll_receive_tasks_drain(pai_alarm):
    alarm, core, session = pai_alarm, pai_alarm._pai, pai_alarm._session
    order = []
    started = asyncio.Event()
    release = asyncio.Event()
    running = set()

    async def old_work(kind):
        running.add(kind)
        if len(running) == 3:
            started.set()
        while not release.is_set():
            try:
                await release.wait()
            except asyncio.CancelledError:
                pass
        running.remove(kind)
        order.append("drained-" + kind)

    for kind in ("control", "poll", "receive"):
        session.spawn(old_work(kind))
    await started.wait()

    def write(packet):
        assert not running
        assert not any(not task.done() for task in session.tasks)
        assert session.invalidated
        assert not core.request_lock.locked()
        assert packet == core.panel.get_message("CloseConnection").build({})
        order.append("panel-close")

    original_close = core._connection.close.side_effect
    async def uart_close():
        assert order[-1] == "panel-close"
        order.append("uart-close")
        await original_close()

    core._connection.write.side_effect = write
    core._connection.close.side_effect = uart_close
    cleanup = asyncio.create_task(alarm.disconnect())
    try:
        await asyncio.sleep(0.01)
        core._connection.write.assert_not_called()
        core._connection.close.assert_not_awaited()
    finally:
        release.set()
        await cleanup
    assert set(order[:3]) == {"drained-control", "drained-poll", "drained-receive"}
    assert order[3:] == ["panel-close", "uart-close"]
    core._connection.write.assert_called_once()


@pytest.mark.asyncio
async def test_actual_unauthorized_late_disconnect_cannot_close_or_write(pai_alarm):
    core, session = pai_alarm._pai, pai_alarm._session
    session.invalidated = True
    core._clean_session()  # upstream finalizer hook has no lifecycle authority
    with pytest.raises(SessionCleanupError):
        await core.disconnect(deadline=time.monotonic() + 1)
    with pytest.raises(ConnectionError):
        await core.send_wait(message=b"late-control", reply_expected=4)
    core._connection.write.assert_not_called()
    core._connection.close.assert_not_awaited()


@pytest.mark.asyncio
async def test_actual_close_write_failure_is_not_replayed_and_uart_still_closes(pai_alarm):
    alarm, core = pai_alarm, pai_alarm._pai
    core._connection.write.side_effect = OSError("possibly sent")
    with pytest.raises(SessionCleanupError):
        await alarm.disconnect()
    core._connection.write.assert_called_once()
    core._connection.close.assert_awaited_once()
    assert alarm._session.close_write_attempt
    assert not alarm.reconnect_allowed and not alarm.is_connected
    assert alarm._session is not None
    # Explicit requalification may verify UART close, never repeat the packet.
    alarm._cleanup_failed = False
    alarm._cleanup_task = None
    await alarm.disconnect()
    core._connection.write.assert_called_once()


@pytest.mark.asyncio
async def test_actual_control_inflight_drained_before_close_packet(pai_alarm):
    alarm, core, session = pai_alarm, pai_alarm._pai, pai_alarm._session
    waiting, release = asyncio.Event(), asyncio.Event()
    order = []
    close_packet = core.panel.get_message("CloseConnection").build({})

    async def wait_for_ack(*args, **kwargs):
        waiting.set()
        while not release.is_set():
            try:
                await release.wait()
            except asyncio.CancelledError:
                pass
        order.append("control-drained")
        return object()

    def write(packet):
        if packet == close_packet:
            assert order == ["control-write", "control-drained"]
            assert not core.request_lock.locked()
            assert not any(not task.done() for task in session.tasks)
            assert alarm._control is None
            order.append("panel-close")
        else:
            assert session.active
            order.append("control-write")

    original_close = core._connection.close.side_effect
    async def uart_close():
        assert order[-1] == "panel-close"
        order.append("uart-close")
        await original_close()

    core._connection.wait_for_message.side_effect = wait_for_ack
    core._connection.write.side_effect = write
    core._connection.close.side_effect = uart_close
    command = asyncio.create_task(alarm.disarm("0000"))
    await waiting.wait()
    cleanup = asyncio.create_task(alarm.disconnect())
    try:
        await asyncio.sleep(0.01)
        assert order == ["control-write"]
        core._connection.close.assert_not_awaited()
        with pytest.raises(ConnectionError):
            await alarm.disarm("0000")
    finally:
        release.set()
        await cleanup
        assert await command is False
    assert order == ["control-write", "control-drained", "panel-close", "uart-close"]
    assert core._connection.write.call_count == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["deadline", "active", "request-lock", "pending", "replaced"])
async def test_actual_lifecycle_close_rechecks_owner_drain_and_deadline(pai_alarm, reason):
    core, session = pai_alarm._pai, pai_alarm._session
    session.invalidated = True
    session.cleanup_task = asyncio.current_task()
    deadline = time.monotonic() + 1
    original_current = session.current
    child = None
    if reason == "deadline":
        deadline = time.monotonic() - 1
    elif reason == "active":
        session.invalidated = False
    elif reason == "request-lock":
        await core.request_lock.acquire()
    elif reason == "pending":
        child = session.spawn(asyncio.Event().wait())
    else:
        session.current = lambda: False
    try:
        with pytest.raises(SessionCleanupError):
            await core.disconnect(deadline=deadline)
        core._connection.write.assert_not_called()
        core._connection.close.assert_not_awaited()
        assert not session.close_write_attempt
    finally:
        session.current = original_current
        session.invalidated = True
        if core.request_lock.locked():
            core.request_lock.release()
        if child is not None:
            child.cancel()
            await asyncio.gather(child, return_exceptions=True)


@pytest.mark.asyncio
async def test_runtime_pai_version_guard_before_adapter_construction(monkeypatch):
    import paradox_bridge.pai_adapter as module
    from paradox.paradox import Paradox
    constructor = Mock(side_effect=AssertionError("must not construct"))
    monkeypatch.setattr(Paradox, "__init__", constructor)
    monkeypatch.setattr(module, "version", lambda name: "3.8.0")
    session = PanelSession(lambda: True, lambda: True)
    with pytest.raises(ImportError, match="3.7.0"):
        create_session_pai(session, "/dev/TEST-NEVER-OPEN", 9600, Mock(), Mock())
    constructor.assert_not_called()


@pytest.mark.asyncio
async def test_pai_370_panic_parser_failure_preserved_without_writes(pai_alarm):
    from paradox.paradox import Paradox
    core = pai_alarm._pai
    # Baseline PAI uses 'partitions', but its parser requires 'partition'. This
    # reliability change must not silently change panic encoding/semantics.
    with pytest.raises(KeyError, match="partition"):
        await Paradox.send_panic(core, "1", "fire", "1")
    core.connection.write.assert_not_called()
    with pytest.raises(KeyError, match="partition"):
        await pai_alarm.send_panic(1, "fire")
    core.connection.write.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("accepted", [True, False])
async def test_actual_panic_wrapper_retry_fence_with_parser_double(pai_alarm, monkeypatch, accepted):
    from paradox.hardware.spectra_magellan import parsers
    builder = Mock()
    builder.build.return_value = b"synthetic-panic-not-a-panel-packet"
    monkeypatch.setattr(parsers, "SendPanicAction", builder)
    if not accepted:
        pai_alarm._pai.connection.wait_for_message.side_effect = TimeoutError
    assert await pai_alarm.send_panic(1, "fire") is accepted
    builder.build.assert_called_once_with({"fields": {"value": {
        "partitions": [1], "panic_type": "fire", "user_id": 1,
    }}})
    pai_alarm._pai.connection.write.assert_called_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("connect_elapsed", [0.0, 45.0, 59.0])
async def test_actual_full_connect_and_owned_uart_lifecycle_without_opening_device(monkeypatch, connect_elapsed):
    pytest.importorskip("paradox.paradox")
    import paradox.paradox as upstream
    from paradox.connections.serial_connection import SerialCommunication
    from paradox.hardware.panel import Panel as BasePanel
    from paradox.config import config as cfg
    from paradox.lib import ps
    import paradox_bridge.alarm as module

    # Simulate only the transport/handshake/memory. Use the actual full_connect,
    # owned serial class, message manager, pubsub and status conversion paths.
    writes, lifecycle = [], []
    clock = [time.monotonic()]
    monkeypatch.setattr(module, "time", SimpleNamespace(monotonic=lambda: clock[0]))

    class Protocol:
        def is_active(self):
            return True

        def send_message(self, message):
            writes.append(message)

        def variable_message_length(self, mode):
            pass

        async def close(self):
            lifecycle.append("close")

    async def serial_connect(serial):
        lifecycle.append("open-double")
        serial.connected = True
        serial._protocol = Protocol()
        return True

    replies = [
        SimpleNamespace(fields=SimpleNamespace(value=SimpleNamespace(
            label=b"SP6000", application=SimpleNamespace(version=6, revision=0, build=0),
            serial_number=b"0000",
        ))),
        SimpleNamespace(fields=SimpleNamespace(value=SimpleNamespace(product_id=1))),
    ]

    async def reply(*args, **kwargs):
        return replies.pop(0)

    class Panel:
        variable_message_length = True
        load_memory = BasePanel.load_memory

        def get_message(self, name):
            if name == "CloseConnection":
                from paradox.hardware import parsers
                return parsers.CloseConnection
            return SimpleNamespace(build=lambda value: b"synthetic-handshake")

        async def initialize_communication(self, password):
            return True

        async def load_definitions(self):
            clock[0] += connect_elapsed
            return {"zone": {16: {"key": 16, "id": 16, "definition": "instant", "partition": 1}}}

        async def load_labels(self):
            return {"partition": {1: {"key": 1, "id": 1, "label": "Test"}}}

        def get_status_requests(self):
            async def status():
                return {"partition_status": {1: {"arm": False}}}
            return [status()]

    monkeypatch.setattr(SerialCommunication, "connect", serial_connect)
    monkeypatch.setattr(SerialCommunication, "wait_for_message", reply)
    monkeypatch.setattr(upstream, "create_panel", lambda *args: Panel())
    monkeypatch.setattr(cfg, "SYNC_TIME", False)
    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    loop = asyncio.get_running_loop()
    old_handler = loop.get_exception_handler()
    before = sum(len(listeners) for listeners in ps.pub.listeners.values())
    try:
        await alarm.connect()
        assert alarm._session.started_at == clock[0]
        assert alarm._last_panel_status_at is None and not alarm.is_connected
        clock[0] += 10  # first monitor check after slow full_connect/memory load
        assert not alarm.needs_reconnect
        async def ready():
            while not alarm.is_connected:
                await asyncio.sleep(0)
        await asyncio.wait_for(ready(), 1)
        assert alarm.get_status().partitions[0].mode == "disarmed"
        assert alarm._pai.storage.get_container("zone")[16]["definition"] == "instant"
        assert lifecycle == ["open-double"]
        assert len(writes) == 2  # handshake only, no control writes
        await alarm.disconnect()
        assert lifecycle == ["open-double", "close"]
        assert not alarm.is_connected and alarm._session is None
        from paradox.hardware import parsers
        assert writes[2:] == [parsers.CloseConnection.build({})]
    finally:
        await alarm.disconnect()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before
        loop.set_exception_handler(old_handler)
