"""SQLite database layer for users, invites, and audit logs."""

import secrets
import sqlite3
import time
from datetime import datetime, timezone
from typing import Optional


class Database:
    def __init__(self, db_path: str):
        self._path = db_path
        self._conn: Optional[sqlite3.Connection] = None

    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            self._conn = sqlite3.connect(self._path, check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
        return self._conn

    def init(self) -> None:
        c = self.conn
        c.executescript("""
            CREATE TABLE IF NOT EXISTS users (
                username    TEXT PRIMARY KEY,
                password_hash TEXT NOT NULL,
                role        TEXT NOT NULL DEFAULT 'user',
                created_at  TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS invites (
                code        TEXT PRIMARY KEY,
                created_by  TEXT NOT NULL,
                created_at  REAL NOT NULL,
                expires_at  REAL NOT NULL,
                used_by     TEXT,
                used_at     REAL,
                FOREIGN KEY (created_by) REFERENCES users(username)
            );

            CREATE TABLE IF NOT EXISTS audit_log (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp   TEXT NOT NULL,
                username    TEXT NOT NULL,
                action      TEXT NOT NULL,
                detail      TEXT
            );

            CREATE TABLE IF NOT EXISTS events (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                type      TEXT NOT NULL,
                label     TEXT NOT NULL,
                property  TEXT NOT NULL,
                value     TEXT NOT NULL,
                timestamp TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_events_ts ON events(timestamp);
        """)
        c.commit()

    def close(self) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None

    def list_tables(self) -> list[str]:
        rows = self.conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        ).fetchall()
        return [r["name"] for r in rows]

    # ── Users ──

    def create_user(self, username: str, password_hash: str, role: str = "user") -> None:
        existing = self.get_user(username)
        if existing is not None:
            raise ValueError(f"User '{username}' already exists")
        now = datetime.now(timezone.utc).isoformat()
        self.conn.execute(
            "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
            (username, password_hash, role, now),
        )
        self.conn.commit()

    def get_user(self, username: str) -> Optional[dict]:
        row = self.conn.execute(
            "SELECT * FROM users WHERE username = ?", (username,)
        ).fetchone()
        return dict(row) if row else None

    def list_users(self) -> list[dict]:
        rows = self.conn.execute("SELECT * FROM users ORDER BY created_at").fetchall()
        return [dict(r) for r in rows]

    def update_user_role(self, username: str, role: str) -> None:
        if role not in ("admin", "user"):
            raise ValueError(f"Invalid role: {role}")
        existing = self.get_user(username)
        if existing is None:
            raise ValueError(f"User '{username}' not found")
        if existing["role"] == "admin" and role != "admin" and self.admin_count() <= 1:
            raise ValueError("Cannot demote the last admin")
        self.conn.execute(
            "UPDATE users SET role = ? WHERE username = ?", (role, username),
        )
        self.conn.commit()

    def admin_count(self) -> int:
        row = self.conn.execute(
            "SELECT COUNT(*) as cnt FROM users WHERE role = 'admin'"
        ).fetchone()
        return row["cnt"] if row else 0

    def update_password(self, username: str, new_password_hash: str) -> None:
        existing = self.get_user(username)
        if existing is None:
            raise ValueError(f"User '{username}' not found")
        self.conn.execute(
            "UPDATE users SET password_hash = ? WHERE username = ?",
            (new_password_hash, username),
        )
        self.conn.commit()

    def delete_user(self, username: str) -> None:
        existing = self.get_user(username)
        if existing is None:
            raise ValueError(f"User '{username}' not found")
        if existing["role"] == "admin" and self.admin_count() <= 1:
            raise ValueError("Cannot delete the last admin")
        self.conn.execute("DELETE FROM users WHERE username = ?", (username,))
        self.conn.commit()

    # ── Invites ──

    def create_invite(self, created_by: str, expires_in_seconds: int = 900) -> str:
        code = secrets.token_hex(4).upper()
        code = f"{code[:4]}-{code[4:]}"
        now = time.time()
        self.conn.execute(
            "INSERT INTO invites (code, created_by, created_at, expires_at) VALUES (?, ?, ?, ?)",
            (code, created_by, now, now + expires_in_seconds),
        )
        self.conn.commit()
        return code

    def validate_invite(self, code: str) -> bool:
        row = self.conn.execute(
            "SELECT * FROM invites WHERE code = ? AND used_by IS NULL AND expires_at > ?",
            (code, time.time()),
        ).fetchone()
        return row is not None

    def consume_invite(self, code: str, used_by: str) -> None:
        self.conn.execute(
            "UPDATE invites SET used_by = ?, used_at = ? WHERE code = ?",
            (used_by, time.time(), code),
        )
        self.conn.commit()

    # ── Audit Log ──

    def log_action(self, username: str, action: str, detail: Optional[str] = None, device: Optional[str] = None) -> None:
        now = datetime.now(timezone.utc).isoformat()
        self._ensure_audit_device_column()
        self.conn.execute(
            "INSERT INTO audit_log (timestamp, username, action, detail, device) VALUES (?, ?, ?, ?, ?)",
            (now, username, action, detail, device),
        )
        self.conn.commit()

    def _ensure_audit_device_column(self) -> None:
        try:
            self.conn.execute("SELECT device FROM audit_log LIMIT 1")
        except sqlite3.OperationalError:
            self.conn.execute("ALTER TABLE audit_log ADD COLUMN device TEXT")
            self.conn.commit()

    def get_audit_log(
        self, limit: int = 50, username: Optional[str] = None
    ) -> list[dict]:
        if username:
            rows = self.conn.execute(
                "SELECT * FROM audit_log WHERE username = ? ORDER BY id DESC LIMIT ?",
                (username, limit),
            ).fetchall()
        else:
            rows = self.conn.execute(
                "SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(r) for r in rows]

    # ── Alarm Events ──

    def insert_event(
        self, etype: str, label: str, prop: str, value: str, timestamp: str,
    ) -> None:
        self.conn.execute(
            "INSERT INTO events (type, label, property, value, timestamp) VALUES (?, ?, ?, ?, ?)",
            (etype, label, prop, value, timestamp),
        )
        self.conn.commit()

    def get_events(self, limit: int = 50) -> list[dict]:
        rows = self.conn.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
        return [dict(r) for r in rows]

    def purge_old_events(self, days: int = 90) -> int:
        from datetime import timedelta
        cutoff = (
            datetime.now(timezone.utc) - timedelta(days=days)
        ).strftime("%Y-%m-%dT%H:%M:%S")
        cursor = self.conn.execute(
            "DELETE FROM events WHERE timestamp < ?", (cutoff,)
        )
        self.conn.commit()
        return cursor.rowcount
