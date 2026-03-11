"""Tests for VirtualPanel — simulates a Paradox SP6000 with PAI-compatible states.

Partition properties (booleans, same as PAI MQTT topics):
  arm, arm_stay, exit_delay, entry_delay, audible_alarm, ready_status

Zone properties (booleans, same as PAI MQTT topics):
  open, bypassed, alarm, was_in_alarm, tamper

Computed current_state (same as PAI _update_partition_states):
  disarmed, arming, armed_away, armed_home, triggered

Control commands (same as PAI MQTT control):
  Partition: arm, arm_stay, disarm
  Zone: bypass, clear_bypass
"""

import time

import pytest

from paradox_bridge.virtual_panel import VirtualPanel


class TestPanelInit:
    def test_two_partitions(self):
        vp = VirtualPanel()
        assert 1 in vp.partitions
        assert 2 in vp.partitions

    def test_partition_names(self):
        vp = VirtualPanel()
        assert vp.partitions[1]["label"] == "Internal"
        assert vp.partitions[2]["label"] == "External"

    def test_partitions_start_disarmed(self):
        vp = VirtualPanel()
        for pid, p in vp.partitions.items():
            assert p["arm"] is False
            assert p["arm_stay"] is False
            assert p["exit_delay"] is False
            assert p["entry_delay"] is False
            assert p["audible_alarm"] is False

    def test_zones_loaded(self):
        vp = VirtualPanel()
        assert len(vp.zones) > 0
        assert 1 in vp.zones   # Main Bedroom
        assert 9 in vp.zones   # Front Door
        assert 33 in vp.zones  # Beam West

    def test_zones_start_closed(self):
        vp = VirtualPanel()
        for zid, z in vp.zones.items():
            assert z["open"] is False
            assert z["bypassed"] is False
            assert z["alarm"] is False
            assert z["was_in_alarm"] is False
            assert z["tamper"] is False

    def test_zone_types(self):
        vp = VirtualPanel()
        assert vp.zones[9]["type"] == "entry_exit"   # Front Door
        assert vp.zones[10]["type"] == "entry_exit"   # Back Door
        assert vp.zones[1]["type"] == "instant"        # Main Bedroom (PIR)
        assert vp.zones[33]["type"] == "instant"       # Beam West
        assert vp.zones[12]["type"] == "follower"      # Hallway

    def test_ready_status_when_all_closed(self):
        vp = VirtualPanel()
        assert vp.partitions[1]["ready_status"] is True


class TestComputedState:
    """current_state must match PAI's _update_partition_states exactly."""

    def test_disarmed(self):
        vp = VirtualPanel()
        assert vp.get_current_state(1) == "disarmed"

    def test_arming_during_exit_delay(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        assert vp.get_current_state(1) == "arming"

    def test_armed_away_after_exit_delay(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        vp._force_exit_delay_done(1)
        assert vp.get_current_state(1) == "armed_away"
        assert vp.partitions[1]["arm"] is True

    def test_armed_home(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm_stay")
        vp._force_exit_delay_done(1)
        assert vp.get_current_state(1) == "armed_home"
        assert vp.partitions[1]["arm"] is True
        assert vp.partitions[1]["arm_stay"] is True

    def test_triggered_by_audible_alarm(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        vp._force_exit_delay_done(1)
        vp.partitions[1]["audible_alarm"] = True
        assert vp.get_current_state(1) == "triggered"

    def test_arming_overrides_armed(self):
        """exit_delay=True should show 'arming' even if arm=True (PAI behavior)."""
        vp = VirtualPanel()
        vp.partitions[1]["arm"] = True
        vp.partitions[1]["exit_delay"] = True
        assert vp.get_current_state(1) == "arming"


class TestControlPartition:
    def test_arm_sets_exit_delay(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        assert vp.partitions[1]["exit_delay"] is True
        assert vp.partitions[1]["arm"] is False  # not armed yet

    def test_arm_stay_sets_exit_delay(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm_stay")
        assert vp.partitions[1]["exit_delay"] is True
        assert vp.partitions[1]["arm_stay"] is True  # flag set immediately

    def test_disarm_clears_all(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        vp._force_exit_delay_done(1)
        vp.control_partition(1, "disarm")
        p = vp.partitions[1]
        assert p["arm"] is False
        assert p["arm_stay"] is False
        assert p["exit_delay"] is False
        assert p["entry_delay"] is False
        assert p["audible_alarm"] is False

    def test_disarm_cancels_exit_delay(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        assert vp.partitions[1]["exit_delay"] is True
        vp.control_partition(1, "disarm")
        assert vp.partitions[1]["exit_delay"] is False
        assert vp.get_current_state(1) == "disarmed"

    def test_disarm_clears_zone_alarm_flags(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        vp._force_exit_delay_done(1)
        vp.set_zone_open(9, True)
        vp.tick()
        vp.control_partition(1, "disarm")
        assert vp.zones[9]["alarm"] is False
        assert vp.zones[9]["was_in_alarm"] is False

    def test_other_partition_unaffected(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        assert vp.partitions[2]["exit_delay"] is False
        assert vp.get_current_state(2) == "disarmed"

    def test_arm_returns_false_for_unknown_partition(self):
        vp = VirtualPanel()
        assert vp.control_partition(99, "arm") is False

    def test_not_ready_prevents_arm(self):
        """Can't arm if a non-bypassed zone is open (ready_status=False)."""
        vp = VirtualPanel()
        vp.set_zone_open(9, True)
        vp.tick()
        assert vp.partitions[1]["ready_status"] is False
        assert vp.control_partition(1, "arm") is False

    def test_arm_force_overrides_not_ready(self):
        vp = VirtualPanel()
        vp.set_zone_open(9, True)
        vp.tick()
        assert vp.control_partition(1, "arm_force") is True
        assert vp.partitions[1]["exit_delay"] is True


class TestControlZone:
    def test_bypass(self):
        vp = VirtualPanel()
        vp.control_zone(1, "bypass")
        assert vp.zones[1]["bypassed"] is True

    def test_clear_bypass(self):
        vp = VirtualPanel()
        vp.control_zone(1, "bypass")
        vp.control_zone(1, "clear_bypass")
        assert vp.zones[1]["bypassed"] is False

    def test_bypass_unknown_zone(self):
        vp = VirtualPanel()
        assert vp.control_zone(999, "bypass") is False


class TestZoneOpenClose:
    def test_open_zone(self):
        vp = VirtualPanel()
        vp.set_zone_open(9, True)
        assert vp.zones[9]["open"] is True

    def test_close_zone(self):
        vp = VirtualPanel()
        vp.set_zone_open(9, True)
        vp.set_zone_open(9, False)
        assert vp.zones[9]["open"] is False

    def test_open_updates_ready_status(self):
        vp = VirtualPanel()
        vp.set_zone_open(9, True)
        vp.tick()
        assert vp.partitions[1]["ready_status"] is False

    def test_bypassed_zone_keeps_ready(self):
        vp = VirtualPanel()
        vp.control_zone(9, "bypass")
        vp.set_zone_open(9, True)
        vp.tick()
        assert vp.partitions[1]["ready_status"] is True

    def test_open_records_event(self):
        vp = VirtualPanel()
        vp.set_zone_open(9, True)
        assert len(vp.events) >= 1
        assert vp.events[0]["type"] == "zone"
        assert vp.events[0]["label"] == "Front Door"
        assert vp.events[0]["property"] == "open"
        assert vp.events[0]["value"] is True


class TestEntryDelay:
    """Entry/exit zone opens while armed → entry_delay, then audible_alarm."""

    def _arm_now(self, vp, pid=1, mode="arm"):
        vp.control_partition(pid, mode)
        vp._force_exit_delay_done(pid)

    def test_entry_zone_sets_entry_delay(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.set_zone_open(9, True)  # Front Door = entry_exit
        vp.tick()
        assert vp.partitions[1]["entry_delay"] is True
        assert vp.partitions[1]["audible_alarm"] is False
        assert vp.get_current_state(1) == "armed_away"  # not triggered yet

    def test_entry_delay_expires_to_alarm(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.set_zone_open(9, True)
        vp.tick()
        vp._force_entry_delay_done(1)
        assert vp.partitions[1]["audible_alarm"] is True
        assert vp.zones[9]["alarm"] is True
        assert vp.zones[9]["was_in_alarm"] is True
        assert vp.get_current_state(1) == "triggered"

    def test_disarm_during_entry_delay(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.set_zone_open(9, True)
        vp.tick()
        assert vp.partitions[1]["entry_delay"] is True
        vp.control_partition(1, "disarm")
        assert vp.partitions[1]["entry_delay"] is False
        assert vp.partitions[1]["audible_alarm"] is False
        assert vp.get_current_state(1) == "disarmed"


class TestInstantZone:
    """Instant zone opens while armed → immediate audible_alarm."""

    def _arm_now(self, vp, pid=1):
        vp.control_partition(pid, "arm")
        vp._force_exit_delay_done(pid)

    def test_instant_zone_triggers_immediately(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.set_zone_open(1, True)  # Main Bedroom = instant
        vp.tick()
        assert vp.partitions[1]["audible_alarm"] is True
        assert vp.zones[1]["alarm"] is True
        assert vp.zones[1]["was_in_alarm"] is True
        assert vp.get_current_state(1) == "triggered"

    def test_bypassed_zone_no_trigger(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.control_zone(1, "bypass")
        vp.set_zone_open(1, True)
        vp.tick()
        assert vp.partitions[1]["audible_alarm"] is False
        assert vp.get_current_state(1) == "armed_away"


class TestFollowerZone:
    """Follower zone: during entry_delay acts like entry, else instant."""

    def _arm_now(self, vp, pid=1):
        vp.control_partition(pid, "arm")
        vp._force_exit_delay_done(pid)

    def test_follower_during_entry_delay_no_trigger(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.set_zone_open(9, True)  # entry zone → entry_delay
        vp.tick()
        assert vp.partitions[1]["entry_delay"] is True
        vp.set_zone_open(12, True)  # Hallway = follower
        vp.tick()
        assert vp.partitions[1]["audible_alarm"] is False  # still just entry delay

    def test_follower_without_entry_delay_is_instant(self):
        vp = VirtualPanel()
        self._arm_now(vp)
        vp.set_zone_open(12, True)  # Hallway opened without entry delay
        vp.tick()
        assert vp.partitions[1]["audible_alarm"] is True  # instant trigger


class TestPanic:
    def test_fire_panic(self):
        vp = VirtualPanel()
        vp.send_panic(1, "fire")
        assert vp.partitions[1]["audible_alarm"] is True
        assert vp.get_current_state(1) == "triggered"

    def test_emergency_panic(self):
        vp = VirtualPanel()
        vp.send_panic(1, "emergency")
        assert vp.partitions[1]["audible_alarm"] is True

    def test_panic_records_event(self):
        vp = VirtualPanel()
        vp.send_panic(1, "fire")
        assert any(e.get("property") == "panic" for e in vp.events)


class TestBellTimeout:
    """After alarm triggers, bell stops after timeout but alarm state persists."""

    def _arm_and_trigger(self, vp, pid=1):
        vp.control_partition(pid, "arm")
        vp._force_exit_delay_done(pid)
        vp.set_zone_open(1, True)
        vp.tick()
        assert vp.partitions[pid]["audible_alarm"] is True

    def test_bell_timeout_clears_audible(self):
        vp = VirtualPanel()
        self._arm_and_trigger(vp)
        vp._force_bell_timeout(1)
        assert vp.partitions[1]["audible_alarm"] is False

    def test_still_triggered_after_bell_timeout(self):
        """was_in_alarm persists → still triggered per PAI logic."""
        vp = VirtualPanel()
        self._arm_and_trigger(vp)
        vp._force_bell_timeout(1)
        assert vp.zones[1]["was_in_alarm"] is True
        assert vp.get_current_state(1) == "armed_away"


class TestExitDelayTiming:
    def test_exit_delay_away_longer_than_stay(self):
        vp = VirtualPanel()
        assert vp.EXIT_DELAY_AWAY > vp.EXIT_DELAY_STAY

    def test_exit_delay_resolved_by_tick(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        assert vp.partitions[1]["exit_delay"] is True
        vp._timers["exit_delay_1"] = time.time() - 1  # expired
        vp.tick()
        assert vp.partitions[1]["exit_delay"] is False
        assert vp.partitions[1]["arm"] is True


class TestCommandAckDelay:
    """Commands are queued and applied after COMMAND_ACK_DELAY (simulates serial roundtrip)."""

    def test_arm_not_applied_immediately(self):
        vp = VirtualPanel()
        vp.COMMAND_ACK_DELAY = 2.0
        vp.control_partition(1, "arm")
        assert vp.partitions[1]["exit_delay"] is False
        assert vp.get_current_state(1) == "disarmed"

    def test_arm_applied_after_ack(self):
        vp = VirtualPanel()
        vp.COMMAND_ACK_DELAY = 2.0
        vp.control_partition(1, "arm")
        vp._force_command_ack()
        assert vp.partitions[1]["exit_delay"] is True
        assert vp.get_current_state(1) == "arming"

    def test_disarm_not_applied_immediately(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        vp._force_exit_delay_done(1)
        assert vp.get_current_state(1) == "armed_away"
        vp.COMMAND_ACK_DELAY = 2.0
        vp.control_partition(1, "disarm")
        assert vp.partitions[1]["arm"] is True  # still armed
        assert vp.get_current_state(1) == "armed_away"

    def test_disarm_applied_after_ack(self):
        vp = VirtualPanel()
        vp.control_partition(1, "arm")
        vp._force_exit_delay_done(1)
        vp.COMMAND_ACK_DELAY = 2.0
        vp.control_partition(1, "disarm")
        vp._force_command_ack()
        assert vp.get_current_state(1) == "disarmed"

    def test_bypass_delayed(self):
        vp = VirtualPanel()
        vp.COMMAND_ACK_DELAY = 2.0
        vp.control_zone(1, "bypass")
        assert vp.zones[1]["bypassed"] is False
        vp._force_command_ack()
        assert vp.zones[1]["bypassed"] is True

    def test_zero_delay_applies_immediately(self):
        """Default COMMAND_ACK_DELAY=0 keeps instant behavior for tests."""
        vp = VirtualPanel()
        assert vp.COMMAND_ACK_DELAY == 0
        vp.control_partition(1, "arm")
        assert vp.partitions[1]["exit_delay"] is True

    def test_multiple_commands_queued(self):
        vp = VirtualPanel()
        vp.COMMAND_ACK_DELAY = 2.0
        vp.control_partition(1, "arm")
        vp.control_partition(2, "arm")
        assert vp.get_current_state(1) == "disarmed"
        assert vp.get_current_state(2) == "disarmed"
        vp._force_command_ack()
        assert vp.get_current_state(1) == "arming"
        assert vp.get_current_state(2) == "arming"

    def test_tick_processes_ack(self):
        """tick() should process pending commands whose time has arrived."""
        vp = VirtualPanel()
        vp.COMMAND_ACK_DELAY = 0.05
        vp.control_partition(1, "arm")
        assert vp.get_current_state(1) == "disarmed"
        time.sleep(0.06)
        vp.tick()
        assert vp.get_current_state(1) == "arming"


class TestEventLog:
    def test_events_ordered_newest_first(self):
        vp = VirtualPanel()
        vp.set_zone_open(1, True)
        vp.set_zone_open(2, True)
        assert vp.events[0]["label"] == "Second Room"
        assert vp.events[1]["label"] == "Main Bedroom"

    def test_get_events_with_limit(self):
        vp = VirtualPanel()
        for zid in [1, 2, 3, 4, 5]:
            vp.set_zone_open(zid, True)
        assert len(vp.get_events(limit=3)) == 3
