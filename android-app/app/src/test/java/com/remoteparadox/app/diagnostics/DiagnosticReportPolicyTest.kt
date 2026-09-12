package com.remoteparadox.app.diagnostics

import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticReportPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val account = "a".repeat(64)
    private val phone = DeviceLog("phone", "1.2.31", 81, 1000)
    private val watch = DeviceLog("watch", "1.2.32", 26, 1000)

    private class Transport : DiagnosticWearTransport {
        var connected = listOf("watch")
        var listener: ((String, String, ByteArray) -> Unit)? = null
        val sent = mutableListOf<String>()
        var removed = false
        var onSend: suspend (String, ByteArray) -> Unit = { _, _ -> }
        override suspend fun nodes() = connected
        override suspend fun addListener(listener: (String, String, ByteArray) -> Unit) { this.listener = listener }
        override fun removeListener(listener: (String, String, ByteArray) -> Unit) { removed = true; this.listener = null }
        override suspend fun send(node: String, payload: ByteArray) {
            checkNotNull(listener)
            sent += node
            onSend(node, payload)
        }
        fun reply(node: String, value: WatchLogReply, path: String = RESPONSE_PATH) {
            listener?.invoke(node, path, DiagnosticCodec.json.encodeToString(value).toByteArray())
        }
    }

    @Test fun unavailableWatchHasTenSecondDeadlineAndRemovesListener() = runTest {
        val transport = Transport().apply { onSend = { _, _ -> awaitCancellation() } }
        val start = currentTime
        assertEquals("unavailable", WatchReportCollector(transport).collect(id, account).status)
        assertEquals(10_000, currentTime - start)
        assertTrue(transport.removed)
    }

    @Test fun listenerPrecedesSendAndFirstValidReplyDoesNotWaitForHungNode() = runTest {
        val transport = Transport().apply {
            connected = listOf("good", "hung", "third", "fourth", "fifth", "good")
            onSend = { node, bytes ->
                assertTrue(bytes.size <= 256)
                val request = DiagnosticCodec.json.decodeFromString<WatchLogRequest>(bytes.decodeToString())
                assertEquals(id, request.reportId)
                if (node == "good") reply(node, WatchLogReply(id, account, "included", watch))
                else awaitCancellation()
            }
        }
        val result = WatchReportCollector(transport).collect(id, account)
        assertEquals(watch, result.log)
        assertTrue(currentTime < 10_000)
        assertTrue(transport.sent.size <= 4)
        assertFalse("fifth" in transport.sent)
        assertTrue(transport.removed)
    }

    @Test fun wrongNodeCorrelationScopePathAndOversizedReplyCannotSupplyLog() = runTest {
        val transport = Transport().apply {
            onSend = { node, _ ->
                reply("intruder", WatchLogReply(id, account, "included", watch))
                reply(node, WatchLogReply(UUID.randomUUID().toString(), account, "included", watch))
                reply(node, WatchLogReply(id, "b".repeat(64), "included", watch))
                reply(node, WatchLogReply(id, account, "included", watch), "/other")
                listener?.invoke(node, RESPONSE_PATH, ByteArray(64 * 1024 + 1))
                listener?.invoke(node, RESPONSE_PATH, byteArrayOf(0xc3.toByte(), 0x28))
            }
        }
        assertEquals(WatchCollection("unavailable"), WatchReportCollector(transport).collect(id, account))
    }

    @Test fun matchingScopeMismatchIsHonestPartialResult() = runTest {
        val transport = Transport().apply { onSend = { node, _ -> reply(node, WatchLogReply(id, account, "scope_mismatch")) } }
        assertEquals(WatchCollection("scope_mismatch"), WatchReportCollector(transport).collect(id, account))
    }

    @Test fun cancellationAndMissingWearServiceRemoveListener() = runTest {
        val transport = Transport()
        val job = launch { WatchReportCollector(transport).collect(id, account) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(transport.removed)
        val absent = object : DiagnosticWearTransport {
            var removed = false
            override suspend fun nodes(): List<String> = error("Wear service absent")
            override suspend fun addListener(listener: (String, String, ByteArray) -> Unit) { throw IOException() }
            override fun removeListener(listener: (String, String, ByteArray) -> Unit) { removed = true }
            override suspend fun send(node: String, payload: ByteArray) = Unit
        }
        assertEquals("unavailable", WatchReportCollector(absent).collect(id, account).status)
        assertTrue(absent.removed)
    }

    @Test fun pendingCaptureExpiresAt24HoursAndCannotCrossAccounts() {
        var now = 1000L
        val store = PendingDiagnosticStore(temp.newFolder()) { now }
        val saved = PendingDiagnostic(account, now, "immutable")
        store.save(saved)
        assertEquals(saved, store.read(account))
        assertNull(store.read("b".repeat(64)))
        assertNull(store.read(account))
        store.save(saved)
        now += REPORT_TTL_MS
        assertNull(store.read(account))
    }

    @Test fun receiptMustMatchReportSourcesAndRetentionWindow() {
        val report = DiagnosticReport(id, "included", phone, watch)
        fun receipt(reportId: String = id, sources: List<String> = listOf("phone", "watch"), expires: Long = 2000) =
            DiagnosticCodec.json.encodeToString(DiagnosticReceipt(reportId, 1000, expires, sources))
        assertEquals(id, validateReceipt(receipt(), report).reportId)
        assertThrows(IllegalArgumentException::class.java) { validateReceipt(receipt(UUID.randomUUID().toString()), report) }
        assertThrows(IllegalArgumentException::class.java) { validateReceipt(receipt(sources = listOf("phone")), report) }
        assertThrows(IllegalArgumentException::class.java) { validateReceipt(receipt(expires = 1001 + REPORT_TTL_MS), report) }
    }

    @Test fun offlineRetryUsesIdenticalBodyAndNeverRecollects() = runTest {
        val store = PendingDiagnosticStore(temp.newFolder()) { 1000L }
        val selected = DiagnosticTarget(DiagnosticSession(account, 1), "https://localhost/", "a".repeat(64), "Bearer test")
        var collections = 0
        var online = false
        val bodies = mutableListOf<String>()
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { it == selected }, store,
            watch = { _, _ -> collections++; WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, body ->
                bodies += body
                if (!online) throw IOException("offline")
                val report = DiagnosticCodec.json.decodeFromString<DiagnosticReport>(body)
                DiagnosticCodec.json.encodeToString(DiagnosticReceipt(report.reportId, 1000, 2000, listOf("phone")))
            }, now = { 1000L })
        coordinator.send()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.pending)
        assertNotNull(coordinator.state.value.reportId)
        assertFalse(coordinator.state.value.busy)
        online = true
        coordinator.send(retry = true)
        advanceUntilIdle()
        assertEquals(1, collections)
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        assertFalse(coordinator.state.value.pending)
        assertNull(store.read(account))
        assertEquals("Report received by server", coordinator.state.value.message)
    }

    @Test fun deviceSwitchMidCollectionCancelsWithoutSavingOrUploading() = runTest {
        val store = PendingDiagnosticStore(temp.newFolder())
        var current = true
        var uploads = 0
        var cancelled = false
        val selected = DiagnosticTarget(DiagnosticSession(account, 1), "https://localhost/", "a".repeat(64), "Bearer test")
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { current }, store,
            watch = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }, snapshot = { phone },
            upload = { _, _ -> uploads++; error("must not upload") })
        coordinator.send()
        runCurrent()
        current = false
        coordinator.reset()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(0, uploads)
        assertNull(store.read(account))
        assertEquals(DiagnosticReportState(), coordinator.state.value)
    }

    @Test fun wrongReceiptKeepsPendingAndResetCancelsUploadAndErasesIt() = runTest {
        val store = PendingDiagnosticStore(temp.newFolder()) { 1000L }
        val selected = DiagnosticTarget(DiagnosticSession(account, 1), "https://localhost/", "a".repeat(64), "Bearer test")
        var hang = false
        var cancelled = false
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, _ ->
                if (hang) try { awaitCancellation() } finally { cancelled = true }
                DiagnosticCodec.json.encodeToString(DiagnosticReceipt(id, 1000, 2000, listOf("phone")))
            }, now = { 1000L })
        coordinator.send()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.pending)
        hang = true
        coordinator.send(retry = true)
        runCurrent()
        coordinator.reset()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertNull(store.read(account))
    }

    @Test fun restartRestoresSameImmutableCaptureWithoutUploadingAndForgetErasesIt() = runTest {
        val directory = temp.newFolder()
        val body = DiagnosticCodec.json.encodeToString(DiagnosticReport(id, "unavailable", phone))
        PendingDiagnosticStore(directory) { 1000L }.save(PendingDiagnostic(account, 1000, body))
        val store = PendingDiagnosticStore(directory) { 1001L }
        val selected = DiagnosticTarget(DiagnosticSession(account, 2), "https://localhost/", "a".repeat(64), "Bearer renewed")
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> error("No collection on restart") }, snapshot = { error("No snapshot on restart") },
            upload = { _, _ -> error("No automatic upload on restart") })
        coordinator.restore()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.pending)
        assertEquals(id, coordinator.state.value.reportId)
        assertEquals(body, store.read(account)?.body)
        coordinator.reset()
        assertNull(PendingDiagnosticStore(directory) { 1002L }.read(account))
    }

    @Test fun storageFailureNeverUploadsOrClaimsSavedOrReceived() = runTest {
        val selected = DiagnosticTarget(DiagnosticSession(account, 1), "https://localhost/", "a".repeat(64), "Bearer test")
        val store = PendingDiagnosticStore(temp.newFile()) { 1000L }
        var uploads = 0
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, _ -> uploads++; error("Cannot upload an unsaved capture") }, now = { 1000L })
        coordinator.send()
        advanceUntilIdle()
        assertEquals(0, uploads)
        assertFalse(coordinator.state.value.pending)
        assertFalse(coordinator.state.value.busy)
        assertEquals("Could not save the report, or the saved report expired.", coordinator.state.value.message)
    }
}
