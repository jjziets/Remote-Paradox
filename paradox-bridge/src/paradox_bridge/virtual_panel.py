"""VirtualPanel — simulates a Paradox SP6000 with PAI-compatible states.

Mirrors the real PAI's boolean partition/zone properties and computed states
so that switching from demo to real hardware requires zero code changes in
the API layer.

Partition properties (booleans):
    arm, arm_stay, exit_delay, entry_delay, audible_alarm, ready_status

Zone properties (booleans):
    open, bypassed, alarm, was_in_alarm, tamper

Zone types (SP6000):
    entry_exit  — doors: trigger entry delay before alarm
    instant     — PIRs, beams: trigger alarm immediately
    follower    — hallways: follows entry delay if active, else instant

Computed current_state (matches PAI _update_partition_states):
    disarmed | arming | armed_away | armed_home | triggered
"""

import threading
import time
from datetime import datetime, timezone


def _default_partition(label: str) -> dict:
    return {
        "label": label,
        "arm": False,
        "arm_stay": False,
        "exit_delay": False,
        "entry_delay": False,
        "audible_alarm": False,
        "ready_status": True,
    }


# zone_id, name, partition_id, type
_ZONE_DEFS = [
    (1,  "Main Bedroom",      1, "instant"),
    (2,  "Second Room",        1, "instant"),
    (3,  "Third Room",         1, "instant"),
    (4,  "Dressing Room",      1, "instant"),
    (5,  "Kitchen",            1, "instant"),
    (6,  "Studio",             1, "instant"),
    (7,  "Living Room",        1, "instant"),
    (8,  "Study",              1, "instant"),
    (9,  "Front Door",         1, "entry_exit"),
    (10, "Back Door",          1, "entry_exit"),
    (11, "Main Bedroom Door",  1, "entry_exit"),
    (12, "Hallway",            1, "follower"),
    (33, "Beam West",          2, "instant"),
    (34, "Beam North",         2, "instant"),
    (35, "Beam East",          2, "instant"),
    (36, "Beam South",         2, "instant"),
    (37, "Garage",             2, "instant"),
    (38, "Wendy House",        2, "instant"),
]


def _default_zone(zid: int, name: str, partition_id: int, ztype: str) -> dict:
    return {
        "id": zid,
        "label": name,
        "partition_id": partition_id,
        "type": ztype,
        "open": False,
        "bypassed": False,
        "alarm": False,
        "was_in_alarm": False,
        "tamper": False,
    }


class VirtualPanel:
    """Simulates a Paradox SP6000 alarm panel."""

    EXIT_DELAY_AWAY = 60   # seconds (real default)
    EXIT_DELAY_STAY = 30
    ENTRY_DELAY = 30
    BELL_TIMEOUT = 240     # 4 minutes
    COMMAND_ACK_DELAY = 0  # 0 = instant (for tests); set >0 for realism

    def __init__(self):
        self._lock = threading.Lock()

        self.partitions: dict[int, dict] = {
            1: _default_partition("Internal"),
            2: _default_partition("External"),
        }
        self.zones: dict[int, dict] = {}
        for zid, name, pid, ztype in _ZONE_DEFS:
            self.zones[zid] = _default_zone(zid, name, pid, ztype)

        self.events: list[dict] = []

        # Timer bookkeeping: key → expiry timestamp
        # Keys: "exit_delay_{pid}", "entry_delay_{pid}", "bell_{pid}"
        self._timers: dict[str, float] = {}

        # Tracks which mode we're arming into per partition
        self._target_mode: dict[int, str] = {}

        # Pending commands: list of (execute_at, callable)
        self._pending_commands: list[tuple[float, callable]] = []

    # ── Computed state (matches PAI _update_partition_states) ──

    def get_current_state(self, partition_id: int) -> str:
        p = self.partitions.get(partition_id)
        if p is None:
            return "unknown"

        if p["audible_alarm"]:
            return "triggered"

        if p["exit_delay"]:
            return "arming"

        if p["arm"]:
            if p["arm_stay"]:
                return "armed_home"
            return "armed_away"

        return "disarmed"

    # ── Command queue (simulates serial roundtrip delay) ──

    def _enqueue_or_run(self, fn) -> None:
        if self.COMMAND_ACK_DELAY > 0:
            self._pending_commands.append((time.time() + self.COMMAND_ACK_DELAY, fn))
        else:
            fn()

    def _process_pending_commands(self) -> None:
        now = time.time()
        remaining = []
        for execute_at, fn in self._pending_commands:
            if now >= execute_at:
                fn()
            else:
                remaining.append((execute_at, fn))
        self._pending_commands = remaining

    # ── Partition control (matches PAI control_partition commands) ──

    def control_partition(self, partition_id: int, command: str) -> bool:
        with self._lock:
            p = self.partitions.get(partition_id)
            if p is None:
                return False

            command = command.lower()

            if command == "arm":
                if not p["ready_status"]:
                    return False
                self._enqueue_or_run(lambda: self._apply_arm(partition_id, "away"))
                return True

            if command == "arm_stay":
                if not p["ready_status"]:
                    return False
                self._enqueue_or_run(lambda: self._apply_arm_stay(partition_id))
                return True

            if command == "arm_force":
                self._enqueue_or_run(lambda: self._apply_arm(partition_id, "away"))
                return True

            if command in ("disarm", "disarm_all"):
                self._enqueue_or_run(lambda: self._apply_disarm(partition_id))
                return True

            return False

    def _apply_arm(self, partition_id: int, target: str) -> None:
        p = self.partitions[partition_id]
        p["exit_delay"] = True
        self._target_mode[partition_id] = target
        self._timers[f"exit_delay_{partition_id}"] = time.time() + self.EXIT_DELAY_AWAY
        self._record_event("partition", p["label"], "exit_delay", True)

    def _apply_arm_stay(self, partition_id: int) -> None:
        p = self.partitions[partition_id]
        p["exit_delay"] = True
        p["arm_stay"] = True
        self._target_mode[partition_id] = "stay"
        self._timers[f"exit_delay_{partition_id}"] = time.time() + self.EXIT_DELAY_STAY
        self._record_event("partition", p["label"], "exit_delay", True)

    def _apply_disarm(self, partition_id: int) -> None:
        p = self.partitions[partition_id]
        p["arm"] = False
        p["arm_stay"] = False
        p["exit_delay"] = False
        p["entry_delay"] = False
        p["audible_alarm"] = False
        self._timers.pop(f"exit_delay_{partition_id}", None)
        self._timers.pop(f"entry_delay_{partition_id}", None)
        self._timers.pop(f"bell_{partition_id}", None)
        self._target_mode.pop(partition_id, None)
        for z in self.zones.values():
            if z["partition_id"] == partition_id:
                z["alarm"] = False
                z["was_in_alarm"] = False
        self._update_ready_status(partition_id)
        self._record_event("partition", p["label"], "disarm", True)

    # ── Zone control (matches PAI control_zone commands) ──

    def control_zone(self, zone_id: int, command: str) -> bool:
        with self._lock:
            z = self.zones.get(zone_id)
            if z is None:
                return False

            command = command.lower()

            if command == "bypass":
                self._enqueue_or_run(lambda: self._apply_bypass(zone_id, True))
                return True

            if command == "clear_bypass":
                self._enqueue_or_run(lambda: self._apply_bypass(zone_id, False))
                return True

            return False

    def _apply_bypass(self, zone_id: int, bypass: bool) -> None:
        z = self.zones[zone_id]
        z["bypassed"] = bypass
        self._update_ready_status(z["partition_id"])
        self._record_event("zone", z["label"], "bypassed", bypass)

    # ── Zone physical state (sensor simulation for demo) ──

    def set_zone_open(self, zone_id: int, is_open: bool) -> None:
        with self._lock:
            z = self.zones.get(zone_id)
            if z is None:
                raise ValueError(f"Zone {zone_id} not found")
            z["open"] = is_open
            self._record_event("zone", z["label"], "open", is_open)

    # ── Panic (matches PAI send_panic) ──

    def send_panic(self, partition_id: int, panic_type: str) -> bool:
        with self._lock:
            p = self.partitions.get(partition_id)
            if p is None:
                return False
            p["audible_alarm"] = True
            self._timers[f"bell_{partition_id}"] = time.time() + self.BELL_TIMEOUT
            self._record_event("partition", p["label"], "panic", panic_type)
            return True

    # ── Tick — advance timers (call periodically or after state changes) ──

    def tick(self) -> None:
        """Resolve all timers and process alarm logic. Thread-safe."""
        with self._lock:
            self._tick_locked()

    def _tick_locked(self) -> None:
        self._process_pending_commands()
        now = time.time()

        for pid, p in self.partitions.items():
            # Exit delay expiry → arm
            key = f"exit_delay_{pid}"
            if p["exit_delay"] and key in self._timers and now >= self._timers[key]:
                p["exit_delay"] = False
                p["arm"] = True
                del self._timers[key]
                self._record_event("partition", p["label"], "arm", True)

            # Entry delay expiry → audible alarm
            key = f"entry_delay_{pid}"
            if p["entry_delay"] and key in self._timers and now >= self._timers[key]:
                p["entry_delay"] = False
                p["audible_alarm"] = True
                del self._timers[key]
                self._timers[f"bell_{pid}"] = now + self.BELL_TIMEOUT
                # Mark the open entry zones as alarm
                for z in self.zones.values():
                    if z["partition_id"] == pid and z["open"] and not z["bypassed"]:
                        z["alarm"] = True
                        z["was_in_alarm"] = True
                self._record_event("partition", p["label"], "audible_alarm", True)

            # Bell timeout → silence
            key = f"bell_{pid}"
            if p["audible_alarm"] and key in self._timers and now >= self._timers[key]:
                p["audible_alarm"] = False
                del self._timers[key]
                self._record_event("partition", p["label"], "bell_finished", True)

            # Process zone alarm logic for armed partitions
            if p["arm"] and not p["exit_delay"]:
                self._process_zone_alarms(pid, p)

            # Update ready status
            self._update_ready_status(pid)

    def _process_zone_alarms(self, pid: int, p: dict) -> None:
        for z in self.zones.values():
            if z["partition_id"] != pid or not z["open"] or z["bypassed"] or z["alarm"]:
                continue

            ztype = z["type"]

            if ztype == "entry_exit":
                if not p["entry_delay"] and not p["audible_alarm"]:
                    p["entry_delay"] = True
                    self._timers[f"entry_delay_{pid}"] = time.time() + self.ENTRY_DELAY
                    self._record_event("partition", p["label"], "entry_delay", True)

            elif ztype == "follower":
                if p["entry_delay"]:
                    pass  # follows the existing entry delay
                else:
                    z["alarm"] = True
                    z["was_in_alarm"] = True
                    p["audible_alarm"] = True
                    self._timers[f"bell_{pid}"] = time.time() + self.BELL_TIMEOUT
                    self._record_event("zone", z["label"], "alarm", True)

            elif ztype == "instant":
                z["alarm"] = True
                z["was_in_alarm"] = True
                p["audible_alarm"] = True
                self._timers[f"bell_{pid}"] = time.time() + self.BELL_TIMEOUT
                self._record_event("zone", z["label"], "alarm", True)

    def _update_ready_status(self, partition_id: int) -> None:
        p = self.partitions.get(partition_id)
        if p is None:
            return
        ready = True
        for z in self.zones.values():
            if z["partition_id"] == partition_id and z["open"] and not z["bypassed"]:
                ready = False
                break
        p["ready_status"] = ready

    # ── Event log ──

    def _record_event(self, etype: str, label: str, prop: str, value) -> None:
        self.events.insert(0, {
            "type": etype,
            "label": label,
            "property": prop,
            "value": value,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        })

    def get_events(self, limit: int = 50) -> list[dict]:
        with self._lock:
            return list(self.events[:limit])

    # ── Test helpers (force timer expiry for deterministic tests) ──

    def _force_command_ack(self) -> None:
        """Instantly process all pending commands (bypasses COMMAND_ACK_DELAY)."""
        for _, fn in self._pending_commands:
            fn()
        self._pending_commands.clear()
        self._tick_locked()

    def _force_exit_delay_done(self, partition_id: int) -> None:
        key = f"exit_delay_{partition_id}"
        if key in self._timers:
            self._timers[key] = time.time() - 1
        self._tick_locked()

    def _force_entry_delay_done(self, partition_id: int) -> None:
        key = f"entry_delay_{partition_id}"
        if key in self._timers:
            self._timers[key] = time.time() - 1
        self._tick_locked()

    def _force_bell_timeout(self, partition_id: int) -> None:
        key = f"bell_{partition_id}"
        if key in self._timers:
            self._timers[key] = time.time() - 1
        self._tick_locked()
