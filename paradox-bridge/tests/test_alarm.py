"""Tests for the alarm service (PAI wrapper). Uses a mock PAI connection."""

import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from paradox_bridge.alarm import AlarmService, AlarmStatus, PartitionStatus, ZoneInfo


class TestAlarmServiceWithMock:
    """All tests use a mock PAI — no real serial connection needed."""

    @pytest.fixture()
    def alarm(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        svc._pai = MagicMock()
        svc._connected = True
        return svc

    def test_get_status_returns_alarm_status(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": True, "armed_away": True, "armed_stay": False}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = [
            {"id": 1, "label": "Front Door", "open": False},
            {"id": 2, "label": "Kitchen", "open": True},
        ]
        status = alarm.get_status()
        assert isinstance(status, AlarmStatus)
        assert len(status.partitions) >= 1
        p = status.partitions[0]
        assert p.armed is True
        assert p.mode == "away"

    def test_get_status_disarmed(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": False, "armed_away": False, "armed_stay": False}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        status = alarm.get_status()
        assert status.partitions[0].armed is False
        assert status.partitions[0].mode == "disarmed"

    def test_get_status_stay_mode(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": True, "armed_away": False, "armed_stay": True}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        status = alarm.get_status()
        assert status.partitions[0].armed is True
        assert status.partitions[0].mode == "stay"

    def test_arm_away(self, alarm):
        alarm._pai.control_partition = MagicMock(return_value=True)
        result = alarm.arm_away(code="1234")
        assert result is True
        alarm._pai.control_partition.assert_called_once()

    def test_arm_stay(self, alarm):
        alarm._pai.control_partition = MagicMock(return_value=True)
        result = alarm.arm_stay(code="1234")
        assert result is True

    def test_disarm(self, alarm):
        alarm._pai.control_partition = MagicMock(return_value=True)
        result = alarm.disarm(code="1234")
        assert result is True

    def test_not_connected_raises(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        with pytest.raises(ConnectionError, match="Not connected"):
            svc.get_status()

    def test_is_connected(self, alarm):
        assert alarm.is_connected is True

    def test_is_not_connected(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        assert svc.is_connected is False


class TestDemoPartitions:
    """Demo mode should return two partitions: Internal and External."""

    @pytest.fixture()
    def demo(self):
        return AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000", demo_mode=True)

    def test_demo_is_connected(self, demo):
        assert demo.is_connected is True

    def test_two_partitions(self, demo):
        st = demo.get_status()
        assert len(st.partitions) == 2

    def test_partition_names(self, demo):
        st = demo.get_status()
        names = [p.name for p in st.partitions]
        assert "Internal" in names
        assert "External" in names

    def test_internal_zones(self, demo):
        st = demo.get_status()
        internal = [p for p in st.partitions if p.id == 1][0]
        zone_names = [z.name for z in internal.zones]
        assert "Main Bedroom" in zone_names
        assert "Kitchen" in zone_names
        assert "Front Door" in zone_names
        assert "Studio" in zone_names
        assert "Living Room" in zone_names

    def test_external_zones(self, demo):
        st = demo.get_status()
        external = [p for p in st.partitions if p.id == 2][0]
        zone_names = [z.name for z in external.zones]
        assert "Beam West" in zone_names
        assert "Beam North" in zone_names
        assert "Garage" in zone_names
        assert "Wendy House" in zone_names

    def test_default_disarmed(self, demo):
        st = demo.get_status()
        for p in st.partitions:
            assert p.armed is False
            assert p.mode == "disarmed"

    def test_arm_away_partition(self, demo):
        demo.arm_away(code="1234", partition_id=1)
        st = demo.get_status()
        p1 = [p for p in st.partitions if p.id == 1][0]
        p2 = [p for p in st.partitions if p.id == 2][0]
        assert p1.armed is True
        assert p1.mode == "away"
        assert p2.armed is False

    def test_arm_stay_partition(self, demo):
        demo.arm_stay(code="1234", partition_id=2)
        st = demo.get_status()
        p2 = [p for p in st.partitions if p.id == 2][0]
        assert p2.armed is True
        assert p2.mode == "stay"

    def test_disarm_partition(self, demo):
        demo.arm_away(code="1234", partition_id=1)
        demo.disarm(code="1234", partition_id=1)
        st = demo.get_status()
        p1 = [p for p in st.partitions if p.id == 1][0]
        assert p1.armed is False
        assert p1.mode == "disarmed"


class TestDemoBypass:
    """Zone bypass in demo mode."""

    @pytest.fixture()
    def demo(self):
        return AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000", demo_mode=True)

    def test_bypass_zone(self, demo):
        demo.bypass_zone(zone_id=1)
        st = demo.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.bypassed is True

    def test_unbypass_zone(self, demo):
        demo.bypass_zone(zone_id=1)
        demo.unbypass_zone(zone_id=1)
        st = demo.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.bypassed is False

    def test_bypass_nonexistent_zone_raises(self, demo):
        with pytest.raises(ValueError, match="Zone 999 not found"):
            demo.bypass_zone(zone_id=999)

    def test_zones_default_not_bypassed(self, demo):
        st = demo.get_status()
        for p in st.partitions:
            for z in p.zones:
                assert z.bypassed is False


class TestDemoZoneToggle:
    """SSH zone toggling for debug."""

    @pytest.fixture()
    def demo(self):
        return AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000", demo_mode=True)

    def test_open_zone(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        st = demo.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.open is True

    def test_close_zone(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        demo.set_zone_open(zone_id=1, is_open=False)
        st = demo.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.open is False

    def test_toggle_creates_history(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        history = demo.get_zone_history(limit=10)
        assert len(history) >= 1
        assert history[0]["zone_id"] == 1
        assert history[0]["zone_name"] == "Main Bedroom"
        assert history[0]["event"] == "opened"

    def test_close_creates_history(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        demo.set_zone_open(zone_id=1, is_open=False)
        history = demo.get_zone_history(limit=10)
        assert len(history) >= 2
        assert history[0]["event"] == "closed"

    def test_toggle_nonexistent_zone_raises(self, demo):
        with pytest.raises(ValueError, match="Zone 999 not found"):
            demo.set_zone_open(zone_id=999, is_open=True)


class TestDemoHistory:
    """Zone event history."""

    @pytest.fixture()
    def demo(self):
        return AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000", demo_mode=True)

    def test_empty_history_initially(self, demo):
        h = demo.get_zone_history()
        assert h == []

    def test_history_has_timestamp(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        h = demo.get_zone_history()
        assert "timestamp" in h[0]
        assert isinstance(h[0]["timestamp"], str)

    def test_history_ordering_newest_first(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        demo.set_zone_open(zone_id=2, is_open=True)
        h = demo.get_zone_history()
        assert h[0]["zone_id"] == 2
        assert h[1]["zone_id"] == 1

    def test_history_limit(self, demo):
        for i in range(1, 6):
            demo.set_zone_open(zone_id=i, is_open=True)
        h = demo.get_zone_history(limit=3)
        assert len(h) == 3

    def test_history_includes_partition(self, demo):
        demo.set_zone_open(zone_id=1, is_open=True)
        demo.set_zone_open(zone_id=33, is_open=True)
        h = demo.get_zone_history()
        partitions = {e["partition_id"] for e in h}
        assert 1 in partitions
        assert 2 in partitions

    def test_list_zones(self, demo):
        """List all zones across partitions — for the CLI tool."""
        zones = demo.list_all_zones()
        assert len(zones) > 0
        assert all("id" in z and "name" in z and "partition" in z for z in zones)
