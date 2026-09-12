package com.remoteparadox.diagnostics

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

internal class DiagnosticHttpInterceptor(private val recorder: DiagnosticRecorder?) : Interceptor {
    private val scope = recorder?.captureScope()

    override fun intercept(chain: Interceptor.Chain): Response {
        val requestId = UUID.randomUUID().toString()
        val original = chain.request()
        val request = original.newBuilder().header("X-Diagnostic-Request-Id", requestId).build()
        val route = original.url.encodedPath.takeIf { it in Fields.routes }
        val repetitivePoll = original.method == "GET" && route == "/alarm/status"
        val started = System.nanoTime()
        fun record(kind: String, status: Int? = null, success: Boolean? = null, error: String? = null) {
            if (scope == null || route == null) return
            try {
                recorder?.recordCaptured(DiagnosticEvent(kind, "http", requestId = requestId, route = route,
                    httpStatus = status, success = success, error = error,
                    elapsedMs = if (kind == "http_started") null else
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)), scope)
            } catch (_: Exception) { }
        }
        if (!repetitivePoll) record("http_started")
        try {
            val response = chain.proceed(request)
            if (!repetitivePoll || !response.isSuccessful) {
                record("http_finished", response.code, response.isSuccessful,
                    if (response.isSuccessful) null else "http")
            }
            return response
        } catch (failure: Exception) {
            val error = when {
                chain.call().isCanceled() -> "cancelled"
                failure is SocketTimeoutException -> "timeout"
                failure is InterruptedIOException -> "cancelled"
                failure is IOException -> "connection"
                else -> "unknown"
            }
            record("http_failed", success = false, error = error)
            throw failure
        }
    }
}
