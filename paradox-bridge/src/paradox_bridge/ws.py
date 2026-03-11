"""WebSocket connection manager for broadcasting live alarm events."""

import logging
from typing import Any

logger = logging.getLogger(__name__)


class ConnectionManager:
    def __init__(self):
        self._connections: dict[Any, str] = {}  # websocket -> username

    @property
    def active_count(self) -> int:
        return len(self._connections)

    async def connect(self, websocket, username: str) -> None:
        await websocket.accept()
        self._connections[websocket] = username

    def disconnect(self, websocket) -> None:
        self._connections.pop(websocket, None)

    async def broadcast(self, data: dict) -> None:
        dead = []
        for ws in list(self._connections):
            try:
                await ws.send_json(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)
