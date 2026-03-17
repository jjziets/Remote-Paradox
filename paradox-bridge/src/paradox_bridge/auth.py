"""Authentication: JWT tokens, login, registration via invite codes."""

from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

import bcrypt
from jose import JWTError, jwt

from paradox_bridge.config import AppConfig
from paradox_bridge.database import Database

_JWT_ALGORITHM = "HS256"


class AuthService:
    def __init__(self, db: Database, config: AppConfig):
        self._db = db
        self._config = config

    # ── Password hashing ──

    @staticmethod
    def hash_password(plain: str) -> str:
        return bcrypt.hashpw(plain.encode(), bcrypt.gensalt()).decode()

    @staticmethod
    def verify_password(plain: str, hashed: str) -> bool:
        return bcrypt.checkpw(plain.encode(), hashed.encode())

    # ── Admin bootstrap ──

    def setup_admin(self, username: str, password: str) -> None:
        if self._db.get_user(username) is not None:
            return
        self._db.create_user(username, self.hash_password(password), role="admin")

    # ── Login ──

    def login(self, username: str, password: str) -> str:
        user = self._db.get_user(username)
        if user is None or not self.verify_password(password, user["password_hash"]):
            raise ValueError("Invalid credentials")
        return self._create_token(username, user["role"])

    # ── JWT ──

    def _create_token(self, username: str, role: str) -> str:
        now = datetime.now(timezone.utc)
        expire = now + timedelta(hours=self._config.jwt_expiry_hours)
        payload = {
            "sub": username,
            "role": role,
            "iat": now,
            "exp": expire,
        }
        return jwt.encode(payload, self._config.jwt_secret, algorithm=_JWT_ALGORITHM)

    def _create_expired_token_for_test(self, username: str, role: str) -> str:
        """Create a token that's already expired. Test helper only."""
        now = datetime.now(timezone.utc) - timedelta(hours=1)
        payload = {"sub": username, "role": role, "iat": now, "exp": now}
        return jwt.encode(payload, self._config.jwt_secret, algorithm=_JWT_ALGORITHM)

    def decode_token(self, token: str) -> dict:
        try:
            payload = jwt.decode(
                token,
                self._config.jwt_secret,
                algorithms=[_JWT_ALGORITHM],
                options={"require_exp": True, "require_sub": True},
            )
            return payload
        except JWTError as exc:
            msg = str(exc).lower()
            if "expired" in msg:
                raise ValueError("Token expired") from exc
            raise ValueError("Invalid token") from exc

    # ── Password Reset ──

    def reset_password(self, admin_username: str, target_username: str, new_password: str) -> None:
        admin = self._db.get_user(admin_username)
        if admin is None or admin["role"] != "admin":
            raise PermissionError("Admin required")
        if admin_username == target_username:
            raise ValueError("Cannot reset your own password")
        if len(new_password) < 6:
            raise ValueError("Password must be at least 6 characters")
        self._db.update_password(target_username, self.hash_password(new_password))

    # ── Invites ──

    def generate_invite(self, requesting_username: str) -> str:
        user = self._db.get_user(requesting_username)
        if user is None or user["role"] != "admin":
            raise PermissionError("Admin required")
        return self._db.create_invite(
            created_by=requesting_username,
            expires_in_seconds=self._config.invite_expiry_seconds,
        )

    def register(self, invite_code: str, username: str, password: str) -> str:
        if not self._db.validate_invite(invite_code):
            raise ValueError("Invalid or expired invite")
        existing = self._db.get_user(username)
        if existing is not None:
            if not self.verify_password(password, existing["password_hash"]):
                raise ValueError("Incorrect password for existing user")
            self._db.consume_invite(invite_code, used_by=username)
            return self._create_token(username, existing["role"])
        self._db.create_user(username, self.hash_password(password), role="user")
        self._db.consume_invite(invite_code, used_by=username)
        return self._create_token(username, "user")

    # ── Invite URI ──

    @staticmethod
    def build_invite_uri(
        code: str, host: str, port: int, fingerprint: str = "",
    ) -> str:
        fragment = f"{code}:{fingerprint}" if fingerprint else code
        return f"paradox://{host}:{port}#{fragment}"

    @staticmethod
    def parse_invite_uri(uri: str) -> dict:
        without_scheme = uri.replace("paradox://", "", 1)
        host_port, fragment = without_scheme.split("#", 1)
        host, port_str = host_port.rsplit(":", 1)
        parts = fragment.split(":", 1)
        code = parts[0]
        fingerprint = parts[1] if len(parts) > 1 else ""
        return {
            "host": host,
            "port": int(port_str),
            "code": code,
            "fingerprint": fingerprint,
        }
