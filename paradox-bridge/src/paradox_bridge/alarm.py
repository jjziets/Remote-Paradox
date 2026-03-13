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
from typing import Optional

from paradox_bridge.virtual_panel import VirtualPanel

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
    def __init__(
        self, serial_port: str, baud: int, pc_password: str, demo_mode: bool = False,
    ):
        self._serial_port = serial_port
        self._baud = baud
        self._pc_password = pc_password
        self._pai = None
        self._pai_loop_task: Optional[asyncio.Task] = None
        self._connected = False
        self._demo_mode = demo_mode
        self._panel: Optional[VirtualPanel] = None
        self._events: deque = deque(maxlen=_MAX_EVENTS)
        self._prev_zone_state: dict[int, dict] = {}
        self._prev_part_state: dict[int, dict] = {}
        self._on_status_change: Optional[StatusChangeCallback] = None
        self._status_changed = False

        if demo_mode:
            self._panel = VirtualPanel()
            self._panel.COMMAND_ACK_DELAY = 1.5  # realistic serial roundtrip
            self._connected = True

    def set_status_change_callback(self, cb: StatusChangeCallback) -> None:
        self._on_status_change = cb

    @property
    def is_connected(self) -> bool:
        if self._demo_mode:
            return self._connected
        if not self._pai:
            self._connected = False
            return False
        try:
            conn = getattr(self._pai, "connection", None)
            if conn is not None and not conn.connected:
                if self._connected:
                    logger.warning("PAI connection dropped (detected via health check)")
                self._connected = False
        except Exception:
            self._connected = False
        return self._connected

    @property
    def demo_mode(self) -> bool:
        return self._demo_mode

    @property
    def panel(self) -> Optional[VirtualPanel]:
        return self._panel

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
            parts.append(PartitionStatus(
                id=pid, name=p["label"],
                armed=p["arm"], mode=mode,
                entry_delay=p["entry_delay"],
                ready=p["ready_status"],
                zones=zones,
            ))
        return AlarmStatus(partitions=parts)

    # ── Partition control (maps to PAI commands) ──

    async def _pai_control_partition(self, partition_id: int, command: str) -> bool:
        try:
            return await self._pai.control_partition(str(partition_id), command)
        except ConnectionError:
            self._connected = False
            raise

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
        if self._demo_mode:
            return self._panel.control_zone(zone_id, "bypass")
        try:
            return await self._pai.control_zone(str(zone_id), "bypass")
        except ConnectionError:
            self._connected = False
            raise

    async def unbypass_zone(self, zone_id: int) -> bool:
        if self._demo_mode:
            return self._panel.control_zone(zone_id, "clear_bypass")
        try:
            return await self._pai.control_zone(str(zone_id), "clear_bypass")
        except ConnectionError:
            self._connected = False
            raise

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
        try:
            return await self._pai.control_partition(str(partition_id), "panic")
        except ConnectionError:
            self._connected = False
            raise

    # ── State change tracking (real mode event history) ──

    def _record_event(self, etype: str, label: str, prop: str, value: object) -> None:
        self._events.appendleft({
            "type": etype,
            "label": label,
            "property": prop,
            "value": str(value).lower() if isinstance(value, bool) else str(value),
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        })
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
            return self._panel.get_events(limit=limit)
        return list(self._events)[:limit]

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
        try:
            from paradox.config import config as pai_cfg
            from paradox.lib.encodings import register_encodings
            from paradox.paradox import Paradox
        except ImportError as e:
            raise ImportError(
                "paradox-alarm-interface not installed. "
                "Install with: pip install paradox-alarm-interface"
            ) from e

        register_encodings()

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

        self._pai = Paradox(retries=1)
        result = await self._pai.full_connect()
        if not result:
            self._pai = None
            raise ConnectionError(
                f"PAI failed to connect via {self._serial_port} at {self._baud} baud"
            )
        self._connected = True
        logger.info("Connected to alarm panel via %s", self._serial_port)

        self._pai_loop_task = asyncio.create_task(self._run_pai_loop())
        logger.info("PAI status polling loop started")

    def _pai_status_update_hook(self, status) -> None:
        """Called by PAI pubsub on every status update from the panel.
        Reads the latest state, detects changes, and fires the callback."""
        try:
            self._status_from_pai()
        except Exception:
            pass

    async def _run_pai_loop(self) -> None:
        """Run PAI's internal loop for status polling and keepalive."""
        try:
            from paradox.lib import ps
            ps.subscribe(self._pai_status_update_hook, "status_update")
            logger.info("Subscribed to PAI status_update pubsub")
        except Exception:
            logger.warning("Could not subscribe to PAI pubsub — push disabled")
        try:
            await self._pai.loop()
        except ConnectionError:
            logger.warning("PAI loop: connection lost")
        except asyncio.CancelledError:
            logger.info("PAI loop cancelled (shutdown)")
        except Exception:
            logger.exception("PAI loop unexpected error")
        finally:
            self._connected = False
            logger.info("PAI loop exited — connection marked as lost")
            try:
                from paradox.lib import ps
                ps.unsubscribe(self._pai_status_update_hook, "status_update")
            except Exception:
                pass

    async def disconnect(self) -> None:
        if self._demo_mode:
            self._connected = False
            return
        if self._pai_loop_task and not self._pai_loop_task.done():
            self._pai_loop_task.cancel()
            try:
                await self._pai_loop_task
            except asyncio.CancelledError:
                pass
            self._pai_loop_task = None
        if self._pai:
            try:
                await self._pai.disconnect()
            except Exception:
                pass
        self._connected = False
        self._pai = None
        self._prev_zone_state.clear()
        self._prev_part_state.clear()
        logger.info("Disconnected from alarm panel")
