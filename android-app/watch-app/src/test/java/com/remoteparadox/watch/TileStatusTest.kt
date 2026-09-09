package com.remoteparadox.watch

import com.remoteparadox.watch.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TileStatusTest {
    private val status = AlarmStatus(partitions = emptyList(), connected = true)

    @Test fun `transport does not transparently replay a lost request`() {
        ApiClient.create("https://127.0.0.1:9433/")
        assertFalse(ApiClient.httpClient.retryOnConnectionFailure)
    }

    @Test fun `fresh app snapshot needs no network request`() = runTest {
        val snapshot = StatusSnapshot(status, 1_000)
        val result = loadTileStatus({ snapshot }, { error("Must not fetch") }, { error("Must not refresh") }, { 2_000 })
        assertEquals(snapshot, result.snapshot)
    }

    @Test fun `expired and future snapshots are never current`() {
        val snapshot = StatusSnapshot(status, 1_000)
        assertFalse(snapshot.isFresh(999))
        assertTrue(snapshot.isFresh(15_999))
        assertFalse(snapshot.isFresh(16_000))
    }

    @Test fun `network timeout is bounded and old status is not shown as live`() = runTest {
        val result = loadTileStatus(
            { StatusSnapshot(status, 0) },
            { delay(20_000); Response.success(status) },
            {},
            { 100_000 + testScheduler.currentTime },
        )
        assertEquals(2_000L, testScheduler.currentTime)
        assertNull(result.snapshot)
        assertEquals("Status unavailable\nOpen app", result.error)
    }

    @Test fun `failed fetch can use a still recent app update`() = runTest {
        val snapshot = StatusSnapshot(status, 1_000)
        val result = loadTileStatus({ snapshot }, { throw java.io.IOException() }, {}, { 10_000 })
        assertEquals(snapshot, result.snapshot)
    }

    @Test fun `unauthorized status refreshes credentials then retries read once`() = runTest {
        var reads = 0
        var refreshes = 0
        val result = loadTileStatus(nullCache, {
            if (++reads == 1) Response.error(401, "".toResponseBody()) else Response.success(status)
        }, { refreshes++ }, { 10_000 })
        assertEquals(2, reads)
        assertEquals(1, refreshes)
        assertEquals(status, result.snapshot?.status)
    }

    @Test fun `persistent unauthorized is not labelled offline`() = runTest {
        var reads = 0
        val result = loadTileStatus(nullCache, { reads++; Response.error(401, "".toResponseBody()) }, {}, { 10_000 })
        assertEquals(2, reads)
        assertNull(result.snapshot)
        assertEquals("Sign in required\nOpen app", result.error)
    }

    @Test fun `cancellation is not converted into a connection error`() = runTest {
        try {
            loadTileStatus(nullCache, { throw CancellationException() }, {})
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
    }

    @Test fun `false command result is not successful`() {
        assertFalse(Response.success(ActionResult(false, "disarm")).panelAccepted())
        assertTrue(Response.success(ActionResult(true, "disarm")).panelAccepted())
        assertFalse(Response.success<ActionResult>(null).panelAccepted())
    }

    @Test fun `app and tile cannot send overlapping commands`() {
        assertTrue(AlarmCommandGate.tryBegin())
        try {
            assertFalse(AlarmCommandGate.tryBegin())
        } finally { AlarmCommandGate.finish() }
        assertTrue(AlarmCommandGate.tryBegin())
        AlarmCommandGate.finish()
    }

    private val nullCache: () -> StatusSnapshot? = { null }

    @Test fun `only aging bearer-only credentials need proactive refresh`() {
        assertFalse(needsLegacyTokenRefresh(null, LEGACY_TOKEN_REFRESH_AGE_MS - 1))
        assertTrue(needsLegacyTokenRefresh(null, LEGACY_TOKEN_REFRESH_AGE_MS))
        assertTrue(needsLegacyTokenRefresh("", Long.MAX_VALUE))
        assertFalse(needsLegacyTokenRefresh("durable-refresh-token", Long.MAX_VALUE))
    }

    @Test fun `legacy refresh precedes status and also runs for fresh cached status`() = runTest {
        val calls = mutableListOf<String>()
        var bearer = "aging"
        val fetch: suspend () -> Response<AlarmStatus> = {
            assertEquals("renewed", bearer)
            calls.add("read")
            Response.success(status)
        }
        val refresh: suspend () -> Unit = { calls.add("refresh"); bearer = "renewed" }
        val result = loadTileStatus(nullCache, fetch, refresh, proactiveRefresh = true)
        assertEquals(listOf("refresh", "read"), calls)
        assertEquals(status, result.snapshot?.status)
        calls.clear()
        val snapshot = StatusSnapshot(status, 1_000)
        loadTileStatus({ snapshot }, fetch, refresh, { 2_000 }, proactiveRefresh = true)
        assertEquals(listOf("refresh"), calls)
    }

    @Test fun `proactive refresh and status share one two second deadline`() = runTest {
        var reads = 0
        val result = loadTileStatus(nullCache, {
            reads++
            delay(800)
            Response.success(status)
        }, { delay(1_500) }, proactiveRefresh = true)
        assertEquals(2_000L, testScheduler.currentTime)
        assertEquals(1, reads)
        assertNull(result.snapshot)
    }

    @Test fun `stalled proactive refresh cannot hold the tile or start a status read`() = runTest {
        val result = loadTileStatus(nullCache, { error("Must not fetch") }, {
            delay(20_000)
        }, proactiveRefresh = true)
        assertEquals(2_000L, testScheduler.currentTime)
        assertNull(result.snapshot)
    }

    @Test fun `failed proactive renewal does not cause a second refresh attempt`() = runTest {
        var refreshes = 0
        var reads = 0
        val result = loadTileStatus(nullCache, {
            reads++
            Response.error(401, "".toResponseBody())
        }, { refreshes++ }, proactiveRefresh = true)
        assertEquals(1, refreshes)
        assertEquals(1, reads)
        assertEquals("Sign in required\nOpen app", result.error)
    }
}
