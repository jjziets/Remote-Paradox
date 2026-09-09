"""WebSocket connection manager for broadcasting live alarm events."""

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Any

logger = logging.getLogger(__name__)


class ConnectionManager:
    def __init__(self, send_timeout: float = 2.0):
        self._connections: dict[Any, str] = {}  # websocket -> username
        self._send_locks: dict[Any, asyncio.Lock] = {}
        self._send_timeout = send_timeout

    @property
    def active_count(self) -> int:
        return len(self._connections)

    async def connect(self, websocket, username: str) -> None:
        await websocket.accept()
        self._connections[websocket] = username
        self._send_locks[websocket] = asyncio.Lock()

    def disconnect(self, websocket) -> None:
        self._connections.pop(websocket, None)
        self._send_locks.pop(websocket, None)

    async def send(self, websocket, data: dict) -> bool:
        lock = self._send_locks.get(websocket)
        if lock is None:
            return False

        async def send_locked():
            async with lock:
                if websocket in self._connections:
                    await websocket.send_json(data)

        try:
            await asyncio.wait_for(send_locked(), timeout=self._send_timeout)
            return websocket in self._connections
        except Exception:
            self.disconnect(websocket)
            try:
                await asyncio.wait_for(websocket.close(code=1011), timeout=self._send_timeout)
            except Exception:
                pass
            return False

    async def broadcast(self, data: dict) -> None:
        # A slow client cannot prevent the other devices receiving their snapshot.
        await asyncio.gather(*(self.send(ws, data) for ws in list(self._connections)))


class StatusDispatcher:
    """Move PAI thread callbacks onto the API loop and coalesce status bursts."""

    def __init__(
        self, publish: Callable[[], Awaitable[None]], *,
        retry_delay: float = 5.0, max_retries: int = 2,
    ):
        if retry_delay <= 0 or max_retries < 0:
            raise ValueError("Retry delay must be positive and retry count nonnegative")
        self._loop = asyncio.get_running_loop()
        self._publish = publish
        self._retry_delay = retry_delay
        self._max_retries = max_retries
        self._retry_at = 0.0
        self._pending = False
        self._closed = False
        self._task: asyncio.Task | None = None

    def notify(self) -> None:
        if not self._closed:
            try:
                self._loop.call_soon_threadsafe(self._request)
            except RuntimeError:
                pass  # The API loop has already shut down.

    def _request(self) -> None:
        if self._closed:
            return
        self._pending = True
        if self._task is None:
            self._task = self._loop.create_task(self._drain())

    async def _drain(self) -> None:
        failures = 0
        try:
            while self._pending and not self._closed:
                delay = self._retry_at - self._loop.time()
                if delay > 0:
                    await asyncio.sleep(delay)
                self._pending = False
                try:
                    await self._publish()
                except Exception:
                    logger.exception("Status publication failed")
                    self._pending = True
                    # Heartbeat notifications share this cooldown and retry task.
                    self._retry_at = self._loop.time() + self._retry_delay
                    failures += 1
                    if failures > self._max_retries:
                        return
                else:
                    failures = 0
                    self._retry_at = 0.0
        finally:
            self._task = None

    async def close(self) -> None:
        self._closed = True
        self._pending = False
        if self._task is not None:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
