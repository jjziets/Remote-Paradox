"""Integration tests for the FastAPI app using httpx TestClient."""

import pytest
from fastapi.testclient import TestClient

import paradox_bridge.main as app_module
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


class TestAlarmRoutes:
    def test_status_when_disconnected(self, client, admin_token):
        resp = client.get("/alarm/status", headers=auth_header(admin_token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["connected"] is False
        assert data["mode"] == "unknown"

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
