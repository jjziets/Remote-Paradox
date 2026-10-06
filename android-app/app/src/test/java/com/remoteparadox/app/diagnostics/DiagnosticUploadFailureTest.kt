package com.remoteparadox.app.diagnostics

import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class DiagnosticUploadFailureTest {
    private val privateText = "Bearer private-token https://private-host/ account private-report"

    @Test fun pinSetupRequiresNewReportWithoutManualRetryOrPreservationClaim() {
        for (failure in listOf(DiagnosticUploadFailure.MISSING_PIN, DiagnosticUploadFailure.MALFORMED_PIN)) {
            assertTrue(failure.message.endsWith("Use the trusted Pi setup QR code, then collect a new report."))
            assertEquals("${failure.message} Saved captures expire 24 hours after collection.", failure.savedCaptureMessage())
            assertFalse(failure.savedCaptureMessage().contains("Retry manually"))
            assertFalse(failure.savedCaptureMessage().contains("remains saved"))
        }
        for (failure in DiagnosticUploadFailure.entries - setOf(DiagnosticUploadFailure.MISSING_PIN, DiagnosticUploadFailure.MALFORMED_PIN)) {
            assertEquals("${failure.message} Saved captures expire 24 hours after collection. Retry manually.", failure.savedCaptureMessage())
        }
    }

    @Test fun classificationsUseTypesAndCausesNeverExceptionMessages() {
        val wrappedPin = SSLHandshakeException(privateText).apply { initCause(DiagnosticCertificateMismatchException()) }
        val cases = listOf(
            wrappedPin to DiagnosticUploadFailure.CERTIFICATE_MISMATCH,
            SSLPeerUnverifiedException(privateText) to DiagnosticUploadFailure.CERTIFICATE_MISMATCH,
            SSLHandshakeException(privateText) to DiagnosticUploadFailure.TLS_FAILURE,
            SocketTimeoutException(privateText) to DiagnosticUploadFailure.TIMEOUT,
            InterruptedIOException(privateText) to DiagnosticUploadFailure.TIMEOUT,
            IOException(privateText, SocketTimeoutException(privateText)) to DiagnosticUploadFailure.TIMEOUT,
            UnknownHostException(privateText) to DiagnosticUploadFailure.NETWORK,
            IOException(privateText) to DiagnosticUploadFailure.NETWORK,
            IllegalStateException(privateText) to DiagnosticUploadFailure.UNKNOWN,
            // Words in an exception cannot impersonate a certificate or authentication failure.
            IOException("Certificate mismatch 401 403 timeout $privateText") to DiagnosticUploadFailure.NETWORK,
        )
        for ((error, expected) in cases) {
            val failure = diagnosticUploadFailure(error)
            assertEquals(expected, failure)
            assertFalse(failure.savedCaptureMessage().contains(privateText))
        }
        for (failure in DiagnosticUploadFailure.entries) {
            val wrapped = IOException(privateText, DiagnosticUploadException(failure))
            assertEquals(failure, diagnosticUploadFailure(wrapped))
            assertEquals(failure.message, DiagnosticUploadException(failure).message)
        }
    }

    @Test fun malformedPinOrEndpointFailsClosedWithSafeClassification() {
        listOf("", " ", "\n").forEach { pin ->
            val error = assertThrows(DiagnosticUploadException::class.java) {
                diagnosticUploadClient("https://localhost/", pin)
            }
            assertEquals(DiagnosticUploadFailure.MISSING_PIN, error.failure)
        }
        listOf("a".repeat(63), "a".repeat(65), "g".repeat(64), "sha256/$privateText", " a".repeat(32)).forEach { pin ->
            val error = assertThrows(DiagnosticUploadException::class.java) {
                diagnosticUploadClient("https://localhost/", pin)
            }
            assertEquals(DiagnosticUploadFailure.MALFORMED_PIN, error.failure)
            assertFalse(error.message!!.contains(pin))
        }
        listOf("not a URL", "http://localhost/", "https://user:password@localhost/",
            "https://localhost/private", "https://localhost/?token=secret", "https://localhost/#secret").forEach { url ->
            val error = assertThrows(DiagnosticUploadException::class.java) { diagnosticUploadClient(url, "a".repeat(64)) }
            assertEquals(DiagnosticUploadFailure.INVALID_ENDPOINT, error.failure)
            assertFalse(error.message!!.contains(url))
        }
        val client = diagnosticUploadClient("https://localhost/", "A".repeat(64))
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(20_000, client.callTimeoutMillis)
    }

    @Test fun httpFailuresAndOversizedReceiptAreClassifiedWithoutReadingPrivateErrorBodiesOrRetrying() = runTest {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02x".format(it) }
        val directory = Files.createTempDirectory("diagnostic-failure-test").toFile()
        Diagnostics.initialize(directory, "phone", "1.2.33", 82)
        ClientDiagnostics.onReset = {}
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val base = server.url("/").toString()
            ClientDiagnostics.bind(base, "private-account", pin, "private-token")
            val target = DiagnosticTarget(ClientDiagnostics.capture()!!, base, pin, "Bearer private-token")
            try {
                val cases = listOf(
                    401 to DiagnosticUploadFailure.AUTHORIZATION_FAILED,
                    403 to DiagnosticUploadFailure.AUTHORIZATION_FAILED,
                    404 to DiagnosticUploadFailure.ENDPOINT_UNAVAILABLE,
                    429 to DiagnosticUploadFailure.RATE_LIMITED,
                    422 to DiagnosticUploadFailure.REPORT_REJECTED,
                    413 to DiagnosticUploadFailure.REPORT_REJECTED,
                    302 to DiagnosticUploadFailure.REDIRECT_BLOCKED,
                    503 to DiagnosticUploadFailure.SERVER_REJECTED,
                )
                for ((index, case) in cases.withIndex()) {
                    server.enqueue(MockResponse().setResponseCode(case.first).setBody(privateText)
                        .addHeader("Location", "https://example.invalid/private"))
                    val error = runCatching { uploadDiagnostic(target, "immutable-body") }.exceptionOrNull()
                    assertTrue(error is DiagnosticUploadException)
                    assertEquals(case.second, diagnosticUploadFailure(error!!))
                    assertEquals(case.second.message, error.message)
                    assertEquals(index + 1, server.requestCount)
                }
                server.enqueue(MockResponse().setBody("x".repeat(8193)))
                val error = runCatching { uploadDiagnostic(target, "immutable-body") }.exceptionOrNull()
                assertEquals(DiagnosticUploadFailure.MALFORMED_RECEIPT, diagnosticUploadFailure(error!!))
                assertEquals(cases.size + 1, server.requestCount)
                for (pinValue in listOf("", "invalid")) {
                    assertTrue(runCatching { uploadDiagnostic(target.copy(pin = pinValue), "immutable-body") }.isFailure)
                    assertEquals(cases.size + 1, server.requestCount)
                }
                val mismatch = runCatching { uploadDiagnostic(target.copy(pin = "0".repeat(64)), "immutable-body") }.exceptionOrNull()
                assertTrue(mismatch is DiagnosticUploadException)
                assertEquals(DiagnosticUploadFailure.CERTIFICATE_MISMATCH, diagnosticUploadFailure(mismatch!!))
                assertEquals(cases.size + 1, server.requestCount)
            } finally {
                ClientDiagnostics.bind(null, null, null, null)
                directory.deleteRecursively()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun diagnostic503RetryAfterZeroMakesOnePostAndRetainsCaptureUntilManualRetry() = runTest {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02x".format(it) }
        val directory = Files.createTempDirectory("diagnostic-replay-test").toFile()
        Diagnostics.initialize(directory, "phone", "1.2.33", 82)
        ClientDiagnostics.onReset = {}
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val base = server.url("/").toString()
            ClientDiagnostics.bind(base, "private-account", pin, "private-token")
            val target = DiagnosticTarget(ClientDiagnostics.capture()!!, base, pin, "Bearer private-token")
            val report = DiagnosticReport("11111111-1111-4111-8111-111111111111", "unavailable", DeviceLog("phone", "1.2.33", 82, 1000))
            val saved = PendingDiagnostic(target.session.scope, 1000, DiagnosticCodec.json.encodeToString(report))
            val store = PendingDiagnosticStore(directory) { 1000L }
            store.save(saved)
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody(DiagnosticCodec.json.encodeToString(
                DiagnosticReceipt(report.reportId, 1000, 2000, listOf("phone")))))
            val coordinator = DiagnosticReportCoordinator(this, Any(), { target }, { ClientDiagnostics.matches(it.session) }, store,
                watch = { _, _ -> error("Saved capture must not be recollected") },
                snapshot = { error("Saved capture must not be replaced") }, upload = ::uploadDiagnostic, now = { 1000L })
            try {
                coordinator.restore()
                advanceUntilIdle()
                coordinator.send(retry = true)
                coordinator.state.first { !it.busy }
                assertEquals(1, server.requestCount)
                assertEquals(saved, store.read(target.session.scope))
                assertTrue(coordinator.state.value.pending)
                assertFalse(coordinator.state.value.receiptValidated)
                assertEquals(DiagnosticUploadFailure.SERVER_REJECTED.savedCaptureMessage(), coordinator.state.value.message)
                val failed = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/system/diagnostics", failed.path)
                assertEquals("Bearer private-token", failed.getHeader("Authorization"))
                assertEquals(saved.body, failed.body.readUtf8())
                coordinator.send(retry = true)
                coordinator.state.first { !it.busy }
                assertEquals(2, server.requestCount)
                assertEquals(saved.body, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
                assertNull(store.read(target.session.scope))
                assertTrue(coordinator.state.value.receiptValidated)
            } finally {
                coordinator.reset()
                ClientDiagnostics.bind(null, null, null, null)
                directory.deleteRecursively()
            }
        }
    }
}
