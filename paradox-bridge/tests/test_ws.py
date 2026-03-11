"""Tests for the WebSocket connection manager."""

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from paradox_bridge.ws import ConnectionManager


class TestConnectionManager:
    @pytest.fixture()
    def mgr(self):
        return ConnectionManager()

    def test_starts_empty(self, mgr):
        assert mgr.active_count == 0

    @pytest.mark.asyncio
    async def test_connect_adds_client(self, mgr):
        ws = AsyncMock()
        await mgr.connect(ws, username="admin")
        assert mgr.active_count == 1

    @pytest.mark.asyncio
    async def test_disconnect_removes_client(self, mgr):
        ws = AsyncMock()
        await mgr.connect(ws, username="admin")
        mgr.disconnect(ws)
        assert mgr.active_count == 0

    @pytest.mark.asyncio
    async def test_broadcast_sends_to_all(self, mgr):
        ws1 = AsyncMock()
        ws2 = AsyncMock()
        await mgr.connect(ws1, username="admin")
        await mgr.connect(ws2, username="john")
        await mgr.broadcast({"event": "zone_open", "zone_id": 1})
        ws1.send_json.assert_called_once_with({"event": "zone_open", "zone_id": 1})
        ws2.send_json.assert_called_once_with({"event": "zone_open", "zone_id": 1})

    @pytest.mark.asyncio
    async def test_broadcast_removes_dead_connections(self, mgr):
        ws_good = AsyncMock()
        ws_dead = AsyncMock()
        ws_dead.send_json.side_effect = Exception("connection closed")
        await mgr.connect(ws_good, username="admin")
        await mgr.connect(ws_dead, username="john")
        assert mgr.active_count == 2
        await mgr.broadcast({"event": "test"})
        assert mgr.active_count == 1

    @pytest.mark.asyncio
    async def test_disconnect_nonexistent_is_safe(self, mgr):
        ws = AsyncMock()
        mgr.disconnect(ws)  # should not raise
        assert mgr.active_count == 0
