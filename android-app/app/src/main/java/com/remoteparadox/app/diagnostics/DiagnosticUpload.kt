package com.remoteparadox.app.diagnostics

import com.remoteparadox.diagnostics.OneShotRequestBody
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class DiagnosticTarget(
    val session: DiagnosticSession,
    val baseUrl: String,
    val pin: String,
    val bearer: String,
)

internal fun diagnosticUploadClient(baseUrl: String, pin: String): OkHttpClient {
    if (pin.isBlank()) throw DiagnosticUploadException(DiagnosticUploadFailure.MISSING_PIN)
    if (!pin.matches(Regex("[0-9a-fA-F]{64}"))) {
        throw DiagnosticUploadException(DiagnosticUploadFailure.MALFORMED_PIN)
    }
    val url = try { baseUrl.toHttpUrl() } catch (_: IllegalArgumentException) {
        throw DiagnosticUploadException(DiagnosticUploadFailure.INVALID_ENDPOINT)
    }
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.encodedPath != "/" || url.query != null || url.fragment != null) {
        throw DiagnosticUploadException(DiagnosticUploadFailure.INVALID_ENDPOINT)
    }
    val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw SSLPeerUnverifiedException("Client certificates unsupported")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw SSLPeerUnverifiedException("Missing certificate")
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            if (!actual.equals(pin, ignoreCase = true)) throw DiagnosticCertificateMismatchException()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trust)
        .hostnameVerifier { host, _ -> host == url.host }
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .build()
}

internal suspend fun uploadDiagnostic(target: DiagnosticTarget, body: String): String {
    val client = diagnosticUploadClient(target.baseUrl, target.pin)
    val request = Request.Builder()
        .url(target.baseUrl.toHttpUrl().newBuilder().encodedPath("/system/diagnostics").build())
        .header("Authorization", target.bearer)
        .post(OneShotRequestBody(body.toRequestBody("application/json".toMediaType())))
        .build()
    return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        synchronized(ClientDiagnostics.lock) {
            if (!ClientDiagnostics.matches(target.session)) {
                continuation.cancel()
                return@synchronized
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(DiagnosticUploadException(diagnosticUploadFailure(e)))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw DiagnosticUploadException(diagnosticHttpFailure(it.code))
                            val source = it.body?.source() ?: throw DiagnosticUploadException(DiagnosticUploadFailure.MALFORMED_RECEIPT)
                            source.request(8193)
                            if (source.buffer.size > 8192) throw DiagnosticUploadException(DiagnosticUploadFailure.MALFORMED_RECEIPT)
                            source.buffer.readUtf8()
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(DiagnosticUploadException(diagnosticUploadFailure(e)))
                    }
                }
            })
        }
    }
}
