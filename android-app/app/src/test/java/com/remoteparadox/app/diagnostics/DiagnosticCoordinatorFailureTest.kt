package com.remoteparadox.app.diagnostics

import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticCoordinatorFailureTest {
    @get:Rule val temp = TemporaryFolder()
    private val account = "a".repeat(64)
    private val selected = DiagnosticTarget(DiagnosticSession(account, 1), "https://localhost/", "a".repeat(64), "Bearer private-token")
    private val phone = DeviceLog("phone", "1.2.33", 82, 1000)

    @Test fun everyClassifiedFailureKeepsImmutableCaptureAndOnlyRetriesOnRequest() = runTest {
        for (failure in DiagnosticUploadFailure.entries) {
            var now = 1000L
            var collections = 0
            var succeed = false
            val bodies = mutableListOf<String>()
            val store = PendingDiagnosticStore(temp.newFolder()) { now }
            val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { it == selected }, store,
                watch = { _, _ -> collections++; WatchCollection("unavailable") }, snapshot = { phone },
                upload = { _, body ->
                    bodies += body
                    if (!succeed) throw DiagnosticUploadException(failure)
                    val report = DiagnosticCodec.json.decodeFromString<DiagnosticReport>(body)
                    DiagnosticCodec.json.encodeToString(DiagnosticReceipt(report.reportId, 1000, 2000, listOf("phone")))
                }, now = { now })
            coordinator.send()
            advanceUntilIdle()
            val saved = store.read(account)!!
            val captureId = coordinator.state.value.reportId
            assertEquals(failure.savedCaptureMessage(), coordinator.state.value.message)
            assertTrue(coordinator.state.value.pending)
            assertFalse(coordinator.state.value.busy)
            assertFalse(coordinator.state.value.receiptValidated)
            assertEquals("Capture ID", coordinator.state.value.identifierLabel)
            now += 60_000
            coordinator.restore()
            coordinator.send() // Pending reports cannot be silently replaced or retried.
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, bodies.size)
            assertEquals(saved, store.read(account))
            coordinator.send(retry = true)
            advanceUntilIdle()
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
            assertEquals(saved, store.read(account)) // Retry must not extend the retention window.
            assertEquals(captureId, coordinator.state.value.reportId)
            succeed = true
            coordinator.send(retry = true)
            advanceUntilIdle()
            assertEquals(1, collections)
            assertEquals(3, bodies.size)
            assertTrue(bodies.all { it == saved.body })
            assertNull(store.read(account))
            assertFalse(coordinator.state.value.pending)
            assertTrue(coordinator.state.value.receiptValidated)
            assertEquals("Report received by server", coordinator.state.value.message)
        }
    }

    @Test fun actualMissingAndMalformedPinFailuresReachUiWithoutLosingCapture() = runTest {
        for ((pin, failure) in listOf("" to DiagnosticUploadFailure.MISSING_PIN, "invalid" to DiagnosticUploadFailure.MALFORMED_PIN)) {
            val target = selected.copy(pin = pin)
            val store = PendingDiagnosticStore(temp.newFolder()) { 1000L }
            val coordinator = DiagnosticReportCoordinator(this, Any(), { target }, { it == target }, store,
                watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
                upload = ::uploadDiagnostic, now = { 1000L })
            coordinator.send()
            advanceUntilIdle()
            assertEquals(failure.savedCaptureMessage(), coordinator.state.value.message)
            assertTrue(coordinator.state.value.pending)
            assertNotNull(store.read(account))
            assertFalse(coordinator.state.value.receiptValidated)
        }
    }

    @Test fun malformedUncorrelatedOrInvalidReceiptNeverClaimsServerReceiptOrExposesBody() = runTest {
        val privateReceipt = "Bearer private-token private-account https://private-server/"
        val store = PendingDiagnosticStore(temp.newFolder()) { 1000L }
        var receipt: (DiagnosticReport) -> String = { privateReceipt }
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, body -> receipt(DiagnosticCodec.json.decodeFromString<DiagnosticReport>(body)) }, now = { 1000L })
        coordinator.send()
        advanceUntilIdle()
        val saved = store.read(account)!!
        val cases: List<(DiagnosticReport) -> String> = listOf(
            { "" },
            { "{}" },
            { DiagnosticCodec.json.encodeToString(DiagnosticReceipt("11111111-1111-4111-8111-111111111111", 1000, 2000, listOf("phone"))) },
            { DiagnosticCodec.json.encodeToString(DiagnosticReceipt(it.reportId, 1000, 2000, listOf("phone", "watch"))) },
            { report -> buildJsonObject {
                put("reportId", report.reportId)
                put("receivedAtMs", 1000)
                put("expiresAtMs", 2000)
                put("sources", JsonArray(listOf(JsonPrimitive("phone"), JsonPrimitive("phone"))))
            }.toString() },
            { DiagnosticCodec.json.encodeToString(DiagnosticReceipt(it.reportId, 1000, 1000, listOf("phone"))) },
            { DiagnosticCodec.json.encodeToString(DiagnosticReceipt(it.reportId, 1000, 1001 + REPORT_TTL_MS, listOf("phone"))) },
        )
        for ((index, value) in (listOf(receipt) + cases).withIndex()) {
            receipt = value
            coordinator.send(retry = true)
            advanceUntilIdle()
            assertEquals("Receipt case $index", DiagnosticUploadFailure.MALFORMED_RECEIPT.savedCaptureMessage(), coordinator.state.value.message)
            assertFalse(coordinator.state.value.message!!.contains(privateReceipt))
            assertFalse(coordinator.state.value.receiptValidated)
            assertTrue(coordinator.state.value.pending)
            assertEquals(saved, store.read(account))
        }
        assertNull(store.read("b".repeat(64))) // The saved failure still cannot cross accounts.
    }

    @Test fun unknownExceptionsNeverExposePrivateTextAndExpiryStillUsesOriginalCollectionTime() = runTest {
        var now = 1000L
        var error: Exception = IllegalStateException("Bearer private-token private-account https://private-server/")
        val store = PendingDiagnosticStore(temp.newFolder()) { now }
        val coordinator = DiagnosticReportCoordinator(this, Any(), { selected }, { true }, store,
            watch = { _, _ -> WatchCollection("unavailable") }, snapshot = { phone },
            upload = { _, _ -> throw error }, now = { now })
        coordinator.send()
        advanceUntilIdle()
        assertEquals(DiagnosticUploadFailure.UNKNOWN.savedCaptureMessage(), coordinator.state.value.message)
        now += REPORT_TTL_MS - 1
        error = IOException("Certificate mismatch 401 private-token")
        coordinator.send(retry = true)
        advanceUntilIdle()
        assertEquals(DiagnosticUploadFailure.NETWORK.savedCaptureMessage(), coordinator.state.value.message)
        now++
        coordinator.restore()
        advanceUntilIdle()
        assertNull(store.read(account))
        assertFalse(coordinator.state.value.pending)
        assertNull(coordinator.state.value.reportId)
    }
}
