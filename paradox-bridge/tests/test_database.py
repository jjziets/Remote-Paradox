"""Tests for the SQLite database layer."""

import time
from datetime import datetime, timezone

import pytest

from paradox_bridge.database import Database


class TestDatabaseInit:
    def test_creates_tables_on_init(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        tables = db.list_tables()
        assert "users" in tables
        assert "invites" in tables
        assert "audit_log" in tables
        db.close()

    def test_idempotent_init(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.init()  # should not raise
        db.close()


class TestUsers:
    def test_create_user(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("admin", "hashed_pw", role="admin")
        user = db.get_user("admin")
        assert user is not None
        assert user["username"] == "admin"
        assert user["password_hash"] == "hashed_pw"
        assert user["role"] == "admin"
        db.close()

    def test_create_duplicate_user_raises(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("admin", "hashed_pw", role="admin")
        with pytest.raises(ValueError, match="already exists"):
            db.create_user("admin", "other_pw", role="user")
        db.close()

    def test_create_user_default_role(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("john", "hashed_pw")
        user = db.get_user("john")
        assert user["role"] == "user"
        db.close()

    def test_get_nonexistent_user(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        assert db.get_user("nobody") is None
        db.close()

    def test_list_users(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("admin", "h1", role="admin")
        db.create_user("john", "h2", role="user")
        users = db.list_users()
        assert len(users) == 2
        names = {u["username"] for u in users}
        assert names == {"admin", "john"}
        db.close()

    def test_delete_user(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("john", "h1")
        db.delete_user("john")
        assert db.get_user("john") is None
        db.close()

    def test_delete_nonexistent_user_raises(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        with pytest.raises(ValueError, match="not found"):
            db.delete_user("nobody")
        db.close()


class TestUpdatePassword:
    def test_update_password(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("john", "old_hash")
        db.update_password("john", "new_hash")
        user = db.get_user("john")
        assert user["password_hash"] == "new_hash"
        db.close()

    def test_update_password_nonexistent_user_raises(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        with pytest.raises(ValueError, match="not found"):
            db.update_password("nobody", "hash")
        db.close()


class TestInvites:
    @staticmethod
    def _db_with_admin(tmp_db):
        db = Database(tmp_db)
        db.init()
        db.create_user("admin", "hashed", role="admin")
        return db

    def test_create_invite(self, tmp_db):
        db = self._db_with_admin(tmp_db)
        code = db.create_invite(created_by="admin", expires_in_seconds=900)
        assert isinstance(code, str)
        assert len(code) >= 8
        db.close()

    def test_validate_invite_valid(self, tmp_db):
        db = self._db_with_admin(tmp_db)
        code = db.create_invite(created_by="admin", expires_in_seconds=900)
        assert db.validate_invite(code) is True
        db.close()

    def test_consume_invite(self, tmp_db):
        db = self._db_with_admin(tmp_db)
        code = db.create_invite(created_by="admin", expires_in_seconds=900)
        db.consume_invite(code, used_by="john")
        assert db.validate_invite(code) is False
        db.close()

    def test_expired_invite_invalid(self, tmp_db):
        db = self._db_with_admin(tmp_db)
        code = db.create_invite(created_by="admin", expires_in_seconds=0)
        time.sleep(0.1)
        assert db.validate_invite(code) is False
        db.close()

    def test_validate_nonexistent_invite(self, tmp_db):
        db = self._db_with_admin(tmp_db)
        assert db.validate_invite("FAKE-CODE") is False
        db.close()


class TestAuditLog:
    def test_log_action(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.log_action(username="admin", action="arm_away", detail="partition 1")
        logs = db.get_audit_log(limit=10)
        assert len(logs) == 1
        assert logs[0]["username"] == "admin"
        assert logs[0]["action"] == "arm_away"
        assert logs[0]["detail"] == "partition 1"
        assert "timestamp" in logs[0]
        db.close()

    def test_log_ordering(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.log_action(username="admin", action="arm_away")
        db.log_action(username="john", action="disarm")
        logs = db.get_audit_log(limit=10)
        assert len(logs) == 2
        assert logs[0]["action"] == "disarm"  # most recent first
        assert logs[1]["action"] == "arm_away"
        db.close()

    def test_log_limit(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        for i in range(20):
            db.log_action(username="admin", action=f"action_{i}")
        logs = db.get_audit_log(limit=5)
        assert len(logs) == 5
        db.close()

    def test_log_filter_by_user(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.log_action(username="admin", action="arm_away")
        db.log_action(username="john", action="disarm")
        logs = db.get_audit_log(username="john", limit=10)
        assert len(logs) == 1
        assert logs[0]["username"] == "john"
        db.close()


class TestEventPersistence:
    def test_events_table_created(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        tables = db.list_tables()
        assert "events" in tables
        db.close()

    def test_insert_event(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.insert_event(
            etype="zone", label="Front Door", prop="open",
            value="true", timestamp="2026-03-09T10:00:00",
        )
        events = db.get_events(limit=10)
        assert len(events) == 1
        assert events[0]["type"] == "zone"
        assert events[0]["label"] == "Front Door"
        assert events[0]["property"] == "open"
        assert events[0]["value"] == "true"
        assert events[0]["timestamp"] == "2026-03-09T10:00:00"
        db.close()

    def test_get_events_ordering(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.insert_event("zone", "Door", "open", "true", "2026-03-09T10:00:00")
        db.insert_event("zone", "Window", "open", "true", "2026-03-09T10:01:00")
        events = db.get_events(limit=10)
        assert len(events) == 2
        assert events[0]["label"] == "Window"  # most recent first
        assert events[1]["label"] == "Door"
        db.close()

    def test_get_events_limit(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        for i in range(20):
            db.insert_event("zone", f"Zone {i}", "open", "true", f"2026-03-09T10:{i:02d}:00")
        events = db.get_events(limit=5)
        assert len(events) == 5
        db.close()

    def test_purge_old_events(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.insert_event("zone", "Old", "open", "true", "2025-01-01T00:00:00")
        db.insert_event("zone", "Recent", "open", "true", "2026-03-09T10:00:00")
        purged = db.purge_old_events(days=90)
        assert purged >= 1
        events = db.get_events(limit=10)
        assert len(events) == 1
        assert events[0]["label"] == "Recent"
        db.close()

    def test_purge_returns_zero_when_nothing_old(self, tmp_db):
        db = Database(tmp_db)
        db.init()
        db.insert_event("zone", "Recent", "open", "true", "2026-03-09T10:00:00")
        purged = db.purge_old_events(days=90)
        assert purged == 0
        db.close()
