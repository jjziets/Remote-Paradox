package com.remoteparadox.diagnostics

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticHttpInterceptorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun recorder() = DiagnosticRecorder(temporary.newFolder(), "phone", "1.2.3", 1).apply { bindScope(SCOPE_A) }
    private fun request(path: String = "/alarm/arm-away") = Request.Builder()
        .url("https://private.example$path?token=do-not-log")
        .header("Authorization", "Bearer do-not-log")
        .header("X-Diagnostic-Request-Id", "attacker-controlled")
        .build()

    @Test fun `correlation header is random and matches both bounded events`() {
        recorder().use { recorder ->
            val chain = FakeChain(request())
            val interceptor = DiagnosticHttpInterceptor(recorder)
            val response = interceptor.intercept(chain)
            assertSame(chain.response, response)
            val id = chain.received!!.header("X-Diagnostic-Request-Id")!!
            assertTrue(Fields.uuid.matches(id))
            assertEquals("Bearer do-not-log", chain.received!!.header("Authorization"))
            val events = recorder.snapshot().events
            assertEquals(listOf("http_started", "http_finished"), events.map { it.kind })
            assertTrue(events.all { it.requestId == id && it.route == "/alarm/arm-away" })
            val serialized = DiagnosticCodec.json.encodeToString(recorder.snapshot())
            assertFalse(serialized.contains("private.example"))
            assertFalse(serialized.contains("do-not-log"))
            assertFalse(serialized.contains("response-secret"))
            val other = FakeChain(request())
            interceptor.intercept(other)
            assertNotEquals(id, other.received!!.header("X-Diagnostic-Request-Id"))
        }
    }

    @Test fun `successful status polls emit no repetitive event pairs`() {
        recorder().use { recorder ->
            val interceptor = DiagnosticHttpInterceptor(recorder)
            repeat(5) { interceptor.intercept(FakeChain(request("/alarm/status"))) }
            assertTrue(recorder.snapshot().events.isEmpty())
            interceptor.intercept(FakeChain(request("/alarm/status"), 503))
            val event = recorder.snapshot().events.single()
            assertEquals("http_finished", event.kind)
            assertEquals("http", event.error)
            assertEquals(false, event.success)
        }
    }

    @Test fun `failure classification preserves original network failure without its message`() {
        recorder().use { recorder ->
            val failure = SocketTimeoutException("password=do-not-log")
            val interceptor = DiagnosticHttpInterceptor(recorder)
            val thrown = assertThrows(SocketTimeoutException::class.java) {
                interceptor.intercept(FakeChain(request("/alarm/status"), action = { throw failure }))
            }
            assertSame(failure, thrown)
            val report = recorder.snapshot()
            assertEquals("timeout", report.events.single().error)
            assertFalse(DiagnosticCodec.json.encodeToString(report).contains("do-not-log"))
        }
    }

    @Test fun `old in flight requests cannot write into new scope`() {
        recorder().use { recorder ->
            val interceptor = DiagnosticHttpInterceptor(recorder)
            interceptor.intercept(FakeChain(request(), action = { recorder.bindScope(SCOPE_B) }))
            assertTrue(recorder.snapshot().events.isEmpty())
            recorder.bindScope(SCOPE_A)
            interceptor.intercept(FakeChain(request()))
            assertTrue(recorder.snapshot().events.isEmpty())
        }
    }

    @Test fun `unknown routes are correlated without being recorded`() {
        recorder().use { recorder ->
            val chain = FakeChain(request("/users/private-label"))
            DiagnosticHttpInterceptor(recorder).intercept(chain)
            assertTrue(Fields.uuid.matches(chain.received!!.header("X-Diagnostic-Request-Id")!!))
            assertTrue(recorder.snapshot().events.isEmpty())
        }
    }

    @Test fun `diagnostics unavailable does not block network`() {
        val chain = FakeChain(request())
        assertSame(chain.response, DiagnosticHttpInterceptor(null).intercept(chain))
    }

    @Test fun `alarm changing POSTs cannot replay on 503 retry after zero even without a recorder`() {
        val payload = "{\"code\":\"private-code\",\"partition_id\":1}"
        for (path in listOf("/alarm/arm-away", "/alarm/arm-stay", "/alarm/disarm", "/alarm/bypass", "/alarm/panic")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setBody("would hide a replay"))
                val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                    .addInterceptor(DiagnosticHttpInterceptor(null)).build()
                val request = Request.Builder().url(server.url(path)).header("Authorization", "Bearer private-token")
                    .post(payload.toRequestBody()).build()
                val status = client.newCall(request).execute().use { it.code }
                assertEquals("Request count for $path", 1, server.requestCount)
                assertEquals(503, status)
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(path, sent.path)
                assertEquals(payload, sent.body.readUtf8())
                assertEquals("Bearer private-token", sent.getHeader("Authorization"))
                assertTrue(Fields.uuid.matches(sent.getHeader("X-Diagnostic-Request-Id")!!))
            }
        }
    }

    @Test fun `status GET retains ordinary 503 follow up behavior`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("status result"))
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .addInterceptor(DiagnosticHttpInterceptor(null)).build()
            val request = Request.Builder().url(server.url("/alarm/status")).get().build()
            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                assertEquals("status result", it.body!!.string())
            }
            assertEquals(2, server.requestCount)
            repeat(2) {
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("GET", sent.method)
                assertEquals("/alarm/status", sent.path)
                assertEquals(0L, sent.body.size)
            }
        }
    }

    @Test fun `one shot policy is limited to recognized alarm POST bodies`() {
        val body = "private-payload".toRequestBody()
        for ((method, path) in listOf("POST" to "/alarm/status", "POST" to "/auth/login",
            "POST" to "/alarm/unknown", "POST" to "/alarm/arm-away/", "POST" to "/prefix/alarm/arm-away",
            "PUT" to "/alarm/arm-away")) {
            val original = request(path).newBuilder().method(method, body).build()
            val chain = FakeChain(original)
            DiagnosticHttpInterceptor(null).intercept(chain)
            assertSame(body, chain.received!!.body)
            assertFalse(chain.received!!.body!!.isOneShot())
        }
        val chain = FakeChain(request("/alarm/status"))
        DiagnosticHttpInterceptor(null).intercept(chain)
        assertNull(chain.received!!.body)
        val oneShot = OneShotRequestBody(body)
        val alreadyMarked = FakeChain(request().newBuilder().post(oneShot).build())
        DiagnosticHttpInterceptor(null).intercept(alreadyMarked)
        assertSame(oneShot, alreadyMarked.received!!.body)
    }

    @Test fun `cancellation is a bounded category`() {
        recorder().use { recorder ->
            val chain = FakeChain(request(), action = { throw IOException("private failure") })
            chain.call().cancel()
            assertThrows(IOException::class.java) { DiagnosticHttpInterceptor(recorder).intercept(chain) }
            assertEquals("cancelled", recorder.snapshot().events.last().error)
        }
    }

    private class FakeChain(
        private val original: Request,
        code: Int = 200,
        private val action: () -> Unit = {},
    ) : Interceptor.Chain {
        var received: Request? = null
        val response = Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(code)
            .message("response-secret").body("response-secret".toResponseBody()).build()
        private val call = OkHttpClient().newCall(original)
        override fun request() = original
        override fun proceed(request: Request): Response { received = request; action(); return response }
        override fun call(): Call = call
        override fun connection(): Connection? = null
        override fun connectTimeoutMillis() = 1000
        override fun readTimeoutMillis() = 1000
        override fun writeTimeoutMillis() = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
