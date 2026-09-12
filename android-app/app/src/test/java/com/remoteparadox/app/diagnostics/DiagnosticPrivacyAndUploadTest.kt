package com.remoteparadox.app.diagnostics

import com.remoteparadox.app.data.*
import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class DiagnosticPrivacyAndUploadTest {
    private fun initialize() {
        Diagnostics.initialize(Files.createTempDirectory("phone-diagnostics-test").toFile(), "phone", "1.2.31", 81)
        ClientDiagnostics.onReset = {}
        ClientDiagnostics.bind(null, null, null, null)
    }

    @Test fun rotationPreservesPendingAndExistingApiInterceptorStillRecords() = runTest {
        initialize()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"success\":true}"))
            val base = server.url("/").toString()
            ClientDiagnostics.bind(base, "test-account", "a".repeat(64), "old-token")
            val session = ClientDiagnostics.capture()!!
            val directory = Files.createTempDirectory("pending-rotation-test").toFile()
            val pending = PendingDiagnosticStore(directory)
            pending.save(PendingDiagnostic(session.scope, System.currentTimeMillis(), "unchanged capture"))
            var resets = 0
            ClientDiagnostics.onReset = { resets++; pending.clear() }
            try {
                ClientDiagnostics.record(DiagnosticEvent("foreground", "phone_app"), session)
                val api = ApiClient.create(base)
                ClientDiagnostics.bind(base, "test-account", "a".repeat(64), "rotated-token")
                assertTrue(ClientDiagnostics.matches(session))
                assertEquals(0, resets)
                assertEquals("unchanged capture", pending.read(session.scope)?.body)
                assertTrue(api.disarm("Bearer rotated-token", ArmRequest("private-code", 1)).isSuccessful)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                val requestId = request.getHeader("X-Diagnostic-Request-Id")!!
                assertEquals(requestId, UUID.fromString(requestId).toString())
                val log = Diagnostics.snapshot()
                assertTrue(log.events.any { it.kind == "foreground" })
                assertTrue(log.events.any { it.kind == "http_finished" && it.requestId == requestId })
                val wire = DiagnosticCodec.json.encodeToString(log)
                listOf("rotated-token", "private-code", "test-account", base).forEach { assertFalse(wire.contains(it)) }
                ClientDiagnostics.bind(base, "second-account", "a".repeat(64), "different-token")
                assertEquals(1, resets)
                assertNull(pending.read(session.scope))
                assertFalse(ClientDiagnostics.matches(session))
                ClientDiagnostics.record(DiagnosticEvent("ws_open", "ws"), session)
                assertTrue(Diagnostics.snapshot().events.isEmpty())
            } finally { ClientDiagnostics.onReset = {}; directory.deleteRecursively() }
        }
    }

    @Test fun summariesDropLabelsUnknownModesAndExceptionText() {
        val event = DiagnosticEvents.summaries(AlarmStatus(listOf(PartitionInfo(1, "private partition", false,
            "private mode", zones = listOf(ZoneInfo(1, "private zone", true, true)))), true), "phone_app").single()
        assertEquals("unknown", event.mode)
        assertEquals(1, event.openZones)
        assertEquals(1, event.bypassedZones)
        val wire = DiagnosticCodec.json.encodeToString(event.copy(processId = UUID.randomUUID().toString(), sequence = 1))
        assertFalse(wire.contains("private"))
        assertEquals("connection", DiagnosticEvents.error(java.io.IOException("Bearer secret / account")))
        assertNull(DiagnosticEvents.route("Bearer secret"))
    }

    @Test fun uploadRequiresHttpsAndFullPinAndDisablesRedirectsAndAutomaticRetry() {
        assertThrows(IllegalArgumentException::class.java) { diagnosticUploadClient("http://localhost/", "a".repeat(64)) }
        listOf("", " ", "a".repeat(63), "g".repeat(64)).forEach { pin ->
            assertThrows(IllegalArgumentException::class.java) { diagnosticUploadClient("https://localhost/", pin) }
        }
        val client = diagnosticUploadClient("https://localhost/", "A".repeat(64))
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(20_000, client.callTimeoutMillis)
    }

    @Test fun pinnedSelfSignedUploadSendsBearerButCannotFollowRedirectOrTrustDifferentCertificate() = runTest {
        initialize()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02x".format(it) }
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val base = server.url("/").toString()
            ClientDiagnostics.bind(base, "test-account", pin, "test-token")
            val target = DiagnosticTarget(ClientDiagnostics.capture()!!, base, pin, "Bearer test-token")
            server.enqueue(MockResponse().setBody("receipt"))
            assertEquals("receipt", uploadDiagnostic(target, "immutable-body"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/system/diagnostics", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("immutable-body", request.body.readUtf8())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.invalid/"))
            assertTrue(runCatching { uploadDiagnostic(target, "immutable-body") }.isFailure)
            assertEquals(2, server.requestCount)
            assertTrue(runCatching {
                diagnosticUploadClient(base, "0".repeat(64)).newCall(Request.Builder().url(base).build()).execute().use { }
            }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
}
