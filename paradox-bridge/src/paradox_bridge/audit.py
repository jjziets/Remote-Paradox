"""Audit logging — records who did what and when."""

from typing import Optional

from paradox_bridge.database import Database


class AuditService:
    def __init__(self, db: Database):
        self._db = db

    def record(self, username: str, action: str, detail: Optional[str] = None, device: Optional[str] = None) -> None:
        self._db.log_action(username=username, action=action, detail=detail, device=device)

    def recent(self, limit: int = 50, username: Optional[str] = None) -> list[dict]:
        return self._db.get_audit_log(limit=limit, username=username)
