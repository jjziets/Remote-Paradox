"""Alarm service — wraps PAI (real) or VirtualPanel (demo).

The API layer uses AlarmService exclusively. In demo mode it delegates
to VirtualPanel which simulates a Paradox SP6000 with identical boolean
states, timing, and behavior to the real PAI library.
"""

import asyncio
import logging
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from threading import RLock
from typing import Optional

from paradox_bridge.virtual_panel import VirtualPanel
from paradox_bridge.diagnostics import emit, trace_partition_command, trace_panic_command, trace_zone_command
from paradox_bridge.pai_adapter import (
    ControlOperation, PanelSession, SessionCleanupError, control_operation, create_session_pai,
)

logger = logging.getLogger(__name__)

_MAX_EVENTS = 200

StatusChangeCallback = Callable[[], None]


@dataclass
class ZoneInfo:
    id: int
    name: str
    open: bool
    bypassed: bool = False
    partition_id: int = 1
    alarm: bool = False
    was_in_alarm: bool = False
    tamper: bool = False


@dataclass
class PartitionStatus:
    id: int
    name: str
    armed: bool
    mode: str       # disarmed | arming | armed_away | armed_home | triggered
    entry_delay: bool = False
    ready: bool = True
    zones: list[ZoneInfo] = field(default_factory=list)


@dataclass
class AlarmStatus:
    partitions: list[PartitionStatus] = field(default_factory=list)


class AlarmService:
    STATUS_MAX_AGE = 30.0  # PAI full polls normally complete every 10 seconds
    CONTROL_TIMEOUT = 10.0
    CLEANUP_TIMEOUT = 5.0
    CONNECT_TIMEOUT = 60.0

    def __init__(
        self, serial_port: str, baud: int, pc_password: str,
        demo_mode: bool = False, db=None,
    ):
        self._serial_port = serial_port
        self._baud = baud
        self._pc_password = pc_password
        self._pai = None
        self._pai_loop_task: Optional[asyncio.Task] = None
        self._connected = False
        self._last_panel_status_at: float | None = None
        self._session: PanelSession | None = None
        self._control: ControlOperation | None = None
        self._lifecycle_lock = asyncio.Lock()
        self._admin_stopped = False
        self._admin_epoch = 0
        self._cleanup_failed = False
        self._cleanup_task: asyncio.Task | None = None
        self._demo_mode = demo_mode
        self._db = db
        self._panel: Optional[VirtualPanel] = None
        self._events: deque = deque(maxlen=_MAX_EVENTS)
        self._prev_zone_state: dict[int, dict] = {}
        self._prev_part_state: dict[int, dict] = {}
        self._on_status_change: Optional[StatusChangeCallback] = None
        self._status_changed = False
        self._status_lock = RLock()
        self._action_user: Optional[str] = None
        self._action_device: Optional[str] = None
        self._action_context_time: float = 0.0

        if demo_mode:
            self._panel = VirtualPanel()
            self._panel.COMMAND_ACK_DELAY = 1.5  # realistic serial roundtrip
            self._connected = True

        if db is not None and not demo_mode:
            self._seed_events_from_db()

    def set_status_change_callback(self, cb: StatusChangeCallback) -> None:
        self._on_status_change = cb

    @property
    def is_connected(self) -> bool:
        if self._demo_mode:
            return self._connected
        with self._status_lock:
            if not self._connected:
                return False
            session = self._session
            try:
                valid = (self._pai is not None and session is not None and session.active
                         and bool(self._pai.connection.connected)
                         and self._last_panel_status_at is not None
                         and 0 <= time.monotonic() - self._last_panel_status_at < self.STATUS_MAX_AGE)
            except Exception:
                valid = False
            if not valid:
                self._retire_session(session)
        return self._connected

    @property
    def reconnect_allowed(self) -> bool:
        return not self._admin_stopped and not self._cleanup_failed

    @property
    def needs_reconnect(self) -> bool:
        if self.is_connected:
            return False
        session = self._session
        # A new session has not yet completed its first full poll. It remains
        # unavailable to APIs/controls, but gets the same bounded startup grace.
        return (session is None or not session.active
                or time.monotonic() - session.started_at >= self.STATUS_MAX_AGE)

    def _notify_availability(self, connected: bool) -> None:
        changed = self._connected != connected
        self._connected = connected
        if changed and self._on_status_change:
            try:
                self._on_status_change()
            except Exception:
                logger.exception("Availability callback failed")

    def _retire_session(self, session) -> None:
        with self._status_lock:
            if session is not None:
                session.invalidated = True
            if self._session is session:
                if self._control is not None and self._control.session is session:
                    self._control.invalidated = True
                self._notify_availability(False)

    @property
    def demo_mode(self) -> bool:
        return self._demo_mode

    @property
    def panel(self) -> Optional[VirtualPanel]:
        return self._panel

    def set_action_context(self, user: Optional[str] = None, device: Optional[str] = None) -> None:
        self._action_user = user
        self._action_device = device
        self._action_context_time = time.time()

    def clear_action_context(self) -> None:
        pass  # context auto-expires via _get_action_context

    def _get_action_context(self) -> tuple[Optional[str], Optional[str]]:
        if self._action_user and (time.time() - self._action_context_time < 15):
            return self._action_user, self._action_device
        return None, None

    def _require_connection(self) -> None:
        if not self.is_connected and not self._demo_mode:
            raise ConnectionError("Not connected to alarm panel")

    # ── Status ──

    def get_status(self) -> AlarmStatus:
        self._require_connection()
        if self._demo_mode:
            return self._status_from_virtual_panel()
        return self._status_from_pai()

    def _status_from_pai(self) -> AlarmStatus:
        # HTTP readers and PAI callbacks also update the event history.
        with self._status_lock:
            self._require_connection()
            return self._read_status_from_pai()

    def _read_status_from_pai(self) -> AlarmStatus:
        try:
            storage = self._pai.storage
        except Exception:
            self._connected = False
            raise ConnectionError("Lost access to panel storage")

        part_container = storage.get_container("partition")
        zone_container = storage.get_container("zone")

        partitions = []
        part_map = {}
        for pid, pdata in part_container.items():
            armed = pdata.get("arm", False)
            arm_stay = pdata.get("arm_stay", False)
            arm_sleep = pdata.get("arm_sleep", False)
            exit_delay = pdata.get("exit_delay", False)
            entry_delay = pdata.get("entry_delay", False)

            if exit_delay:
                mode = "arming"
            elif any([pdata.get("audible_alarm"), pdata.get("silent_alarm"),
                       pdata.get("fire")]):
                mode = "triggered"
            elif armed and (arm_stay or arm_sleep):
                mode = "armed_home"
            elif armed:
                mode = "armed_away"
            else:
                mode = "disarmed"

            ps = PartitionStatus(
                id=pid,
                name=pdata.get("label", f"Partition {pid}"),
                armed=armed,
                mode=mode,
                entry_delay=entry_delay,
                ready=pdata.get("ready_status", True),
                zones=[],
            )
            partitions.append(ps)
            part_map[pid] = ps

            self._track_partition_changes(pid, ps)

        for zid, zdata in zone_container.items():
            zone_def = zdata.get("definition", None)
            if zone_def == "disabled":
                continue

            zone_partition = zdata.get("partition", 0)
            if isinstance(zone_partition, int) and zone_partition > 3:
                continue

            zi = ZoneInfo(
                id=zid,
                name=zdata.get("label", f"Zone {zid}"),
                open=zdata.get("open", False),
                bypassed=zdata.get("bypassed", False),
                alarm=zdata.get("alarm", False),
                was_in_alarm=zdata.get("was_in_alarm", False),
                tamper=zdata.get("tamper", False),
                partition_id=zone_partition if isinstance(zone_partition, int) and zone_partition > 0 else 1,
            )
            self._track_zone_changes(zid, zi)

            assigned = False
            for pid, ps in part_map.items():
                if zone_partition == 0 or (isinstance(zone_partition, int) and zone_partition & (1 << (pid - 1))):
                    ps.zones.append(zi)
                    assigned = True
            if not assigned and partitions:
                partitions[0].zones.append(zi)

        if self._status_changed and self._on_status_change:
            self._status_changed = False
            try:
                self._on_status_change()
            except Exception:
                logger.exception("Status change callback failed")

        return AlarmStatus(partitions=partitions)

    def _status_from_virtual_panel(self) -> AlarmStatus:
        vp = self._panel
        vp.tick()
        parts = []
        for pid in sorted(vp.partitions):
            p = vp.partitions[pid]
            mode = vp.get_current_state(pid)
            zones = [
                ZoneInfo(
                    id=z["id"], name=z["label"], open=z["open"],
                    bypassed=z["bypassed"], partition_id=z["partition_id"],
                    alarm=z["alarm"], was_in_alarm=z["was_in_alarm"],
                    tamper=z["tamper"],
                )
                for z in sorted(vp.zones.values(), key=lambda x: x["id"])
                if z["partition_id"] == pid
            ]
            ps = PartitionStatus(
                id=pid, name=p["label"],
                armed=p["arm"], mode=mode,
                entry_delay=p["entry_delay"],
                ready=p["ready_status"],
                zones=zones,
            )
            self._track_partition_changes(pid, ps)
            for zi in zones:
                self._track_zone_changes(zi.id, zi)
            parts.append(ps)
        if self._status_changed and self._on_status_change:
            self._status_changed = False
            self._on_status_change()
        return AlarmStatus(partitions=parts)

    # ── Partition control (maps to PAI commands) ──

    async def _pai_control_partition(self, partition_id: int, command: str) -> bool:
        return await trace_partition_command(
            self, partition_id, command,
            lambda *args: self._supervise_control("control_partition", *args),
        )

    async def _supervise_control(self, method, *args) -> bool:
        self._require_connection()
        # Admission is synchronous on the API loop; never queue another control.
        if self._control is not None:
            emit("control_rejected", reason="busy")
            return False
        session = self._session
        op = ControlOperation(session, time.monotonic() + self.CONTROL_TIMEOUT)
        self._control = op

        async def send():
            token = control_operation.set(op)
            try:
                return await getattr(session.pai, method)(*args)
            finally:
                control_operation.reset(token)

        task = session.spawn(send(), operation=op)
        try:
            done, _ = await asyncio.wait({task}, timeout=max(0, op.deadline - time.monotonic()))
            if (not done or not op.valid()
                    or (task.cancelling() and not task.cancelled())):
                op.invalidated = True
                self._retire_session(session)
                task.cancel()
                emit("control_unconfirmed", reason="deadline_or_retired", write_attempt=op.write_attempt)
                return False
            accepted = bool(task.result())
            if not accepted and op.write_attempt:
                op.invalidated = True
                self._retire_session(session)
            return accepted
        except BaseException:
            # Invalidate BEFORE cancel, including when PAI swallows cancellation.
            op.invalidated = True
            self._retire_session(session)
            task.cancel()
            raise
        finally:
            op.invalidated = True
            if self._control is op:
                self._control = None

    async def arm_away(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "arm")
        return await self._pai_control_partition(partition_id, "arm")

    async def arm_stay(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "arm_stay")
        return await self._pai_control_partition(partition_id, "arm_stay")

    async def arm_force(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "arm_force")
        return await self._pai_control_partition(partition_id, "arm_force")

    async def disarm(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "disarm")
        return await self._pai_control_partition(partition_id, "disarm")

    # ── Zone control (maps to PAI commands) ──

    async def bypass_zone(self, zone_id: int) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_zone(zone_id, "bypass")
        return await trace_zone_command(self, zone_id, "bypass",
                                        lambda *args: self._supervise_control("control_zone", *args))

    async def unbypass_zone(self, zone_id: int) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_zone(zone_id, "clear_bypass")
        return await trace_zone_command(self, zone_id, "clear_bypass",
                                        lambda *args: self._supervise_control("control_zone", *args))

    # ── Zone toggle (demo only — simulates physical sensor) ──

    def set_zone_open(self, zone_id: int, is_open: bool) -> None:
        if not self._demo_mode:
            raise RuntimeError("set_zone_open only available in demo mode")
        self._panel.set_zone_open(zone_id, is_open)
        self._panel.tick()

    # ── Panic ──

    async def send_panic(self, partition_id: int, panic_type: str) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.send_panic(partition_id, panic_type)
        return await trace_panic_command(self, partition_id, panic_type,
                                         lambda *args: self._supervise_control("send_panic", *args))

    # ── State change tracking (real mode event history) ──

    def _record_event(self, etype: str, label: str, prop: str, value: object) -> None:
        val_str = str(value).lower() if isinstance(value, bool) else str(value)
        ts = time.strftime("%Y-%m-%dT%H:%M:%S")
        ctx_user, ctx_device = self._get_action_context() if etype == "partition" else (None, None)
        user = ctx_user
        device = ctx_device
        event = {
            "type": etype, "label": label, "property": prop,
            "value": val_str, "timestamp": ts,
        }
        if user:
            event["user"] = user
        if device:
            event["device"] = device
        self._events.appendleft(event)
        if self._db is not None:
            try:
                self._db.insert_event(etype, label, prop, val_str, ts, user=user, device=device)
            except Exception:
                logger.exception("Failed to persist event to database")
        self._status_changed = True

    def _track_zone_changes(self, zid: int, zi: ZoneInfo) -> None:
        prev = self._prev_zone_state.get(zid)
        if prev is None:
            self._prev_zone_state[zid] = {"open": zi.open, "alarm": zi.alarm, "bypassed": zi.bypassed, "tamper": zi.tamper}
            return
        for prop in ("open", "alarm", "bypassed", "tamper"):
            cur = getattr(zi, prop)
            if cur != prev.get(prop):
                self._record_event("zone", zi.name, prop, cur)
                prev[prop] = cur

    def _track_partition_changes(self, pid: int, ps: PartitionStatus) -> None:
        prev = self._prev_part_state.get(pid)
        if prev is None:
            self._prev_part_state[pid] = {"mode": ps.mode, "entry_delay": ps.entry_delay}
            return
        if ps.mode != prev.get("mode"):
            self._record_event("partition", ps.name, "mode", ps.mode)
            prev["mode"] = ps.mode
        if ps.entry_delay != prev.get("entry_delay"):
            self._record_event("partition", ps.name, "entry_delay", ps.entry_delay)
            prev["entry_delay"] = ps.entry_delay

    # ── Event history ──

    def get_zone_history(self, limit: int = 50) -> list[dict]:
        if self._demo_mode:
            panel_events = self._panel.get_events(limit=limit)
            enriched = list(self._events)[:limit]
            if enriched:
                merged = {(e["timestamp"], e["label"], e["property"]): e for e in panel_events}
                for e in enriched:
                    merged[(e["timestamp"], e["label"], e["property"])] = e
                return sorted(merged.values(), key=lambda e: e["timestamp"], reverse=True)[:limit]
            return panel_events
        if self._db is not None:
            rows = self._db.get_events(limit=limit)
            result = []
            for r in rows:
                e = {
                    "type": r["type"], "label": r["label"], "property": r["property"],
                    "value": r["value"], "timestamp": r["timestamp"],
                }
                user = r.get("user")
                device = r.get("device")
                if user:
                    e["user"] = user
                if device:
                    e["device"] = device
                result.append(e)
            return result
        return list(self._events)[:limit]

    def _seed_events_from_db(self) -> None:
        """Load recent events from DB into the in-memory deque on startup."""
        if self._db is None:
            return
        try:
            rows = self._db.get_events(limit=_MAX_EVENTS)
            for row in reversed(rows):
                self._events.appendleft({
                    "type": row["type"], "label": row["label"],
                    "property": row["property"], "value": row["value"],
                    "timestamp": row["timestamp"],
                })
        except Exception:
            logger.exception("Failed to seed events from database")

    # ── Zone listing (for CLI) ──

    def list_all_zones(self) -> list[dict]:
        if self._demo_mode:
            result = []
            for z in sorted(self._panel.zones.values(), key=lambda x: x["id"]):
                p = self._panel.partitions.get(z["partition_id"], {})
                result.append({
                    "id": z["id"],
                    "name": z["label"],
                    "partition": p.get("label", "?"),
                    "partition_id": z["partition_id"],
                    "open": z["open"],
                    "bypassed": z["bypassed"],
                    "type": z["type"],
                })
            return result
        return []

    # ── Connection ──

    async def connect(self) -> None:
        if self._demo_mode:
            self._connected = True
            return
        async with self._lifecycle_lock:
            if not self.reconnect_allowed:
                raise ConnectionError("Panel administratively stopped or cleanup unproven")
            if self.is_connected:
                return
            await self._disconnect_locked()
            await self._connect_locked()

    async def _connect_locked(self) -> None:
        try:
            from paradox.config import config as pai_cfg
            from paradox.lib.encodings import register_encodings
        except ImportError as e:
            raise ImportError(
                "paradox-alarm-interface not installed. "
                "Install with: pip install paradox-alarm-interface"
            ) from e

        register_encodings()
        self._last_panel_status_at = None
        emit("panel_connect_started")

        pai_cfg.SERIAL_PORT = self._serial_port
        pai_cfg.SERIAL_BAUD = self._baud
        pai_cfg.CONNECTION_TYPE = "Serial"
        if self._pc_password:
            pai_cfg.PASSWORD = self._pc_password

        logger.info(
            "PAI config: port=%s baud=%d password=%s",
            pai_cfg.SERIAL_PORT, pai_cfg.SERIAL_BAUD,
            "****" if pai_cfg.PASSWORD else "None",
        )

        session = PanelSession(lambda: self._session is session,
                               lambda: self.is_connected, status_lock=self._status_lock)
        self._session = session
        try:
            self._pai = session.pai = create_session_pai(
                session, self._serial_port, self._baud,
                self._full_poll_completed, self._retire_session,
            )
        except ImportError:
            # A rejected PAI version never owns a UART or a partially open session.
            self._retire_session(session)
            self._session = None
            self._pai = None
            raise
        task = session.spawn(self._pai.full_connect())
        try:
            done, _ = await asyncio.wait({task}, timeout=self.CONNECT_TIMEOUT)
            if not done or not session.active or self._admin_stopped or not task.result():
                raise ConnectionError("PAI failed to establish an active panel session")
        except BaseException:
            self._retire_session(session)
            task.cancel()
            # Keep session ownership until cleanup is proven by disconnect.
            raise
        # Handshake/memory loading has its own deadline. Only now does the
        # initial full-poll grace begin; this is not completed-poll freshness.
        session.started_at = time.monotonic()
        self._pai_loop_task = session.spawn(self._run_pai_loop(session))
        logger.info("PAI status polling loop started")

    def _full_poll_completed(self, session) -> None:
        with self._status_lock:
            if self._session is not session or not session.active or self._admin_stopped:
                return
            self._last_panel_status_at = time.monotonic()
            self._notify_availability(True)
            try:
                self._status_from_pai()
            except Exception as exc:
                emit("panel_status_error", error_type=type(exc).__name__)

    async def _run_pai_loop(self, session) -> None:
        """Run PAI's internal loop for status polling and keepalive."""
        try:
            await session.pai.loop()
        except ConnectionError:
            logger.warning("PAI loop: connection lost")
        except asyncio.CancelledError:
            logger.info("PAI loop cancelled (shutdown)")
        except Exception:
            logger.exception("PAI loop unexpected error")
        finally:
            self._retire_session(session)
            emit("panel_poll_stopped")
            logger.info("PAI loop exited — connection marked as lost")

    async def disconnect(self) -> None:
        if self._demo_mode:
            self._connected = False
            return
        async with self._lifecycle_lock:
            await self._disconnect_locked()

    async def _disconnect_locked(self) -> None:
        session = self._session
        self._retire_session(session)
        if self._cleanup_failed:
            raise SessionCleanupError("Panel cleanup unproven; explicit start/restart required")
        if session is not None:
            deadline = time.monotonic() + self.CLEANUP_TIMEOUT
            try:
                await session.drain(deadline)
                if self._cleanup_task is None:
                    session.pai.detach()
                    self._cleanup_task = asyncio.create_task(session.pai.disconnect(deadline=deadline))
                    session.cleanup_task = self._cleanup_task
                    self._cleanup_task.add_done_callback(
                        lambda task: task.exception() if not task.cancelled() else None,
                    )
                task = self._cleanup_task
                done, _ = await asyncio.wait({task}, timeout=max(0, deadline - time.monotonic()))
                if not done:
                    raise SessionCleanupError("Old UART close did not finish; restart may be required")
                task.result()
                connection = getattr(session.pai, "_connection", None)
                if connection is not None and (connection.connected or getattr(connection, "_protocol", None) is not None):
                    raise SessionCleanupError("Old UART was not proven closed")
            except BaseException as exc:
                # Never drop old references and then open another UART on failure.
                self._cleanup_failed = True
                emit("panel_cleanup_failed", restart_may_be_required=True)
                if not isinstance(exc, Exception) or isinstance(exc, SessionCleanupError):
                    raise
                raise SessionCleanupError("Old panel cleanup failed; restart may be required") from exc
            self._cleanup_task = None
        with self._status_lock:
            self._session = None
            self._pai = None
            self._pai_loop_task = None
            self._last_panel_status_at = None
            self._prev_zone_state.clear()
            self._prev_part_state.clear()
        logger.info("Disconnected from alarm panel")

    async def stop(self) -> None:
        # Set before waiting for the lock so in-flight connects cannot win a stop.
        self._admin_stopped = True
        self._admin_epoch += 1
        self._retire_session(self._session)
        await self.disconnect()

    async def start(self) -> None:
        epoch = self._admin_epoch
        async with self._lifecycle_lock:
            if epoch != self._admin_epoch:
                raise ConnectionError("Start superseded by an administrative stop")
            self._admin_stopped = False
            self._cleanup_failed = False  # explicit request permits rechecking drain/close
            if self._cleanup_task is not None and self._cleanup_task.done():
                if self._cleanup_task.cancelled() or self._cleanup_task.exception() is not None:
                    self._cleanup_task = None
            if self.is_connected:
                return
            await self._disconnect_locked()
            await self._connect_locked()
