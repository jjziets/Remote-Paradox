package com.remoteparadox.app.diagnostics

import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticCoordinatorLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val account = "a".repeat(64)
    private val captureId = "11111111-1111-4111-8111-111111111111"
    private val phone = DeviceLog("phone", "1.2.31", 81, 1000)
    private val selected = DiagnosticTarget(DiagnosticSession(account, 1), "https://localhost/", "a".repeat(64), "Bearer test")
    private val savedBody get() = DiagnosticCodec.json.encodeToString(DiagnosticReport(captureId, "unavailable", phone))

    @Test fun blockedSnapshotCannotResurrectForgottenCaptureOrFinishReplacementOperation() = runBlocking {
        // Exercise both the old success and catch/finally paths after cancellation.
        for (throwAfterRelease in listOf(false, true)) {
            val root = SupervisorJob()
            val workers = CoroutineScope(root + Dispatchers.Default)
            val directory = temp.newFolder()
            val store = PendingDiagnosticStore(directory) { 1000L }
            val enteredSnapshot = CountDownLatch(1)
            val releaseSnapshot = CountDownLatch(1)
            val replacementStarted = CompletableDeferred<Unit>()
            val replacementWatch = CompletableDeferred<WatchCollection>()
            val snapshots = AtomicInteger()
            val captures = CopyOnWriteArrayList<String>()
            val uploads = CopyOnWriteArrayList<String>()
            val coordinator = DiagnosticReportCoordinator(workers, Any(), { selected }, { true }, store,
                watch = { id, _ ->
                    captures += id
                    if (captures.size == 1) WatchCollection("unavailable") else {
                        replacementStarted.complete(Unit)
                        replacementWatch.await()
                    }
                },
                snapshot = {
                    if (snapshots.incrementAndGet() == 1) {
                        enteredSnapshot.countDown()
                        check(releaseSnapshot.await(5, TimeUnit.SECONDS))
                        if (throwAfterRelease) throw IOException("snapshot failed after Forget")
                    }
                    phone
                },
                upload = { _, body -> uploads += body; throw IOException("offline") }, now = { 1000L })
            try {
                coordinator.send()
                assertTrue(enteredSnapshot.await(5, TimeUnit.SECONDS))
                val oldJob = root.children.single()
                coordinator.reset()
                assertEquals(DiagnosticReportState(), coordinator.state.value)
                coordinator.send()
                withTimeout(5000) { replacementStarted.await() }
                val replacementJob = root.children.single { it !== oldJob }
                assertTrue(coordinator.state.value.busy)
                releaseSnapshot.countDown()
                withTimeout(5000) { oldJob.join() }
                assertTrue("Old finally must not clear replacement busy", coordinator.state.value.busy)
                assertFalse(File(directory, "pending.json").exists())
                assertTrue(uploads.isEmpty())
                coordinator.send()
                assertEquals(2, captures.size)
                replacementWatch.complete(WatchCollection("unavailable"))
                withTimeout(5000) { replacementJob.join() }
                assertEquals(1, uploads.size)
                val saved = DiagnosticCodec.json.decodeFromString<DiagnosticReport>(store.read(account)!!.body)
                assertEquals(captures[1], saved.reportId)
                assertNotEquals(captures[0], saved.reportId)
                assertEquals(saved.reportId, coordinator.state.value.reportId)
                assertTrue(coordinator.state.value.pending)
                assertFalse(coordinator.state.value.busy)
            } finally {
                releaseSnapshot.countDown()
                withTimeout(5000) { root.cancelAndJoin() }
            }
        }
    }

    @Test fun startupDeletesOrphanTemporaryFileWithOrWithoutExpiredCapture() = runTest {
        for (withCapture in listOf(false, true)) {
            var now = 1000L
            val directory = temp.newFolder()
            val store = PendingDiagnosticStore(directory) { now }
            if (withCapture) store.save(PendingDiagnostic(account, now, savedBody))
            File(directory, "pending.tmp").writeText("interrupted private capture")
            now += REPORT_TTL_MS
            val coordinator = DiagnosticReportCoordinator(backgroundScope, Any(), { selected }, { true }, store,
                watch = { _, _ -> error("No collection during startup cleanup") }, snapshot = { phone },
                upload = { _, _ -> error("No automatic upload") }, now = { now })
            coordinator.startMaintenance()
            runCurrent()
            assertFalse(File(directory, "pending.tmp").exists())
            assertFalse(File(directory, "pending.json").exists())
            assertNull(coordinator.state.value.reportId)
            assertFalse(coordinator.state.value.pending)
        }
    }

    @Test fun foregroundCleanupRemovesExpiredCaptureAndOrphanAndClearsUi() = runTest {
        var now = 1000L
        val directory = temp.newFolder()
        val store = PendingDiagnosticStore(directory) { now }
        store.save(PendingDiagnostic(account, now, savedBody))
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> error("No collection on foreground") }, snapshot = { phone },
            upload = { _, _ -> error("No automatic upload") }, now = { now })
        coordinator.restore()
        advanceUntilIdle()
        assertEquals("Capture ID", coordinator.state.value.identifierLabel)
        assertEquals(captureId, coordinator.state.value.reportId)
        now += REPORT_TTL_MS
        File(directory, "pending.tmp").writeText("orphan")
        coordinator.restore()
        advanceUntilIdle()
        assertFalse(File(directory, "pending.tmp").exists())
        assertFalse(File(directory, "pending.json").exists())
        assertNull(coordinator.state.value.reportId)
        assertNull(coordinator.state.value.watchStatus)
        assertFalse(coordinator.state.value.pending)
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun idleExpiryRunsWithinOneMinuteWithoutReadOrRetryAndAllowsNewSend() = runTest {
        var now = 1000L
        var collections = 0
        val directory = temp.newFolder()
        val store = PendingDiagnosticStore(directory) { now }
        val coordinator = DiagnosticReportCoordinator(backgroundScope, Any(), { selected }, { true }, store,
            watch = { _, _ -> collections++; WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, _ -> throw IOException("offline") }, now = { now })
        coordinator.startMaintenance()
        coordinator.send()
        runCurrent()
        val firstId = coordinator.state.value.reportId
        assertTrue(coordinator.state.value.pending)
        now += REPORT_TTL_MS
        File(directory, "pending.tmp").writeText("orphan")
        advanceTimeBy(PENDING_CLEANUP_INTERVAL_MS - 1)
        runCurrent()
        assertTrue(coordinator.state.value.pending)
        advanceTimeBy(1)
        runCurrent()
        // File existence checks cannot themselves trigger the store's lazy expiry.
        assertFalse(File(directory, "pending.json").exists())
        assertFalse(File(directory, "pending.tmp").exists())
        assertNull(coordinator.state.value.reportId)
        assertFalse(coordinator.state.value.pending)
        coordinator.send()
        runCurrent()
        assertEquals(2, collections)
        assertTrue(coordinator.state.value.pending)
        assertNotEquals(firstId, coordinator.state.value.reportId)
    }

    @Test fun sendAlsoReconcilesExpiredPendingBeforeItsNextMaintenanceTick() = runTest {
        var now = 1000L
        var collections = 0
        val store = PendingDiagnosticStore(temp.newFolder()) { now }
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> collections++; WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, _ -> throw IOException("offline") }, now = { now })
        coordinator.send()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.pending)
        now += REPORT_TTL_MS
        coordinator.send()
        advanceUntilIdle()
        assertEquals(2, collections)
        assertTrue(coordinator.state.value.pending)
    }

    @Test fun activeUploadIsCancelledOnExpiryAndCannotLeaveBusyOrCaptureIdBehind() = runTest {
        var now = 1000L
        var cancelled = false
        val directory = temp.newFolder()
        val store = PendingDiagnosticStore(directory) { now }
        val coordinator = DiagnosticReportCoordinator(backgroundScope, Any(), { selected }, { true }, store,
            watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }, now = { now })
        coordinator.startMaintenance()
        coordinator.send()
        runCurrent()
        assertTrue(coordinator.state.value.busy)
        assertTrue(coordinator.state.value.pending)
        now += REPORT_TTL_MS
        advanceTimeBy(PENDING_CLEANUP_INTERVAL_MS)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(File(directory, "pending.json").exists())
        assertFalse(coordinator.state.value.pending)
        assertFalse(coordinator.state.value.busy)
        assertFalse(coordinator.state.value.receiptValidated)
        assertNull(coordinator.state.value.reportId)
    }

    @Test fun onlyValidatedReceiptChangesCaptureIdLabelToReportId() = runTest {
        val store = PendingDiagnosticStore(temp.newFolder()) { 1000L }
        var validReceipt = false
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, body ->
                val report = DiagnosticCodec.json.decodeFromString<DiagnosticReport>(body)
                DiagnosticCodec.json.encodeToString(DiagnosticReceipt(if (validReceipt) report.reportId else captureId,
                    1000, 2000, listOf("phone")))
            }, now = { 1000L })
        coordinator.send()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.pending)
        assertFalse(coordinator.state.value.receiptValidated)
        assertEquals("Capture ID", coordinator.state.value.identifierLabel)
        val savedId = coordinator.state.value.reportId
        validReceipt = true
        coordinator.send(retry = true)
        advanceUntilIdle()
        assertFalse(coordinator.state.value.pending)
        assertTrue(coordinator.state.value.receiptValidated)
        assertEquals("Report ID", coordinator.state.value.identifierLabel)
        assertEquals(savedId, coordinator.state.value.reportId)
        coordinator.send()
        assertNull(coordinator.state.value.reportId)
        assertFalse(coordinator.state.value.receiptValidated)
        advanceUntilIdle()
    }
}
