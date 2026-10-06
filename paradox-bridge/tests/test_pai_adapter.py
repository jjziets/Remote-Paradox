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


@pytest.mark.asyncio
@pytest.mark.parametrize("topics", [
    ("definitons_loaded",), ("definitions_loaded",),
    ("definitons_loaded", "definitions_loaded", "definitions_loaded"),
])
async def test_actual_constructor_reconciles_only_its_definition_subscriptions(monkeypatch, topics):
    pytest.importorskip("paradox.paradox")
    from paradox.lib import ps
    from paradox.paradox import Paradox

    original_init = Paradox.__init__
    loop = asyncio.get_running_loop()
    original_handler = loop.get_exception_handler()
    foreign = Mock()
    for topic in ("definitons_loaded", "definitions_loaded"):
        ps.subscribe(foreign, topic)
    before = sum(len(listeners) for listeners in ps.pub.listeners.values())

    def upstream_variant(core, *args, **kwargs):
        original_init(core, *args, **kwargs)
        ps.pub.unsubscribe(core._on_definitions_load, ps.PREFIX + "definitons_loaded")
        for topic in topics:
            ps.subscribe(core._on_definitions_load, topic)

    monkeypatch.setattr(Paradox, "__init__", upstream_variant)
    session = PanelSession(lambda: True, lambda: False)
    core = None
    try:
        core = create_session_pai(session, "/dev/TEST-NEVER-OPEN", 9600, Mock(), Mock())
        assert core._connection is None  # constructor must never allocate/open UART
        for topic in ("definitons_loaded", "definitions_loaded"):
            callbacks = [listener.callback for listener in ps.pub.listeners[ps.PREFIX + topic]]
            assert callbacks.count(foreign) == 1
            assert callbacks.count(core._on_definitions_load) == (topic == "definitions_loaded")
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before + 5
        core.detach()
        core.detach()  # removal is instance-local and idempotent too
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before
    finally:
        if core is not None:
            core.detach()
        for topic in ("definitons_loaded", "definitions_loaded"):
            ps.pub.unsubscribe(foreign, ps.PREFIX + topic)
        loop.set_exception_handler(original_handler)


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["base-constructor", "adapter-subscribe"])
async def test_actual_constructor_failure_removes_listeners_and_empty_owner_can_retry(monkeypatch, phase):
    pytest.importorskip("paradox.paradox")
    from paradox.lib import ps
    from paradox.paradox import Paradox
    from paradox.connections.serial_connection import SerialCommunication

    original_init, original_subscribe = Paradox.__init__, ps.subscribe
    loop = asyncio.get_running_loop()
    original_handler = loop.get_exception_handler()
    before = sum(len(listeners) for listeners in ps.pub.listeners.values())
    uart_init = Mock(side_effect=AssertionError("Constructor failure must not allocate UART"))
    monkeypatch.setattr(SerialCommunication, "__init__", uart_init)

    def fail_after_base(core, *args, **kwargs):
        original_init(core, *args, **kwargs)
        raise ValueError("injected constructor failure")

    def fail_subscribe(listener, topic, **kwargs):
        if topic == "definitions_loaded":
            raise ValueError("injected constructor failure")
        original_subscribe(listener, topic, **kwargs)

    if phase == "base-constructor":
        monkeypatch.setattr(Paradox, "__init__", fail_after_base)
    else:
        monkeypatch.setattr(ps, "subscribe", fail_subscribe)
    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    try:
        with pytest.raises(ValueError, match="injected constructor failure"):
            await alarm.connect()
        assert alarm._session is None and alarm._pai is None
        assert not alarm._cleanup_failed and alarm.reconnect_allowed
        assert not alarm.is_connected and alarm._last_panel_status_at is None
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before
        assert loop.get_exception_handler() is original_handler
        uart_init.assert_not_called()
        await alarm.disconnect()

        monkeypatch.setattr(Paradox, "__init__", original_init)
        monkeypatch.setattr(ps, "subscribe", original_subscribe)
        monkeypatch.setattr(Paradox, "full_connect", AsyncMock(return_value=True))
        await alarm.connect()
        assert alarm._session.active and alarm._session.pai is alarm._pai
        assert alarm._pai._connection is None and not alarm.is_connected
        uart_init.assert_not_called()
    finally:
        await alarm.disconnect()
        loop.set_exception_handler(original_handler)
    assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("close_fails", [False, True])
async def test_actual_partial_constructor_uart_remains_owned_until_safe_cleanup(monkeypatch, close_fails):
    pytest.importorskip("paradox.paradox")
    from paradox.lib import ps
    from paradox.paradox import Paradox

    original_init = Paradox.__init__
    loop = asyncio.get_running_loop()
    original_handler = loop.get_exception_handler()
    before = sum(len(listeners) for listeners in ps.pub.listeners.values())
    order = []
    fake = SimpleNamespace(connected=True, _protocol=object(), write=Mock())

    async def close():
        order.append("uart-close")
        assert order == ["construct", "uart-close"]
        if close_fails:
            raise OSError("injected UART close failure")
        fake.connected = False
        fake._protocol = None

    fake.close = AsyncMock(side_effect=close)

    def partially_construct(core, *args, **kwargs):
        original_init(core, *args, **kwargs)
        order.append("construct")
        if len(order) == 1:
            core._connection = fake
            raise ValueError("injected allocated-handle failure")

    monkeypatch.setattr(Paradox, "__init__", partially_construct)
    monkeypatch.setattr(Paradox, "full_connect", AsyncMock(return_value=True))
    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    try:
        with pytest.raises(ValueError, match="allocated-handle failure"):
            await alarm.connect()
        owner = alarm._session
        assert owner.pai is alarm._pai and alarm._pai._connection is fake
        assert owner.invalidated and not alarm.is_connected
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before
        if close_fails:
            with pytest.raises(SessionCleanupError):
                await alarm.connect()
            assert alarm._session is owner and alarm._pai is owner.pai
            assert alarm._cleanup_failed and not alarm.reconnect_allowed
            with pytest.raises(ConnectionError):
                await alarm.connect()
            assert order == ["construct", "uart-close"]
        else:
            await alarm.connect()
            assert order == ["construct", "uart-close", "construct"]
            assert alarm._session is not owner and alarm._session.active
        fake.write.assert_called_once()  # exact lifecycle close, never replayed
    finally:
        if close_fails:
            # Test-only requalification after fixing the synthetic close failure.
            async def safe_close():
                fake.connected = False
                fake._protocol = None
            fake.close.side_effect = safe_close
            alarm._cleanup_failed = False
            alarm._cleanup_task = None
        await alarm.disconnect()
        loop.set_exception_handler(original_handler)
    fake.write.assert_called_once()
    assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("corrected_topic", [False, True])
async def test_actual_boot_memory_and_every_ram_block_over_owned_raw_receive(monkeypatch, corrected_topic):
    pytest.importorskip("paradox.paradox")
    from contextvars import Context
    from paradox.config import config as cfg
    from paradox.connections.serial_connection import SerialCommunication
    from paradox.hardware import parsers as generic
    from paradox.hardware.common import ProductIdEnum
    from paradox.hardware.spectra_magellan import parsers
    from paradox.lib import ps
    from paradox.paradox import Paradox

    loop = asyncio.get_running_loop()
    original_handler = loop.get_exception_handler()
    original_init = Paradox.__init__
    before = sum(len(listeners) for listeners in ps.pub.listeners.values())
    if corrected_topic:
        def corrected_init(core, *args, **kwargs):
            original_init(core, *args, **kwargs)
            ps.pub.unsubscribe(core._on_definitions_load, ps.PREFIX + "definitons_loaded")
            ps.subscribe(core._on_definitions_load, "definitions_loaded")
        monkeypatch.setattr(Paradox, "__init__", corrected_init)
    monkeypatch.setattr(cfg, "LIMITS", {})
    monkeypatch.setattr(cfg, "SYNC_TIME", False)
    eeprom = bytearray(0x1000)
    eeprom[0x730:0x733] = bytes([8, 1, 0])  # actual instant-zone definition, partition 1
    eeprom[0x10:0x15] = b"Entry"
    eeprom[0x310:0x314] = b"Home"
    ram = {i: bytearray(parser.sizeof()) for i, parser in parsers.RAMDataParserMap.items()}
    ram[0][5:11] = bytes([20, 26, 10, 6, 7, 30])  # valid DateAdapter input
    ram[0][15] = 1  # zone 1 open in actual StatusAdapter encoding
    requested, writes, lifecycle, held = [], [], [], []
    final_requested = asyncio.Event()

    def packet(parser, fields):
        # RawCopy builds checksum-bearing wire packets; parsing is entirely PAI's.
        return parser.build({"fields": {"data": bytes(fields)}})

    initiate = bytearray(36)
    initiate[:2] = bytes([0x72, 0xFF])
    initiate[-8:] = b"SP6000  "
    start = bytearray(36)
    start[4] = ProductIdEnum.build("SPECTRA_SP6000")[0]
    start[5] = 6
    authenticate = bytearray(36)
    authenticate[0] = 0x10
    handshake = [packet(generic.InitiateCommunicationResponse, initiate),
                 packet(generic.StartCommunicationResponse, start),
                 packet(parsers.InitializeCommunicationResponse, authenticate)]

    class Protocol:
        def __init__(self, serial):
            self.serial = serial
            self.active = True

        def is_active(self):
            return self.active

        def variable_message_length(self, mode):
            pass

        def send_message(self, message):
            writes.append(message)
            if handshake:
                response = handshake.pop(0)
            elif message[0] == 0x70:
                lifecycle.append("panel-close")
                return
            else:
                assert message[0] == 0x50  # boot issues reads only, never controls
                address = parsers.ReadEEPROM.parse(message).fields.value.address
                if address >= 0x8000:
                    address -= 0x8000
                    requested.append(address)
                    response = packet(parsers.ReadStatusResponse,
                                      bytes([0x50, 0, 0x80, address]) + ram[address])
                    if address == max(ram):
                        held.append((self.serial, response))
                        final_requested.set()
                        return
                else:
                    response = packet(parsers.ReadEEPROMResponse,
                                      bytes([0x50, 0]) + address.to_bytes(2, "big")
                                      + eeprom[address:address + 32])
            # Real serial callbacks do not carry a control/publisher context.
            loop.call_soon(self.serial.on_message, response, context=Context())

        async def close(self):
            lifecycle.append("uart-close")
            self.active = False

    async def serial_connect(serial):
        lifecycle.append("open-double")
        serial._protocol = Protocol(serial)
        serial.connected = True
        return True

    monkeypatch.setattr(SerialCommunication, "connect", serial_connect)
    alarm = AlarmService("/dev/TEST-NEVER-OPEN", 9600, "0000")
    callback = Mock()
    alarm.set_status_change_callback(callback)
    try:
        await alarm.connect()
        await asyncio.wait_for(final_requested.wait(), 2)
        assert requested == list(parsers.RAMDataParserMap)
        assert alarm._last_panel_status_at is None and not alarm.is_connected
        callback.assert_not_called()  # six parsed blocks are not a full poll
        storage = alarm._pai.storage
        assert storage.get_container("zone")[1]["definition"] == "instant"
        assert storage.get_container("zone")[1]["key"] == "Entry"
        assert storage.get_container("partition")[1]["label"] == "Home"
        serial, response = held.pop()
        loop.call_soon(serial.on_message, response, context=Context())

        async def ready():
            while not alarm.is_connected:
                await asyncio.sleep(0)
        await asyncio.wait_for(ready(), 1)
        assert alarm._last_panel_status_at is not None
        status = alarm.get_status()
        assert status.partitions[0].name == "Home"
        assert status.partitions[0].mode == "disarmed"
        assert status.partitions[0].zones[0].name == "Entry"
        assert status.partitions[0].zones[0].open
        callback.assert_called()
        assert lifecycle == ["open-double"]
        assert not handshake
        await alarm.disconnect()
        assert lifecycle == ["open-double", "panel-close", "uart-close"]
        assert writes[-1] == parsers.CloseConnection.build({})
    finally:
        await alarm.disconnect()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        assert sum(len(listeners) for listeners in ps.pub.listeners.values()) == before
        loop.set_exception_handler(original_handler)


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
