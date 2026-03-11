"""Tests for the audit logging service."""

import pytest

from paradox_bridge.audit import AuditService
from paradox_bridge.database import Database


@pytest.fixture()
def audit_env(tmp_path):
    db = Database(str(tmp_path / "test.db"))
    db.init()
    svc = AuditService(db)
    yield svc, db
    db.close()


class TestAuditService:
    def test_record_and_retrieve(self, audit_env):
        svc, _ = audit_env
        svc.record("admin", "arm_away", "partition 1")
        logs = svc.recent(limit=10)
        assert len(logs) == 1
        assert logs[0]["username"] == "admin"
        assert logs[0]["action"] == "arm_away"

    def test_record_without_detail(self, audit_env):
        svc, _ = audit_env
        svc.record("admin", "login")
        logs = svc.recent(limit=10)
        assert logs[0]["detail"] is None

    def test_recent_by_user(self, audit_env):
        svc, _ = audit_env
        svc.record("admin", "arm_away")
        svc.record("john", "disarm")
        svc.record("admin", "arm_stay")
        logs = svc.recent(username="john", limit=10)
        assert len(logs) == 1
        assert logs[0]["username"] == "john"

    def test_recent_ordering_newest_first(self, audit_env):
        svc, _ = audit_env
        svc.record("a", "first")
        svc.record("a", "second")
        svc.record("a", "third")
        logs = svc.recent(limit=10)
        assert logs[0]["action"] == "third"
        assert logs[2]["action"] == "first"
