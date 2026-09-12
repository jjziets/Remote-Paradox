package com.remoteparadox.watch.diagnostics

import com.remoteparadox.diagnostics.*
import com.remoteparadox.watch.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

class DiagnosticIntegrationTest {
    private val scope = "a".repeat(64)
    private val id = "11111111-1111-4111-8111-111111111111"

    @Test fun matchingRequestKeepsReportAndScopeAndUsesBoundedWatchSnapshot() {
        val log = DeviceLog("watch", "1.2.32", 26, 1000)
        val reply = diagnosticReply(WatchLogRequest(id, scope), scope) { log }
        assertEquals(WatchLogReply(id, scope, "included", log), reply)
        assertTrue(DiagnosticCodec.json.encodeToString(reply).toByteArray().size < 64 * 1024)
    }

    @Test fun signedOutOrOtherAccountNeverReadsOrReturnsSnapshot() {
        for (current in listOf(null, "b".repeat(64))) {
            val reply = diagnosticReply(WatchLogRequest(id, scope), current) { error("Must not export a different account") }
            assertEquals(WatchLogReply(id, scope, "scope_mismatch"), reply)
            assertFalse(DiagnosticCodec.json.encodeToString(reply).contains("events"))
        }
    }

    @Test fun watchStatusSummaryCannotExportPartitionZoneLabelsOrArbitraryMode() {
        val status = AlarmStatus(listOf(PartitionInfo(1, "secret partition", false, "secret mode",
            zones = listOf(ZoneInfo(1, "secret zone", true, true)))), true)
        val summary = DiagnosticEvents.summaries(status, "watch_tile").single()
        assertEquals("unknown", summary.mode)
        assertEquals(1, summary.partitionId)
        assertEquals(1, summary.openZones)
        assertEquals(1, summary.bypassedZones)
        val wire = DiagnosticCodec.json.encodeToString(summary.copy(processId = id, sequence = 1))
        assertFalse(wire.contains("secret"))
    }

    @Test fun ordinaryRotationKeepsExistingWatchApiInterceptorAndScopeChangeDropsLateEvents() = runTest {
        Diagnostics.initialize(Files.createTempDirectory("watch-diagnostics-test").toFile(), "watch", "1.2.32", 26)
        ClientDiagnostics.bind(null, null, null, null)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true}"))
            val base = server.url("/").toString()
            ClientDiagnostics.bind(base, "watch-account", "a".repeat(64), "first-token")
            val captured = ClientDiagnostics.capture()!!
            val api = ApiClient.create(base)
            ClientDiagnostics.record(DiagnosticEvent("tile_render", "watch_tile", success = false), captured)
            ClientDiagnostics.bind(base, "watch-account", "a".repeat(64), "rotated-token")
            assertTrue(ClientDiagnostics.matches(captured))
            api.disarm("Bearer rotated-token", ArmRequest("private-code", 1))
            val requestId = server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Diagnostic-Request-Id")!!
            assertEquals(requestId, UUID.fromString(requestId).toString())
            val log = Diagnostics.snapshot()
            assertTrue(log.events.any { it.kind == "tile_render" })
            assertTrue(log.events.any { it.kind == "http_finished" && it.requestId == requestId })
            ClientDiagnostics.bind(base, "other-account", "a".repeat(64), "other-token")
            ClientDiagnostics.record(DiagnosticEvent("ws_open", "ws"), captured)
            DiagnosticEvents.status(AlarmStatus(emptyList(), true), "ws", captured)
            assertTrue(Diagnostics.snapshot().events.isEmpty())
            assertFalse(ClientDiagnostics.matches(captured))
            val current = ClientDiagnostics.capture()!!
            ClientDiagnostics.bind(base, "other-account", "a".repeat(64), null)
            assertFalse(ClientDiagnostics.matches(current))
            assertNull(ClientDiagnostics.capture())
        }
    }

    @Test fun invalidWireCannotRequestUnboundedOrUnstructuredExport() {
        assertTrue(runCatching { DiagnosticCodec.json.decodeFromString<WatchLogRequest>(
            "{\"reportId\":\"$id\",\"scope\":\"$scope\",\"payload\":\"secret\"}") }.isFailure)
        assertTrue(runCatching { WatchLogRequest("invalid", scope) }.isFailure)
        assertTrue(runCatching { WatchLogRequest(id, "account name") }.isFailure)
    }
}
