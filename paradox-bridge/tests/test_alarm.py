"""Tests for AlarmService — delegates to VirtualPanel in demo mode.

The heavy lifting is tested in test_virtual_panel.py. These tests verify
that AlarmService correctly delegates and transforms data.
"""

from unittest.mock import MagicMock

import pytest

from paradox_bridge.alarm import AlarmService, AlarmStatus, PartitionStatus, ZoneInfo
from paradox_bridge.database import Database


class TestAlarmServiceWithMock:
    """Real PAI path — uses mocks to simulate PAI storage containers."""

    @pytest.fixture()
    def alarm(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        svc._pai = MagicMock()
        svc._connected = True
        return svc

    def _setup_storage(self, alarm, partitions, zones):
        part_container = MagicMock()
        part_container.items.return_value = list(partitions.items())
        zone_container = MagicMock()
        zone_container.items.return_value = list(zones.items())
        alarm._pai.storage.get_container.side_effect = lambda name: {
            "partition": part_container,
            "zone": zone_container,
        }[name]

    def test_get_status_armed_away(self, alarm):
        self._setup_storage(alarm, {
            1: {"label": "Partition 1", "arm": True, "arm_stay": False,
                "exit_delay": False, "entry_delay": False, "ready_status": True},
        }, {})
        status = alarm.get_status()
        assert isinstance(status, AlarmStatus)
        assert status.partitions[0].mode == "armed_away"

    def test_get_status_armed_home(self, alarm):
        self._setup_storage(alarm, {
            1: {"label": "Partition 1", "arm": True, "arm_stay": True,
                "exit_delay": False, "entry_delay": False, "ready_status": True},
        }, {})
        assert alarm.get_status().partitions[0].mode == "armed_home"

    def test_get_status_disarmed(self, alarm):
        self._setup_storage(alarm, {
            1: {"label": "Partition 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {})
        assert alarm.get_status().partitions[0].mode == "disarmed"

    def test_not_connected_raises(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        with pytest.raises(ConnectionError):
            svc.get_status()


class TestStatusChangeCallback:
    """Verify the callback fires on status changes and not on steady state."""

    @pytest.fixture()
    def alarm(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        svc._pai = MagicMock()
        svc._connected = True
        return svc

    def _setup_storage(self, alarm, partitions, zones):
        part_container = MagicMock()
        part_container.items.return_value = list(partitions.items())
        zone_container = MagicMock()
        zone_container.items.return_value = list(zones.items())
        alarm._pai.storage.get_container.side_effect = lambda name: {
            "partition": part_container,
            "zone": zone_container,
        }[name]

    def test_callback_fires_on_partition_change(self, alarm):
        cb = MagicMock()
        alarm.set_status_change_callback(cb)
        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {})
        alarm.get_status()
        cb.assert_not_called()

        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": True, "arm_stay": False,
                "exit_delay": False, "entry_delay": False, "ready_status": True},
        }, {})
        alarm.get_status()
        cb.assert_called_once()

    def test_callback_fires_on_zone_change(self, alarm):
        cb = MagicMock()
        alarm.set_status_change_callback(cb)
        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {
            1: {"label": "Front Door", "open": False, "bypassed": False,
                "alarm": False, "was_in_alarm": False, "tamper": False, "partition": 1},
        })
        alarm.get_status()
        cb.assert_not_called()

        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {
            1: {"label": "Front Door", "open": True, "bypassed": False,
                "alarm": False, "was_in_alarm": False, "tamper": False, "partition": 1},
        })
        alarm.get_status()
        cb.assert_called_once()

    def test_no_callback_on_steady_state(self, alarm):
        cb = MagicMock()
        alarm.set_status_change_callback(cb)
        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {})
        alarm.get_status()
        alarm.get_status()
        alarm.get_status()
        cb.assert_not_called()

    def test_callback_not_set_no_error(self, alarm):
        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {
            1: {"label": "Zone 1", "open": False, "bypassed": False,
                "alarm": False, "was_in_alarm": False, "tamper": False, "partition": 1},
        })
        alarm.get_status()
        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {
            1: {"label": "Zone 1", "open": True, "bypassed": False,
                "alarm": False, "was_in_alarm": False, "tamper": False, "partition": 1},
        })
        alarm.get_status()


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

    @pytest.mark.asyncio
    async def test_arm_away_starts_arming(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "arming"

    @pytest.mark.asyncio
    async def test_arm_stay_starts_arming(self, svc):
        await svc.arm_stay(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "arming"

    @pytest.mark.asyncio
    async def test_arm_transitions_to_armed(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "armed_away"
        assert p.armed is True

    @pytest.mark.asyncio
    async def test_arm_stay_transitions_to_armed_home(self, svc):
        await svc.arm_stay(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "armed_home"

    @pytest.mark.asyncio
    async def test_disarm(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        await svc.disarm(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "disarmed"

    @pytest.mark.asyncio
    async def test_bypass_zone(self, svc):
        await svc.bypass_zone(zone_id=1)
        st = svc.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.bypassed is True

    @pytest.mark.asyncio
    async def test_unbypass_zone(self, svc):
        await svc.bypass_zone(zone_id=1)
        await svc.unbypass_zone(zone_id=1)
        st = svc.get_status()
        z = [z for p in st.partitions for z in p.zones if z.id == 1][0]
        assert z.bypassed is False

    @pytest.mark.asyncio
    async def test_instant_zone_triggers_alarm(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=1, is_open=True)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "triggered"

    @pytest.mark.asyncio
    async def test_entry_zone_starts_entry_delay(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=9, is_open=True)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.entry_delay is True
        assert p.mode == "armed_away"

    @pytest.mark.asyncio
    async def test_entry_delay_then_triggered(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=9, is_open=True)
        svc.panel._force_entry_delay_done(1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "triggered"

    @pytest.mark.asyncio
    async def test_disarm_during_entry_delay(self, svc):
        await svc.arm_away(code="1234", partition_id=1)
        svc.panel._force_exit_delay_done(1)
        svc.set_zone_open(zone_id=9, is_open=True)
        await svc.disarm(code="1234", partition_id=1)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.mode == "disarmed"
        assert p.entry_delay is False

    @pytest.mark.asyncio
    async def test_panic(self, svc):
        await svc.send_panic(partition_id=1, panic_type="fire")
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

    @pytest.mark.asyncio
    async def test_not_ready_prevents_arm(self, svc):
        svc.set_zone_open(zone_id=9, is_open=True)
        result = await svc.arm_away(code="1234", partition_id=1)
        assert result is False

    def test_ready_field_in_status(self, svc):
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.ready is True
        svc.set_zone_open(zone_id=9, is_open=True)
        st = svc.get_status()
        p = [p for p in st.partitions if p.id == 1][0]
        assert p.ready is False


class TestEventPersistence:
    """Verify that _record_event writes to the database when one is provided."""

    @pytest.fixture()
    def alarm_with_db(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        svc = AlarmService(
            serial_port="/dev/null", baud=9600, pc_password="0000", db=db,
        )
        svc._pai = MagicMock()
        svc._connected = True
        return svc, db

    def _setup_storage(self, alarm, partitions, zones):
        part_container = MagicMock()
        part_container.items.return_value = list(partitions.items())
        zone_container = MagicMock()
        zone_container.items.return_value = list(zones.items())
        alarm._pai.storage.get_container.side_effect = lambda name: {
            "partition": part_container,
            "zone": zone_container,
        }[name]

    def test_record_event_writes_to_db(self, alarm_with_db):
        alarm, db = alarm_with_db
        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {
            1: {"label": "Front Door", "open": False, "bypassed": False,
                "alarm": False, "was_in_alarm": False, "tamper": False, "partition": 1},
        })
        alarm.get_status()

        self._setup_storage(alarm, {
            1: {"label": "Area 1", "arm": False, "exit_delay": False,
                "entry_delay": False, "ready_status": True},
        }, {
            1: {"label": "Front Door", "open": True, "bypassed": False,
                "alarm": False, "was_in_alarm": False, "tamper": False, "partition": 1},
        })
        alarm.get_status()

        events = db.get_events(limit=10)
        assert len(events) >= 1
        assert events[0]["label"] == "Front Door"
        assert events[0]["property"] == "open"
        db.close()

    def test_history_reads_from_db(self, alarm_with_db):
        alarm, db = alarm_with_db
        db.insert_event("zone", "Seeded Event", "open", "true", "2026-03-09T09:00:00")
        history = alarm.get_zone_history(limit=10)
        assert any(e["label"] == "Seeded Event" for e in history)
        db.close()

    def test_alarm_without_db_still_works(self):
        svc = AlarmService(serial_port="/dev/null", baud=9600, pc_password="0000")
        svc._pai = MagicMock()
        svc._connected = True
        part_container = MagicMock()
        part_container.items.return_value = [(1, {"label": "Area 1", "arm": False, "exit_delay": False, "entry_delay": False, "ready_status": True})]
        zone_container = MagicMock()
        zone_container.items.return_value = []
        svc._pai.storage.get_container.side_effect = lambda name: {"partition": part_container, "zone": zone_container}[name]
        svc.get_status()
        assert svc.get_zone_history() == []
