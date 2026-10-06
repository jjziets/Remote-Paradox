"""Session-owned PAI 3.7 adapter. No global library or transport patches.

The write fence belongs inside send_wait's request lock. Cancellation alone is
not a fence: PAI control wrappers deliberately swallow CancelledError.
"""

import asyncio
import logging
import time
from collections.abc import Callable, Iterable
from contextvars import ContextVar, copy_context
from dataclasses import dataclass, field
from importlib.metadata import version
from threading import RLock


logger = logging.getLogger(__name__)
control_operation = ContextVar("panel_control_operation", default=None)
_publisher_session = ContextVar("panel_publisher_session", default=None)
SUPPORTED_PAI_VERSION = "3.7.0"


class SessionCleanupError(ConnectionError):
    """Old work/UART was not proven closed; automatic replacement is held."""


@dataclass(eq=False)
class PanelSession:
    current: Callable[[], bool]
    fresh: Callable[[], bool]
    pai: object = None
    invalidated: bool = False
    tasks: set = field(default_factory=set)
    started_at: float = field(default_factory=time.monotonic)
    status_lock: object = field(default_factory=RLock)
    cleanup_task: asyncio.Task | None = None
    close_write_attempt: bool = False

    @property
    def active(self):
        return not self.invalidated and self.current()

    def spawn(self, coroutine, *, operation=None):
        # Poll/receive work must never inherit a control's one-write allowance.
        context = copy_context()
        context.run(control_operation.set, operation)
        context.run(_publisher_session.set, self)
        task = asyncio.create_task(coroutine, context=context)
        self.tasks.add(task)
        task.add_done_callback(self._finished)
        return task

    def _finished(self, task):
        self.tasks.discard(task)
        if not task.cancelled():
            task.exception()  # consume late failure even if supervisor returned

    async def drain(self, deadline):
        while pending := {task for task in self.tasks if not task.done()}:
            for task in pending:
                task.cancel()
            _, pending = await asyncio.wait(pending, timeout=max(0, deadline - time.monotonic()))
            if pending:
                raise SessionCleanupError("Old panel tasks did not drain; restart may be required")


@dataclass
class ControlOperation:
    session: PanelSession
    deadline: float
    invalidated: bool = False
    write_attempt: bool = False

    def valid(self):
        task = asyncio.current_task()
        return (not self.invalidated and self.session.active
                and time.monotonic() < self.deadline
                and not (task and task.cancelling()))


class SessionParadoxMixin:
    """Overrides only PAI's unsafe retry, polling and callback boundaries."""

    async def send_wait(self, message_type=None, args=None, message=None,
                        retries=5, timeout=None, reply_expected=None):
        if timeout is None:
            from paradox.config import config as cfg
            timeout = cfg.IO_TIMEOUT
        op = control_operation.get()
        if message is None and message_type is not None:
            message = message_type.build(dict(fields=dict(value=args)))
        attempts = 1 if op is not None else retries
        for attempt in range(attempts):
            async with self.request_lock:
                try:
                    self._write_checked(message, op)
                    if reply_expected is not None:
                        if isinstance(reply_expected, Callable):
                            check = reply_expected
                        elif isinstance(reply_expected, Iterable):
                            check = lambda m: any(m.fields.value.po.command == v for v in reply_expected)
                        else:
                            check = lambda m: m.fields.value.po.command == reply_expected
                        wait = timeout * 2
                        if op is not None:
                            wait = min(wait, max(0, op.deadline - time.monotonic()))
                        reply = await self.connection.wait_for_message(check, timeout=wait)
                        if not self.session.active or (op is not None and not op.valid()):
                            raise ConnectionError("Reply belongs to retired operation")
                        return reply
                except asyncio.TimeoutError:
                    if attempt + 1 == attempts:
                        raise
        return None

    def _write_checked(self, message, op):
        # Share the HTTP reader's lock so a concurrent freshness/retirement check
        # cannot interleave with validation, allowance consumption and UART write.
        with self.session.status_lock:
            task = asyncio.current_task()
            if (not self.session.active or not self.connection.connected
                    or (task and task.cancelling())):
                raise ConnectionError("Panel session retired")
            if op is not None:
                if (op.session is not self.session or not op.valid()
                        or not self.session.fresh() or op.write_attempt):
                    raise ConnectionError("Control write no longer eligible")
            if message is not None:
                if op is not None:
                    op.write_attempt = True  # write can raise after sending
                self.connection.write(message)

    async def poll_once(self):
        children = []
        try:
            for request in self.panel.get_status_requests():
                children.append(self.session.spawn(request))
            if not children:
                raise ConnectionError("Panel supplied no full-poll requests")
            results = await asyncio.gather(*children)
            if not self.session.active:
                return
            from paradox.lib.utils import deep_merge
            merged = deep_merge(*results, extend_lists=True, initializer={})
            # Apply on the owning loop before marking this *full* poll fresh.
            self._process_status(merged)
        finally:
            # gather does not cancel siblings after one child fails.
            for child in children:
                if not child.done():
                    child.cancel()

    async def loop(self):
        from paradox.config import config as cfg
        from paradox.data.enums import RunState

        token = control_operation.set(None)
        try:
            while self.session.active and self.run_state not in (RunState.STOP, RunState.ERROR):
                started = time.monotonic()
                if self.run_state == RunState.RUN:
                    async with self.busy:
                        await self.poll_once()
                delay = max(0, started + cfg.KEEP_ALIVE_INTERVAL - time.monotonic())
                try:
                    await asyncio.wait_for(self.loop_wait_event.wait(), delay)
                except asyncio.TimeoutError:
                    pass
                finally:
                    self.loop_wait_event.clear()
        finally:
            control_operation.reset(token)

    def _belongs_to_session(self):
        # A subscriber generation alone cannot identify a global publisher.
        return self.session.active and _publisher_session.get() is self.session

    def _process_status(self, raw_status):
        with self.session.status_lock:
            self._apply_full_status(raw_status)

    def _apply_full_status(self, raw_status):
        if not self._belongs_to_session():
            return
        from paradox.config import config as cfg, get_limits_for_type
        from paradox.parsers.status import convert_raw_status

        status = convert_raw_status(raw_status)
        for key in cfg.LIMITS:
            if key in status:
                limits = get_limits_for_type(key)
                if limits is not None:
                    status[key].filter(limits)
        self._on_status_update(status)
        self.on_full_poll(self.session)

    def _on_status_update(self, status):
        with self.session.status_lock:
            self._apply_status_update(status)

    def _apply_status_update(self, status):
        if not self._belongs_to_session():
            return
        # PAI's base method spawns untracked time-sync tasks; retain behavior but
        # explicitly own those tasks. A global status event never marks freshness.
        from paradox.config import config as cfg
        if "troubles" in status:
            self._process_trouble_statuses(status["troubles"])
        for kind, items in status.items():
            if kind != "troubles":
                for key, properties in items.items():
                    if isinstance(properties, (dict, list)):
                        self.storage.update_container_object(kind, key, properties)
        self._update_partition_states()
        if cfg.SYNC_TIME:
            self.session.spawn(self.sync_time())

    def _on_labels_load(self, data):
        with self.session.status_lock:
            if self._belongs_to_session():
                super()._on_labels_load(data)

    def _on_definitions_load(self, data):
        with self.session.status_lock:
            if self._belongs_to_session():
                super()._on_definitions_load(data)

    def _on_event(self, event):
        with self.session.status_lock:
            if self._belongs_to_session():
                super()._on_event(event)

    def _on_property_change(self, change):
        with self.session.status_lock:
            if self._belongs_to_session():
                super()._on_property_change(change)

    def handle_event_message(self, message=None):
        with self.session.status_lock:
            if self._belongs_to_session():
                super().handle_event_message(message)

    def handle_error_message(self, message):
        if not self._belongs_to_session():
            return
        if message.fields.value.message == "panel_not_connected":
            self.session.invalidated = True
            self.on_session_lost(self.session)
        else:
            super().handle_error_message(message)

    def on_connection_message(self, message):
        if self._belongs_to_session():
            super().on_connection_message(message)

    def request_status_refresh(self):
        if self.session.active:
            super().request_status_refresh()

    def _clean_session(self):
        # Upstream invokes this unfenced synchronous hook from arbitrary callers.
        # The exact close packet is sent only by the authorized cleanup task.
        return None

    def _check_cleanup_owner(self, deadline):
        session = self.session
        if (deadline is None or not session.invalidated or not session.current()
                or session.cleanup_task is not asyncio.current_task()
                or any(not task.done() for task in session.tasks)
                or self.request_lock.locked()):
            raise SessionCleanupError("Panel close requires the drained session's cleanup owner")
        if time.monotonic() >= deadline:
            raise SessionCleanupError("Panel cleanup deadline expired")

    async def disconnect(self, *, deadline=None):
        self._check_cleanup_owner(deadline)
        from paradox.data.enums import RunState
        self.run_state = RunState.STOP
        # Do not access the lazy property: cleanup must not create a connection.
        connection = self._connection
        if connection is None:
            return
        try:
            if connection.connected and not self.session.close_write_attempt:
                panel = self.panel
                if panel is None:
                    from paradox.hardware import create_panel
                    panel = create_panel(self)
                packet = panel.get_message("CloseConnection").build({})
                # There is no general retired-write permission or message input.
                # This is the sole upstream lifecycle packet, with no ACK/retry.
                with self.session.status_lock:
                    self._check_cleanup_owner(deadline)
                    self.session.close_write_attempt = True
                    connection.write(packet)
        finally:
            # A write may raise after it was sent. Never replay it, but always
            # attempt UART closure; the service retains failed cleanup ownership.
            await connection.close()

    @staticmethod
    def _unsubscribe_all(method, topic):
        from paradox.lib import ps
        while True:
            try:
                ps.pub.unsubscribe(method, ps.PREFIX + topic)
            except ValueError:
                return

    def detach(self):
        for method, topic in (
            (self._on_labels_load, "labels_loaded"),
            (self._on_definitions_load, "definitons_loaded"),
            (self._on_definitions_load, "definitions_loaded"),
            (self._on_status_update, "status_update"),
            (self._on_event, "events"), (self._on_property_change, "changes"),
        ):
            self._unsubscribe_all(method, topic)


def create_session_pai(session, serial_port, baud, on_full_poll, on_session_lost):
    installed = version("paradox-alarm-interface")
    if installed != SUPPORTED_PAI_VERSION:
        raise ImportError(f"Session adapter requires PAI {SUPPORTED_PAI_VERSION}; installed {installed}")
    from paradox.paradox import Paradox
    from paradox.connections.serial_connection import SerialCommunication
    from paradox.lib import ps

    class OwnedSerial(SerialCommunication):
        def schedule_raw_message_handling(self, message):
            if session.active:
                return session.spawn(self.raw_handler_registry.handle(message))

        def schedule_message_handling(self, message):
            if session.active:
                return session.spawn(self.handler_registry.handle(message))

    class SessionParadox(SessionParadoxMixin, Paradox):
        def __init__(self):
            self.session = session
            self.on_full_poll = on_full_poll
            self.on_session_lost = on_session_lost
            self._connection = None
            # Retain partial construction until callbacks and any UART are proven
            # safe. Assignment at the factory return would lose this ownership.
            session.pai = self
            loop = asyncio.get_running_loop()
            previous_handler = loop.get_exception_handler()
            try:
                super().__init__(retries=1)
                # Stock 3.7 misspells this topic; some installations correct it.
                # Reconcile only this callback, including duplicate registrations.
                for topic in ("definitons_loaded", "definitions_loaded"):
                    self._unsubscribe_all(self._on_definitions_load, topic)
                ps.subscribe(self._on_definitions_load, "definitions_loaded")
            except BaseException:
                session.invalidated = True
                try:
                    self.detach()
                except Exception:
                    logger.exception("Failed to detach partially constructed PAI")
                else:
                    if self._connection is None and not session.tasks:
                        session.pai = None
                loop.set_exception_handler(previous_handler)
                raise

        @property
        def connection(self):
            if self._connection is None:
                if not session.active:
                    raise ConnectionError("Panel session retired")
                self._connection = OwnedSerial(serial_port, baud)
                self._register_connection_handlers()
            return self._connection

    return SessionParadox()
