"""Tests for the alarm service (PAI wrapper). Uses a mock PAI connection."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from paradox_bridge.alarm import AlarmService, AlarmStatus, ZoneInfo


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
        assert status.armed is True
        assert status.mode == "away"
        assert len(status.zones) == 2
        assert status.zones[0].name == "Front Door"
        assert status.zones[0].open is False
        assert status.zones[1].open is True

    def test_get_status_disarmed(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": False, "armed_away": False, "armed_stay": False}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        status = alarm.get_status()
        assert status.armed is False
        assert status.mode == "disarmed"

    def test_get_status_stay_mode(self, alarm):
        alarm._pai.panel = {
            "partitions": {
                1: {"status": {"armed": True, "armed_away": False, "armed_stay": True}},
            },
        }
        alarm._pai.storage.zones = MagicMock()
        alarm._pai.storage.zones.select.return_value = []
        status = alarm.get_status()
        assert status.armed is True
        assert status.mode == "stay"

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


class TestDemoMode:
    """Tests for demo/mock alarm mode (no real panel needed)."""

    @pytest.fixture()
    def demo(self):
        return AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000", demo_mode=True)

    def test_demo_is_connected(self, demo):
        assert demo.is_connected is True

    def test_demo_status_default_disarmed(self, demo):
        st = demo.get_status()
        assert st.armed is False
        assert st.mode == "disarmed"
        assert len(st.zones) > 0

    def test_demo_arm_away(self, demo):
        assert demo.arm_away(code="1234") is True
        st = demo.get_status()
        assert st.armed is True
        assert st.mode == "away"

    def test_demo_arm_stay(self, demo):
        assert demo.arm_stay(code="1234") is True
        st = demo.get_status()
        assert st.armed is True
        assert st.mode == "stay"

    def test_demo_disarm(self, demo):
        demo.arm_away(code="1234")
        assert demo.disarm(code="1234") is True
        st = demo.get_status()
        assert st.armed is False
        assert st.mode == "disarmed"

    def test_demo_zones_present(self, demo):
        st = demo.get_status()
        names = [z.name for z in st.zones]
        assert "Front Door" in names
        assert "Garage" in names
