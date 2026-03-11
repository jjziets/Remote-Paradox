"""Alarm service — wraps PAI (real) or VirtualPanel (demo).

The API layer uses AlarmService exclusively. In demo mode it delegates
to VirtualPanel which simulates a Paradox SP6000 with identical boolean
states, timing, and behavior to the real PAI library.
"""

from dataclasses import dataclass, field
from typing import Optional

from paradox_bridge.virtual_panel import VirtualPanel


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
        self._connected = False
        self._demo_mode = demo_mode
        self._panel: Optional[VirtualPanel] = None

        if demo_mode:
            self._panel = VirtualPanel()
            self._panel.COMMAND_ACK_DELAY = 1.5  # realistic serial roundtrip
            self._connected = True

    @property
    def is_connected(self) -> bool:
        return self._connected

    @property
    def demo_mode(self) -> bool:
        return self._demo_mode

    @property
    def panel(self) -> Optional[VirtualPanel]:
        return self._panel

    def _require_connection(self) -> None:
        if not self._connected and not self._demo_mode:
            raise ConnectionError("Not connected to alarm panel")

    # ── Status ──

    def get_status(self) -> AlarmStatus:
        self._require_connection()
        if self._demo_mode:
            return self._status_from_virtual_panel()

        partitions = []
        for pid, pdata in self._pai.panel.get("partitions", {}).items():
            status = pdata.get("status", {})
            armed = status.get("armed", False)
            if armed and status.get("armed_stay", False):
                mode = "armed_home"
            elif armed:
                mode = "armed_away"
            else:
                mode = "disarmed"
            if status.get("exit_delay"):
                mode = "arming"
            if any([status.get("audible_alarm"), status.get("silent_alarm"),
                     status.get("fire_alarm")]):
                mode = "triggered"
            partitions.append(PartitionStatus(
                id=pid, name=f"Partition {pid}", armed=armed, mode=mode,
                entry_delay=status.get("entry_delay", False),
                ready=status.get("ready_status", True),
                zones=[],
            ))

        raw_zones = self._pai.storage.zones.select()
        for z in raw_zones:
            zi = ZoneInfo(
                id=z["id"], name=z.get("label", f"Zone {z['id']}"),
                open=z.get("open", False),
                bypassed=z.get("bypassed", False),
                alarm=z.get("alarm", False),
                was_in_alarm=z.get("was_in_alarm", False),
                tamper=z.get("tamper", False),
            )
            for p in partitions:
                p.zones.append(zi)

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

    def arm_away(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "arm")
        return self._pai.control_partition(partition_id, "arm", code)

    def arm_stay(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "arm_stay")
        return self._pai.control_partition(partition_id, "arm_stay", code)

    def arm_force(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "arm_force")
        return self._pai.control_partition(partition_id, "arm_force", code)

    def disarm(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            return self._panel.control_partition(partition_id, "disarm")
        return self._pai.control_partition(partition_id, "disarm", code)

    # ── Zone control (maps to PAI commands) ──

    def bypass_zone(self, zone_id: int) -> bool:
        if self._demo_mode:
            return self._panel.control_zone(zone_id, "bypass")
        return self._pai.control_zone(zone_id, "bypass")

    def unbypass_zone(self, zone_id: int) -> bool:
        if self._demo_mode:
            return self._panel.control_zone(zone_id, "clear_bypass")
        return self._pai.control_zone(zone_id, "clear_bypass")

    # ── Zone toggle (demo only — simulates physical sensor) ──

    def set_zone_open(self, zone_id: int, is_open: bool) -> None:
        if not self._demo_mode:
            raise RuntimeError("set_zone_open only available in demo mode")
        self._panel.set_zone_open(zone_id, is_open)
        self._panel.tick()

    # ── Panic (demo only — simulates keypad panic) ──

    def send_panic(self, partition_id: int, panic_type: str) -> bool:
        if not self._demo_mode:
            raise RuntimeError("send_panic only available in demo mode")
        return self._panel.send_panic(partition_id, panic_type)

    # ── Event history ──

    def get_zone_history(self, limit: int = 50) -> list[dict]:
        if self._demo_mode:
            return self._panel.get_events(limit=limit)
        return []

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
            from paradox.paradox import Paradox
        except ImportError as e:
            raise ImportError(
                "paradox-alarm-interface not installed. "
                "Install with: pip install paradox-alarm-interface"
            ) from e

        self._pai = Paradox()
        self._pai.connection_type = "Serial"
        self._pai.serial_port = self._serial_port
        self._pai.serial_baud = self._baud
        self._pai.pc_password = self._pc_password
        await self._pai.connect()
        self._connected = True

    async def disconnect(self) -> None:
        if self._demo_mode:
            self._connected = False
            return
        if self._pai:
            try:
                await self._pai.disconnect()
            except Exception:
                pass
        self._connected = False
        self._pai = None
