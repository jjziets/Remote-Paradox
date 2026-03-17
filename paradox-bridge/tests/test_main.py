"""Integration tests for the FastAPI app using httpx TestClient."""

import asyncio
import json
import time
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

import paradox_bridge.main as app_module
from paradox_bridge.alarm import AlarmService
from paradox_bridge.main import app, init_services, shutdown_services


@pytest.fixture(autouse=True)
def setup_app(tmp_path):
    """Initialise services with a temp config for every test."""
    config_path = str(tmp_path / "config.json")
    init_services(config_path=config_path)
    app_module._auth.setup_admin("admin", "secret123")
    yield
    shutdown_services()


@pytest.fixture()
def demo_app(tmp_path):
    """Re-initialise services in demo mode."""
    config_path = tmp_path / "demo_config.json"
    config_path.write_text(json.dumps({"demo_mode": True}))
    init_services(config_path=str(config_path))
    app_module._alarm.panel.COMMAND_ACK_DELAY = 0  # instant for tests
    app_module._auth.setup_admin("admin", "secret123")


@pytest.fixture()
def client():
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture()
def admin_token(client):
    resp = client.post("/auth/login", json={"username": "admin", "password": "secret123"})
    assert resp.status_code == 200
    return resp.json()["token"]


def auth_header(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


class TestHealth:
    def test_health_ok(self, client):
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert data["alarm_connected"] is False

    def test_health_shows_demo(self, client, demo_app):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json()["demo_mode"] is True


class TestLogin:
    def test_login_success(self, client):
        resp = client.post("/auth/login", json={"username": "admin", "password": "secret123"})
        assert resp.status_code == 200
        data = resp.json()
        assert "token" in data
        assert data["username"] == "admin"
        assert data["role"] == "admin"

    def test_login_wrong_password(self, client):
        resp = client.post("/auth/login", json={"username": "admin", "password": "wrong"})
        assert resp.status_code == 401

    def test_login_nonexistent_user(self, client):
        resp = client.post("/auth/login", json={"username": "nobody", "password": "pass"})
        assert resp.status_code == 401


class TestInviteAndRegister:
    def test_create_invite_as_admin(self, client, admin_token):
        resp = client.post("/auth/invite", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert "code" in data
        assert "uri" in data
        assert data["uri"].startswith("paradox://")
        assert data["expires_in_seconds"] == 900
        assert "qr_data_uri" in data
        assert data["qr_data_uri"].startswith("data:image/png;base64,")

    def test_create_invite_as_non_admin(self, client, admin_token):
        inv = client.post("/auth/invite", headers=auth_header(admin_token)).json()
        reg = client.post("/auth/register", json={
            "invite_code": inv["code"], "username": "john", "password": "pass1234"
        })
        user_token = reg.json()["token"]
        resp = client.post("/auth/invite", headers=auth_header(user_token))
        assert resp.status_code == 403

    def test_register_with_invite(self, client, admin_token):
        inv = client.post("/auth/invite", headers=auth_header(admin_token)).json()
        resp = client.post("/auth/register", json={
            "invite_code": inv["code"], "username": "john", "password": "pass1234"
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["username"] == "john"
        assert "token" in data

    def test_register_with_bad_invite(self, client):
        resp = client.post("/auth/register", json={
            "invite_code": "FAKE-CODE", "username": "john", "password": "pass1234"
        })
        assert resp.status_code == 400

    def test_invite_consumed_after_register(self, client, admin_token):
        inv = client.post("/auth/invite", headers=auth_header(admin_token)).json()
        client.post("/auth/register", json={
            "invite_code": inv["code"], "username": "john", "password": "pass1234"
        })
        resp = client.post("/auth/register", json={
            "invite_code": inv["code"], "username": "jane", "password": "pass5678"
        })
        assert resp.status_code == 400
        assert "Invalid or expired" in resp.json()["detail"]


class TestUserManagement:
    def test_list_users(self, client, admin_token):
        resp = client.get("/auth/users", headers=auth_header(admin_token))
        assert resp.status_code == 200
        users = resp.json()["users"]
        assert any(u["username"] == "admin" for u in users)

    def test_delete_user(self, client, admin_token):
        inv = client.post("/auth/invite", headers=auth_header(admin_token)).json()
        client.post("/auth/register", json={
            "invite_code": inv["code"], "username": "john", "password": "pass1234"
        })
        resp = client.delete("/auth/users/john", headers=auth_header(admin_token))
        assert resp.status_code == 200
        assert resp.json()["success"] is True

    def test_cannot_delete_self(self, client, admin_token):
        resp = client.delete("/auth/users/admin", headers=auth_header(admin_token))
        assert resp.status_code == 400


class TestResetPassword:
    def _create_user(self, client, admin_token, username="john"):
        inv = client.post("/auth/invite", headers=auth_header(admin_token)).json()
        client.post("/auth/register", json={
            "invite_code": inv["code"], "username": username, "password": "pass1234"
        })

    def test_reset_password_success(self, client, admin_token):
        self._create_user(client, admin_token)
        resp = client.put(
            "/auth/users/john/password",
            json={"password": "newpass123"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        assert resp.json()["success"] is True
        login = client.post("/auth/login", json={"username": "john", "password": "newpass123"})
        assert login.status_code == 200

    def test_reset_password_old_password_fails(self, client, admin_token):
        self._create_user(client, admin_token)
        client.put(
            "/auth/users/john/password",
            json={"password": "newpass123"},
            headers=auth_header(admin_token),
        )
        login = client.post("/auth/login", json={"username": "john", "password": "pass1234"})
        assert login.status_code == 401

    def test_reset_own_password_rejected(self, client, admin_token):
        resp = client.put(
            "/auth/users/admin/password",
            json={"password": "newpass123"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 400

    def test_non_admin_cannot_reset(self, client, admin_token):
        self._create_user(client, admin_token)
        login = client.post("/auth/login", json={"username": "john", "password": "pass1234"})
        user_token = login.json()["token"]
        resp = client.put(
            "/auth/users/admin/password",
            json={"password": "hacked"},
            headers=auth_header(user_token),
        )
        assert resp.status_code == 403

    def test_reset_password_too_short(self, client, admin_token):
        self._create_user(client, admin_token)
        resp = client.put(
            "/auth/users/john/password",
            json={"password": "abc"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 422

    def test_reset_nonexistent_user(self, client, admin_token):
        resp = client.put(
            "/auth/users/nobody/password",
            json={"password": "newpass123"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 400


class TestAlarmRoutes:
    def test_status_when_disconnected(self, client, admin_token):
        resp = client.get("/alarm/status", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["connected"] is False
        assert data["partitions"] == []

    def test_arm_away_when_disconnected(self, client, admin_token):
        resp = client.post(
            "/alarm/arm-away",
            json={"code": "1234"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 503

    def test_unauthenticated_access_denied(self, client):
        resp = client.get("/alarm/status")
        assert resp.status_code in (401, 403)


class TestAlarmDemo:
    def test_status_demo_two_partitions(self, client, demo_app, admin_token):
        resp = client.get("/alarm/status", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["connected"] is True
        assert len(data["partitions"]) == 2
        names = [p["name"] for p in data["partitions"]]
        assert "Internal" in names
        assert "External" in names

    def test_status_has_ready_and_entry_delay(self, client, demo_app, admin_token):
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["ready"] is True
        assert p1["entry_delay"] is False
        assert "exit_delay_remaining" not in p1

    def test_arm_away_demo_starts_arming(self, client, demo_app, admin_token):
        resp = client.post(
            "/alarm/arm-away",
            json={"code": "1234", "partition_id": 1},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        assert resp.json()["success"] is True
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["armed"] is False
        assert p1["mode"] == "arming"

    def test_arm_transitions_to_armed_away(self, client, demo_app, admin_token):
        client.post(
            "/alarm/arm-away",
            json={"code": "1234", "partition_id": 1},
            headers=auth_header(admin_token),
        )
        alarm = app_module._alarm
        alarm.panel._force_exit_delay_done(1)
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["armed"] is True
        assert p1["mode"] == "armed_away"

    def test_instant_zone_triggers_via_api(self, client, demo_app, admin_token):
        """Open an instant zone (Main Bedroom) while armed → triggered immediately."""
        client.post("/alarm/arm-away", json={"code": "1234", "partition_id": 1},
                     headers=auth_header(admin_token))
        alarm = app_module._alarm
        alarm.panel._force_exit_delay_done(1)
        client.post("/alarm/zone-toggle", json={"zone_id": 1, "open": True},
                     headers=auth_header(admin_token))
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["mode"] == "triggered"
        triggered_zones = [z for z in p1["zones"] if z["alarm"]]
        assert len(triggered_zones) >= 1
        assert triggered_zones[0]["id"] == 1

    def test_entry_zone_starts_entry_delay(self, client, demo_app, admin_token):
        """Open an entry/exit zone (Front Door) while armed → entry_delay first."""
        client.post("/alarm/arm-away", json={"code": "1234", "partition_id": 1},
                     headers=auth_header(admin_token))
        alarm = app_module._alarm
        alarm.panel._force_exit_delay_done(1)
        client.post("/alarm/zone-toggle", json={"zone_id": 9, "open": True},
                     headers=auth_header(admin_token))
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["entry_delay"] is True
        assert p1["mode"] == "armed_away"

    def test_bypass_zone(self, client, demo_app, admin_token):
        resp = client.post(
            "/alarm/bypass",
            json={"zone_id": 1, "bypass": True},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        assert resp.json()["action"] == "bypass"
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        z1 = None
        for p in st["partitions"]:
            for z in p["zones"]:
                if z["id"] == 1:
                    z1 = z
        assert z1 is not None
        assert z1["bypassed"] is True

    def test_unbypass_zone(self, client, demo_app, admin_token):
        client.post(
            "/alarm/bypass",
            json={"zone_id": 1, "bypass": True},
            headers=auth_header(admin_token),
        )
        resp = client.post(
            "/alarm/bypass",
            json={"zone_id": 1, "bypass": False},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        assert resp.json()["action"] == "unbypass"

    def test_zone_toggle(self, client, demo_app, admin_token):
        resp = client.post(
            "/alarm/zone-toggle",
            json={"zone_id": 1, "open": True},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        assert resp.json()["action"] == "zone_opened"

    def test_zone_toggle_requires_auth(self, client, demo_app):
        resp = client.post(
            "/alarm/zone-toggle",
            json={"zone_id": 1, "open": True},
        )
        assert resp.status_code in (401, 403)

    def test_zone_toggle_not_in_non_demo(self, client, admin_token):
        resp = client.post(
            "/alarm/zone-toggle",
            json={"zone_id": 1, "open": True},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 403

    def test_event_history(self, client, demo_app, admin_token):
        client.post("/alarm/zone-toggle", json={"zone_id": 1, "open": True},
                     headers=auth_header(admin_token))
        client.post("/alarm/zone-toggle", json={"zone_id": 1, "open": False},
                     headers=auth_header(admin_token))
        resp = client.get("/alarm/history", headers=auth_header(admin_token))
        assert resp.status_code == 200
        events = resp.json()["events"]
        assert len(events) >= 2
        assert events[0]["type"] == "zone"
        assert events[0]["label"] == "Main Bedroom"

    def test_panic_route(self, client, demo_app, admin_token):
        resp = client.post(
            "/alarm/panic",
            json={"partition_id": 1, "panic_type": "fire"},
            headers=auth_header(admin_token),
        )
        assert resp.status_code == 200
        assert resp.json()["success"] is True
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["mode"] == "triggered"

    def test_panic_requires_auth(self, client, demo_app):
        resp = client.post(
            "/alarm/panic",
            json={"partition_id": 1, "panic_type": "emergency"},
        )
        assert resp.status_code in (401, 403)

    def test_not_ready_prevents_arm(self, client, demo_app, admin_token):
        client.post("/alarm/zone-toggle", json={"zone_id": 9, "open": True},
                     headers=auth_header(admin_token))
        st = client.get("/alarm/status", headers=auth_header(admin_token)).json()
        p1 = [p for p in st["partitions"] if p["id"] == 1][0]
        assert p1["ready"] is False
        resp = client.post(
            "/alarm/arm-away",
            json={"code": "1234", "partition_id": 1},
            headers=auth_header(admin_token),
        )
        assert resp.json()["success"] is False


class TestAuditLogs:
    def test_audit_logs_after_actions(self, client, admin_token):
        inv = client.post("/auth/invite", headers=auth_header(admin_token))
        resp = client.get("/alarm/logs", headers=auth_header(admin_token))
        assert resp.status_code == 200
        entries = resp.json()["entries"]
        assert any(e["action"] == "create_invite" for e in entries)

    def test_audit_logs_require_auth(self, client):
        resp = client.get("/alarm/logs")
        assert resp.status_code in (401, 403)


class TestNoCORS:
    """FastAPI should NOT set CORS headers — nginx handles that."""

    def test_no_cors_header_on_response(self, client):
        resp = client.get("/health", headers={"Origin": "http://localhost:3000"})
        assert "access-control-allow-origin" not in resp.headers


class TestRealModeLifespan:
    """Tests for real-mode (non-demo) alarm connect/disconnect in lifespan."""

    def _run_lifespan(self, tmp_path, config_overrides=None):
        """Helper: sets up real-mode config and returns a context for lifespan."""
        config_path = tmp_path / "real_config.json"
        config_path.write_text(json.dumps(config_overrides or {"demo_mode": False}))
        return str(config_path)

    def test_lifespan_calls_connect_in_real_mode(self, tmp_path):
        cfg_path = self._run_lifespan(tmp_path)
        with patch.object(AlarmService, "connect", new_callable=AsyncMock) as mock_connect:
            init_services(config_path=cfg_path)
            app_module._auth.setup_admin("admin", "secret123")
            loop = asyncio.new_event_loop()
            try:
                async def _test():
                    from paradox_bridge.main import _connect_alarm
                    await _connect_alarm()
                loop.run_until_complete(_test())
            finally:
                loop.close()
            mock_connect.assert_awaited()
        shutdown_services()

    def test_lifespan_calls_disconnect_on_shutdown(self, tmp_path):
        cfg_path = self._run_lifespan(tmp_path)
        with patch.object(AlarmService, "connect", new_callable=AsyncMock), \
             patch.object(AlarmService, "disconnect", new_callable=AsyncMock) as mock_disconnect:
            init_services(config_path=cfg_path)
            app_module._auth.setup_admin("admin", "secret123")
            app_module._alarm._connected = True
            loop = asyncio.new_event_loop()
            try:
                async def _test():
                    from paradox_bridge.main import _disconnect_alarm
                    await _disconnect_alarm()
                loop.run_until_complete(_test())
            finally:
                loop.close()
            mock_disconnect.assert_awaited_once()
        shutdown_services()

    def test_connect_retries_on_failure(self, tmp_path):
        cfg_path = self._run_lifespan(tmp_path)
        call_count = 0

        async def flaky_connect(self_alarm):
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise ConnectionError("Serial port busy")
            self_alarm._connected = True

        with patch.object(AlarmService, "connect", flaky_connect):
            init_services(config_path=cfg_path)
            app_module._auth.setup_admin("admin", "secret123")
            loop = asyncio.new_event_loop()
            try:
                async def _test():
                    from paradox_bridge.main import _connect_alarm
                    await _connect_alarm()
                loop.run_until_complete(_test())
            finally:
                loop.close()
            assert call_count >= 3
            assert app_module._alarm.is_connected
        shutdown_services()

    def test_health_shows_not_connected_before_connect(self, tmp_path, client):
        cfg_path = self._run_lifespan(tmp_path)
        init_services(config_path=cfg_path)
        app_module._auth.setup_admin("admin", "secret123")
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json()["alarm_connected"] is False
        assert resp.json()["demo_mode"] is False
        shutdown_services()


class TestSystemResources:
    def test_requires_admin(self, client):
        resp = client.get("/system/resources")
        assert resp.status_code in (401, 403)

    def test_returns_resource_data(self, client, admin_token):
        with patch("paradox_bridge.main._read_cpu_percent", return_value=25.3), \
             patch("paradox_bridge.main._read_memory", return_value=(200, 512, 39.1)), \
             patch("paradox_bridge.main._read_uptime", return_value=86400), \
             patch("shutil.disk_usage") as mock_du:
            mock_du.return_value = type("Usage", (), {
                "total": 16 * 1024**3, "used": 4 * 1024**3, "free": 12 * 1024**3,
            })()
            resp = client.get("/system/resources", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["cpu_percent"] == 25.3
        assert data["memory_used_mb"] == 200
        assert data["memory_total_mb"] == 512
        assert data["memory_percent"] == 39.1
        assert data["disk_used_gb"] == 4.0
        assert data["disk_total_gb"] == 16.0
        assert data["uptime_seconds"] == 86400


class TestSystemWifi:
    def test_requires_admin(self, client):
        resp = client.get("/system/wifi")
        assert resp.status_code in (401, 403)

    def test_returns_wifi_info(self, client, admin_token):
        with patch("subprocess.run") as mock_run:
            def side_effect(cmd, **kw):
                r = type("R", (), {"stdout": "", "stderr": "", "returncode": 0})()
                if cmd[0] == "iwgetid":
                    r.stdout = "MyNetwork\n"
                elif cmd[0] == "hostname":
                    r.stdout = "192.168.50.10\n"
                elif cmd[0] == "iwconfig":
                    r.stdout = "wlan0  Signal level=-55 dBm\n"
                return r
            mock_run.side_effect = side_effect
            resp = client.get("/system/wifi", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["ssid"] == "MyNetwork"
        assert data["ip_address"] == "192.168.50.10"
        assert data["signal_dbm"] == -55
        assert data["signal_percent"] == 90


class TestSystemReboot:
    def test_requires_admin(self, client):
        resp = client.post("/system/reboot")
        assert resp.status_code in (401, 403)

    def test_initiates_reboot(self, client, admin_token):
        with patch("subprocess.Popen") as mock_popen:
            resp = client.post("/system/reboot", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert data["action"] == "reboot"
        mock_popen.assert_called_once()
