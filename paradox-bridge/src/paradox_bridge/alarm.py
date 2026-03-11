"""Alarm service — wraps PAI to provide status, arm, and disarm."""

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ZoneInfo:
    id: int
    name: str
    open: bool


@dataclass
class AlarmStatus:
    armed: bool
    mode: str  # "away", "stay", "disarmed"
    zones: list[ZoneInfo] = field(default_factory=list)


_DEMO_ZONES = [
    ZoneInfo(id=1, name="Front Door", open=False),
    ZoneInfo(id=2, name="Kitchen Window", open=False),
    ZoneInfo(id=3, name="Garage", open=True),
    ZoneInfo(id=4, name="Back Door", open=False),
    ZoneInfo(id=5, name="Motion - Hall", open=False),
    ZoneInfo(id=6, name="Motion - Lounge", open=False),
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
        self._demo_armed = False
        self._demo_mode_str = "disarmed"
        if demo_mode:
            self._connected = True

    @property
    def is_connected(self) -> bool:
        return self._connected

    def _require_connection(self) -> None:
        if not self._connected and not self._demo_mode:
            raise ConnectionError("Not connected to alarm panel")

    def get_status(self, partition_id: int = 1) -> AlarmStatus:
        self._require_connection()
        if self._demo_mode:
            return AlarmStatus(
                armed=self._demo_armed,
                mode=self._demo_mode_str,
                zones=list(_DEMO_ZONES),
            )
        part = self._pai.panel["partitions"][partition_id]
        status = part["status"]
        armed = status.get("armed", False)
        if armed and status.get("armed_stay", False):
            mode = "stay"
        elif armed and status.get("armed_away", False):
            mode = "away"
        elif armed:
            mode = "away"
        else:
            mode = "disarmed"

        raw_zones = self._pai.storage.zones.select()
        zones = [
            ZoneInfo(id=z["id"], name=z.get("label", f"Zone {z['id']}"), open=z.get("open", False))
            for z in raw_zones
        ]
        return AlarmStatus(armed=armed, mode=mode, zones=zones)

    def arm_away(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            self._demo_armed = True
            self._demo_mode_str = "away"
            return True
        return self._pai.control_partition(partition_id, "arm", code)

    def arm_stay(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            self._demo_armed = True
            self._demo_mode_str = "stay"
            return True
        return self._pai.control_partition(partition_id, "arm_stay", code)

    def disarm(self, code: str, partition_id: int = 1) -> bool:
        self._require_connection()
        if self._demo_mode:
            self._demo_armed = False
            self._demo_mode_str = "disarmed"
            return True
        return self._pai.control_partition(partition_id, "disarm", code)

    async def connect(self) -> None:
        if self._demo_mode:
            self._connected = True
            return
        try:
            from paradox.paradox import Paradox
        except ImportError as e:
            raise ImportError(
                "paradox-alarm-interface not installed. Install with: pip install paradox-alarm-interface"
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
