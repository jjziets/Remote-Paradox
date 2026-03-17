"""Tests for auth: JWT, login, register with invite, invite generation."""

import time

import pytest

from paradox_bridge.auth import AuthService
from paradox_bridge.config import AppConfig
from paradox_bridge.database import Database


@pytest.fixture()
def auth_env(tmp_path):
    """Set up a Database + AuthService wired together."""
    db_path = str(tmp_path / "test.db")
    cfg = AppConfig(
        serial_port="/dev/serial0",
        serial_baud=9600,
        api_port=8080,
        api_host="0.0.0.0",
        jwt_secret="test-secret-key-for-unit-tests-only",
        jwt_expiry_hours=24,
        panel_pc_password="0000",
        invite_expiry_seconds=900,
        config_path=str(tmp_path / "config.json"),
    )
    db = Database(db_path)
    db.init()
    auth = AuthService(db=db, config=cfg)
    yield auth, db
    db.close()


class TestPasswordHashing:
    def test_hash_and_verify(self, auth_env):
        auth, _ = auth_env
        hashed = auth.hash_password("Paradox")
        assert auth.verify_password("Paradox", hashed) is True

    def test_wrong_password_fails(self, auth_env):
        auth, _ = auth_env
        hashed = auth.hash_password("Paradox")
        assert auth.verify_password("wrong", hashed) is False


class TestSetupAdmin:
    def test_setup_creates_admin(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        user = db.get_user("admin")
        assert user is not None
        assert user["role"] == "admin"

    def test_setup_admin_twice_is_idempotent(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        auth.setup_admin("admin", "secret123")  # no error
        assert len(db.list_users()) == 1


class TestLogin:
    def test_login_success(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        token = auth.login("admin", "secret123")
        assert isinstance(token, str)
        assert len(token) > 20

    def test_login_wrong_password(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        with pytest.raises(ValueError, match="Invalid credentials"):
            auth.login("admin", "wrong")

    def test_login_nonexistent_user(self, auth_env):
        auth, _ = auth_env
        with pytest.raises(ValueError, match="Invalid credentials"):
            auth.login("nobody", "pass")


class TestJWT:
    def test_decode_valid_token(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        token = auth.login("admin", "secret123")
        payload = auth.decode_token(token)
        assert payload["sub"] == "admin"
        assert payload["role"] == "admin"

    def test_decode_expired_token(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        token = auth._create_expired_token_for_test("admin", "admin")
        with pytest.raises(ValueError, match="expired"):
            auth.decode_token(token)

    def test_decode_tampered_token(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        token = auth.login("admin", "secret123")
        tampered = token[:-4] + "XXXX"
        with pytest.raises(ValueError, match="Invalid token"):
            auth.decode_token(tampered)


class TestResetPassword:
    def test_admin_can_reset_user_password(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        db.create_user("john", auth.hash_password("oldpass"), role="user")
        auth.reset_password("admin", "john", "newpass")
        token = auth.login("john", "newpass")
        assert isinstance(token, str)

    def test_admin_cannot_reset_own_password(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        with pytest.raises(ValueError, match="Cannot reset your own password"):
            auth.reset_password("admin", "admin", "newpass")

    def test_non_admin_cannot_reset_password(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        db.create_user("john", auth.hash_password("pass"), role="user")
        db.create_user("jane", auth.hash_password("pass"), role="user")
        with pytest.raises(PermissionError, match="Admin required"):
            auth.reset_password("john", "jane", "newpass")

    def test_reset_nonexistent_user_raises(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        with pytest.raises(ValueError, match="not found"):
            auth.reset_password("admin", "nobody", "newpass")

    def test_reset_password_too_short_raises(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        db.create_user("john", auth.hash_password("pass"), role="user")
        with pytest.raises(ValueError, match="at least 6 characters"):
            auth.reset_password("admin", "john", "abc")


class TestInviteAndRegister:
    def test_generate_invite(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        assert isinstance(code, str)
        assert db.validate_invite(code) is True

    def test_non_admin_cannot_invite(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        db.create_user("john", auth.hash_password("pass"), role="user")
        with pytest.raises(PermissionError, match="Admin required"):
            auth.generate_invite("john")

    def test_register_with_valid_invite(self, auth_env):
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        token = auth.register(invite_code=code, username="john", password="mypass")
        assert isinstance(token, str)
        user = db.get_user("john")
        assert user is not None
        assert user["role"] == "user"
        assert db.validate_invite(code) is False  # consumed

    def test_register_with_invalid_invite(self, auth_env):
        auth, _ = auth_env
        with pytest.raises(ValueError, match="Invalid or expired invite"):
            auth.register(invite_code="FAKE-CODE", username="john", password="pass")

    def test_reregister_existing_user_correct_password(self, auth_env):
        """Existing user + correct password + valid invite → token (acts as login)."""
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        code1 = auth.generate_invite("admin")
        auth.register(invite_code=code1, username="john", password="pass1")
        code2 = auth.generate_invite("admin")
        token = auth.register(invite_code=code2, username="john", password="pass1")
        assert isinstance(token, str)
        payload = auth.decode_token(token)
        assert payload["sub"] == "john"
        assert db.validate_invite(code2) is False  # invite consumed

    def test_reregister_existing_user_wrong_password(self, auth_env):
        """Existing user + wrong password + valid invite → rejected."""
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        code1 = auth.generate_invite("admin")
        auth.register(invite_code=code1, username="john", password="pass1")
        code2 = auth.generate_invite("admin")
        with pytest.raises(ValueError, match="Incorrect password"):
            auth.register(invite_code=code2, username="john", password="wrong")
        assert db.validate_invite(code2) is True  # invite NOT consumed

    def test_reregister_existing_user_invalid_invite(self, auth_env):
        """Existing user + correct password + bad invite → rejected."""
        auth, db = auth_env
        auth.setup_admin("admin", "secret123")
        code1 = auth.generate_invite("admin")
        auth.register(invite_code=code1, username="john", password="pass1")
        with pytest.raises(ValueError, match="Invalid or expired invite"):
            auth.register(invite_code="FAKE-CODE", username="john", password="pass1")

    def test_register_consumed_invite_fails(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        auth.register(invite_code=code, username="john", password="pass")
        with pytest.raises(ValueError, match="Invalid or expired invite"):
            auth.register(invite_code=code, username="jane", password="pass")


class TestBuildInviteURI:
    def test_uri_format(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        uri = auth.build_invite_uri(code, host="192.168.50.32", port=8080)
        assert uri.startswith("paradox://192.168.50.32:8080#")
        assert code in uri

    def test_uri_with_fingerprint(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        fp = "a1b2c3d4" * 8  # 64 hex chars
        uri = auth.build_invite_uri(code, host="192.168.50.32", port=8080, fingerprint=fp)
        assert uri.startswith("paradox://192.168.50.32:8080#")
        assert f"{code}:{fp}" in uri

    def test_parse_invite_uri(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        uri = auth.build_invite_uri(code, host="192.168.50.32", port=8080)
        parsed = auth.parse_invite_uri(uri)
        assert parsed["host"] == "192.168.50.32"
        assert parsed["port"] == 8080
        assert parsed["code"] == code
        assert parsed["fingerprint"] == ""

    def test_parse_invite_uri_with_fingerprint(self, auth_env):
        auth, _ = auth_env
        auth.setup_admin("admin", "secret123")
        code = auth.generate_invite("admin")
        fp = "a1b2c3d4" * 8
        uri = auth.build_invite_uri(code, host="192.168.50.32", port=8080, fingerprint=fp)
        parsed = auth.parse_invite_uri(uri)
        assert parsed["host"] == "192.168.50.32"
        assert parsed["port"] == 8080
        assert parsed["code"] == code
        assert parsed["fingerprint"] == fp
