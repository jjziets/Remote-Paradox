"""Alarm service — wraps PAI to provide status, arm, and disarm."""

import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Optional


@dataclass
class ZoneInfo:
    id: int
    name: str
    open: bool
    bypassed: bool = False
    partition_id: int = 1


@dataclass
class PartitionStatus:
    id: int
    name: str
    armed: bool
    mode: str  # "away", "stay", "disarmed"
    zones: list[ZoneInfo] = field(default_factory=list)


@dataclass
class AlarmStatus:
    partitions: list[PartitionStatus] = field(default_factory=list)


# ── Demo zone definitions ──

_DEMO_INTERNAL_ZONES = [
    ZoneInfo(id=1, name="Main Bedroom", open=False, partition_id=1),
    ZoneInfo(id=2, name="Second Room", open=False, partition_id=1),
    ZoneInfo(id=3, name="Third Room", open=False, partition_id=1),
    ZoneInfo(id=4, name="Dressing Room", open=False, partition_id=1),
    ZoneInfo(id=5, name="Kitchen", open=False, partition_id=1),
    ZoneInfo(id=6, name="Studio", open=False, partition_id=1),
    ZoneInfo(id=7, name="Living Room", open=False, partition_id=1),
    ZoneInfo(id=8, name="Study", open=False, partition_id=1),
    ZoneInfo(id=9, name="Front Door", open=False, partition_id=1),
    ZoneInfo(id=10, name="Back Door", open=False, partition_id=1),
    ZoneInfo(id=11, name="Main Bedroom Door", open=False, partition_id=1),
    ZoneInfo(id=12, name="Hallway", open=False, partition_id=1),
]

_DEMO_EXTERNAL_ZONES = [
    ZoneInfo(id=33, name="Beam West", open=False, partition_id=2),
    ZoneInfo(id=34, name="Beam North", open=False, partition_id=2),
    ZoneInfo(id=35, name="Beam East", open=False, partition_id=2),
    ZoneInfo(id=36, name="Beam South", open=False, partition_id=2),
    ZoneInfo(id=37, name="Garage", open=False, partition_id=2),
    ZoneInfo(id=38, name="Wendy House", open=False, partition_id=2),
]


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
        self._lock = threading.Lock()

        # Per-partition arm state: {partition_id: {"armed": bool, "mode": str}}
        self._demo_partitions: dict[int, dict] = {
            1: {"armed": False, "mode": "disarmed", "name": "Internal"},
            2: {"armed": False, "mode": "disarmed", "name": "External"},
        }
        # Mutable zone state keyed by zone id
        self._demo_zones: dict[int, ZoneInfo] = {}
        self._demo_history: list[dict] = []

        if demo_mode:
            self._connected = True
            for z in _DEMO_INTERNAL_ZONES + _DEMO_EXTERNAL_ZONES:
                self._demo_zones[z.id] = ZoneInfo(
                    id=z.id, name=z.name, open=z.open,
                    bypassed=z.bypassed, partition_id=z.partition_id,
                )

    @property
    def is_connected(self) -> bool:
        return self._connected

    @property
    def demo_mode(self) -> bool:
        return self._demo_mode

    def _require_connection(self) -> None:
        if not self._connected and not self._demo_mode:
            raise ConnectionError("Not connected to alarm panel")

    # ── Zone lookup ──

    def _find_demo_zone(self, zone_id: int) -> ZoneInfo:
        z = self._demo_zones.get(zone_id)
        if z is None:
            raise ValueError(f"Zone {zone_id} not found")
        return z

    # ── Status ──

    def get_status(self) -> AlarmStatus:
        self._require_connection()
        if self._demo_mode:
            return self._demo_status()

        partitions = []
        for pid, pdata in self._pai.panel.get("partitions", {}).items():
            status = pdata.get("status", {})
            armed = status.get("armed", False)
            if armed and status.get("armed_stay", False):
                mode = "stay"
            elif armed:
                mode = "away"
            else:
                mode = "disarmed"
            partitions.append(PartitionStatus(
                id=pid, name=f"Partition {pid}", armed=armed, mode=mode, zones=[],
            ))

        raw_zones = self._pai.storage.zones.select()
        for z in raw_zones:
            zi = ZoneInfo(
                id=z["id"], name=z.get("label", f"Zone {z['id']}"),
                open=z.get("open", False),
            )
            for p in partitions:
                p.zones.append(zi)

        return AlarmStatus(partitions=partitions)

    def _demo_status(self) -> AlarmStatus:
        with self._lock:
            parts = []
            for pid, pdata in sorted(self._demo_partitions.items()):
                zones = [
                    ZoneInfo(id=z.id, name=z.name, open=z.open,
                             bypassed=z.bypassed, partition_id=z.partition_id)
                    for z in self._demo_zones.values()
                    if z.partition_id == pid
                ]
                zones.sort(key=lambda z: z.id)
                parts.append(PartitionStatus(
                    id=pid, name=pdata["name"],
                    armed=pdata["armed"], mode=pdata["mode"],
                    zones=zones,
                ))
            return AlarmStatus(partitions=parts)

    # ── Arm / Disarm ──

    def arm_away(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            with self._lock:
                p = self._demo_partitions.get(partition_id)
                if p is None:
                    return False
                p["armed"] = True
                p["mode"] = "away"
            return True
        return self._pai.control_partition(partition_id, "arm", code)

    def arm_stay(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            with self._lock:
                p = self._demo_partitions.get(partition_id)
                if p is None:
                    return False
                p["armed"] = True
                p["mode"] = "stay"
            return True
        return self._pai.control_partition(partition_id, "arm_stay", code)

    def disarm(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            with self._lock:
                p = self._demo_partitions.get(partition_id)
                if p is None:
                    return False
                p["armed"] = False
                p["mode"] = "disarmed"
            return True
        return self._pai.control_partition(partition_id, "disarm", code)

    # ── Zone bypass ──

    def bypass_zone(self, zone_id: int) -> bool:
        if self._demo_mode:
            with self._lock:
                z = self._find_demo_zone(zone_id)
                z.bypassed = True
            return True
        raise NotImplementedError("PAI bypass not yet implemented")

    def unbypass_zone(self, zone_id: int) -> bool:
        if self._demo_mode:
            with self._lock:
                z = self._find_demo_zone(zone_id)
                z.bypassed = False
            return True
        raise NotImplementedError("PAI unbypass not yet implemented")

    # ── Zone toggle (demo / debug) ──

    def set_zone_open(self, zone_id: int, is_open: bool) -> None:
        with self._lock:
            z = self._find_demo_zone(zone_id)
            z.open = is_open
            event = "opened" if is_open else "closed"
            self._demo_history.insert(0, {
                "zone_id": z.id,
                "zone_name": z.name,
                "partition_id": z.partition_id,
                "event": event,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            })

    # ── History ──

    def get_zone_history(self, limit: int = 50) -> list[dict]:
        with self._lock:
            return list(self._demo_history[:limit])

    # ── Zone listing (for CLI) ──

    def list_all_zones(self) -> list[dict]:
        with self._lock:
            result = []
            for z in sorted(self._demo_zones.values(), key=lambda x: x.id):
                pname = self._demo_partitions.get(z.partition_id, {}).get("name", "?")
                result.append({
                    "id": z.id,
                    "name": z.name,
                    "partition": pname,
                    "partition_id": z.partition_id,
                    "open": z.open,
                    "bypassed": z.bypassed,
                })
            return result

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
