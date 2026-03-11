"""Tests for AlarmService — delegates to VirtualPanel in demo mode.

The heavy lifting is tested in test_virtual_panel.py. These tests verify
that AlarmService correctly delegates and transforms data.
"""

from unittest.mock import MagicMock

import pytest

from paradox_bridge.alarm import AlarmService, AlarmStatus, PartitionStatus, ZoneInfo


class TestAlarmServiceWithMock:
    """Real PAI path — uses mocks."""

    @pytest.fixture()
    def alarm(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        svc._pai = MagicMock()
        svc._connected = True
        return svc

    def test_get_status_armed_away(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": True, "armed_away": True, "armed_stay": False}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        status = alarm.get_status()
        assert isinstance(status, AlarmStatus)
        assert status.partitions[0].mode == "armed_away"

    def test_get_status_armed_home(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": True, "armed_away": False, "armed_stay": True}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        assert alarm.get_status().partitions[0].mode == "armed_home"

    def test_get_status_disarmed(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": False}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        assert alarm.get_status().partitions[0].mode == "disarmed"

    def test_not_connected_raises(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        with pytest.raises(ConnectionError):
            svc.get_status()


class TestDemoMode:
    """Demo mode — delegates to VirtualPanel."""

    @pytest.fixture()
    def svc(self):
        s = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000", demo_mode=True)
        s.panel.COMMAND_ACK_DELAY = 0  # instant for tests
        return s

    def test_is_connected(self, svc):
        assert svc.is_connected is True

    def test_has_panel(self, svc):
        assert svc.panel is not None

    def test_two_partitions(self, svc):
        st = svc.get_status()
        assert len(st.partitions) == 2

    def test_partition_names(self, svc):
        st = svc.get_status()
        names = [p.name for p in st.partitions]
        assert "Internal" in names
        assert "External" in names

    def test_default_disarmed(self, svc):
        st = svc.get_status()
        for p in st.partitions:
            assert p.mode == "disarmed"
            assert p.armed is False

    def test_zones_have_types(self, svc):
        zones = svc.list_all_zones()
        assert all("type" in z for z in zones)

    def test_arm_away_starts_arming(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "arming"

    def test_arm_stay_starts_arming(self, svc):
        svc.arm_stay(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "arming"

    def test_arm_transitions_to_armed(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "armed_away"
        assert p.armed is True

    def test_arm_stay_transitions_to_armed_home(self, svc):
        svc.arm_stay(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "armed_home"

    def test_disarm(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        svc.disarm(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "disarmed"

    def test_bypass_zone(self, svc):
        svc.bypass_zone(zone_id=1)
        st = svc.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.bypassed is True

    def test_unbypass_zone(self, svc):
        svc.bypass_zone(zone_id=1)
        svc.unbypass_zone(zone_id=1)
        st = svc.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.bypassed is False

    def test_instant_zone_triggers_alarm(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=1, is_open=True)  # Main Bedroom = instant
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "triggered"

    def test_entry_zone_starts_entry_delay(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=9, is_open=True)  # Front Door = entry_exit
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.entry_delay is True
        assert p.mode == "armed_away"  # not triggered yet

    def test_entry_delay_then_triggered(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=9, is_open=True)
        svc.panel._force_entry_delay_done(1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "triggered"

    def test_disarm_during_entry_delay(self, svc):
        svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=9, is_open=True)
        svc.disarm(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "disarmed"
        assert p.entry_delay is False

    def test_panic(self, svc):
        svc.send_panic(partition_id=1, panic_type="fire")
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "triggered"

    def test_history_from_panel_events(self, svc):
        svc.set_zone_open(zone_id=1, is_open=True)
        h = svc.get_zone_history()
        assert len(h) >= 1
        assert h[0]["label"] == "Main Bedroom"

    def test_zone_properties_in_status(self, svc):
        st = svc.get_status()
        for p in st.partitions:
            for z in p.zones:
                assert hasattr(z, "alarm")
                assert hasattr(z, "was_in_alarm")
                assert hasattr(z, "tamper")

    def test_not_ready_prevents_arm(self, svc):
        svc.set_zone_open(zone_id=9, is_open=True)
        result = svc.arm_away(code="1234", partition_id=1)
        assert result is False

    def test_ready_field_in_status(self, svc):
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.ready is True
        svc.set_zone_open(zone_id=9, is_open=True)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.ready is False
