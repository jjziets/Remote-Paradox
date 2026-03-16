"""Tests for BLE server — command handling, auth enforcement, and client tracker."""

import json
from unittest.mock import patch, MagicMock

import pytest

from paradox_bridge.ble_server import handle_command, BleClientTracker


class TestHandleCommand:
    """Verify handle_command JSON protocol and auth enforcement."""

    def test_invalid_json(self):
        result = json.loads(handle_command("not json"))
        assert "error" in result

    def test_status_public(self):
        result = json.loads(handle_command(json.dumps({"cmd": "status"})))
        assert "ip" in result
        assert "ssid" in result
        assert "version" in result

    def test_unknown_command(self):
        result = json.loads(handle_command(json.dumps({"cmd": "nope"})))
        assert "error" in result
        assert "unknown" in result["error"]

    def test_authenticated_command_without_token(self):
        result = json.loads(handle_command(json.dumps({"cmd": "wifi_scan"})))
        assert "error" in result
        assert "authentication" in result["error"]

    def test_admin_command_without_token(self):
        result = json.loads(handle_command(json.dumps({"cmd": "system_reboot"})))
        assert "error" in result
        assert "authentication" in result["error"]

    @patch("paradox_bridge.ble_server._validate_token")
    def test_admin_command_as_user_rejected(self, mock_validate):
        mock_validate.return_value = {"sub": "john", "role": "user"}
        result = json.loads(handle_command(json.dumps({"cmd": "system_reboot", "token": "fake"})))
        assert "error" in result
        assert "admin" in result["error"]

    @patch("paradox_bridge.ble_server._validate_token")
    def test_wifi_scan_as_authenticated_user(self, mock_validate):
        mock_validate.return_value = {"sub": "john", "role": "user"}
        result = json.loads(handle_command(json.dumps({"cmd": "wifi_scan", "token": "fake"})))
        assert "networks" in result

    def test_admin_setup_when_already_trusted(self):
        with patch("paradox_bridge.ble_server.is_trusted", return_value=True):
            result = json.loads(handle_command(json.dumps({
                "cmd": "admin_setup", "username": "admin", "password": "pass",
            })))
            assert "error" in result
            assert "already" in result["error"]


class TestBleClientTracker:
    """Verify in-memory BLE client tracking state management."""

    def test_add_client(self):
        tracker = BleClientTracker()
        tracker.client_connected("AA:BB:CC:DD:EE:FF", "Phone")
        clients = tracker.get_clients()
        assert len(clients) == 1
        assert clients[0]["address"] == "AA:BB:CC:DD:EE:FF"
        assert clients[0]["name"] == "Phone"
        assert clients[0]["username"] is None

    def test_remove_client(self):
        tracker = BleClientTracker()
        tracker.client_connected("AA:BB:CC:DD:EE:FF", "Phone")
        tracker.client_disconnected("AA:BB:CC:DD:EE:FF")
        assert tracker.get_clients() == []

    def test_set_username(self):
        tracker = BleClientTracker()
        tracker.client_connected("AA:BB:CC:DD:EE:FF", "Phone")
        tracker.set_username("AA:BB:CC:DD:EE:FF", "john")
        clients = tracker.get_clients()
        assert clients[0]["username"] == "john"

    def test_remove_nonexistent_no_error(self):
        tracker = BleClientTracker()
        tracker.client_disconnected("AA:BB:CC:DD:EE:FF")

    def test_multiple_clients(self):
        tracker = BleClientTracker()
        tracker.client_connected("AA:BB:CC:DD:EE:01", "Phone1")
        tracker.client_connected("AA:BB:CC:DD:EE:02", "Phone2")
        assert len(tracker.get_clients()) == 2

    def test_count(self):
        tracker = BleClientTracker()
        assert tracker.count == 0
        tracker.client_connected("AA:BB:CC:DD:EE:FF", "Phone")
        assert tracker.count == 1


class TestPairingAgent:
    """Verify NoInputNoOutput pairing agent helper functions."""

    def test_agent_capability_is_no_input_no_output(self):
        from paradox_bridge.ble_server import AGENT_CAPABILITY
        assert AGENT_CAPABILITY == "NoInputNoOutput"

    def test_agent_path_defined(self):
        from paradox_bridge.ble_server import AGENT_PATH
        assert AGENT_PATH.startswith("/")

    def test_register_agent_calls_agent_manager(self):
        import sys
        mock_dbus = MagicMock()
        mock_dbus.service.Object = type("Object", (), {"__init__": lambda *a, **k: None})
        mock_dbus.service.method = lambda *a, **k: lambda f: f
        sys.modules["dbus"] = mock_dbus
        sys.modules["dbus.service"] = mock_dbus.service
        try:
            import importlib
            import paradox_bridge.ble_server as mod
            importlib.reload(mod)
            mock_bus = MagicMock()
            mod.register_pairing_agent(mock_bus)
            mock_bus.get_object.assert_called()
        finally:
            del sys.modules["dbus"]
            del sys.modules["dbus.service"]
