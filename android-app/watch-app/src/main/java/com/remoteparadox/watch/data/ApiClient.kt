package com.remoteparadox.watch.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var _httpClient: OkHttpClient? = null
    val httpClient: OkHttpClient get() = _httpClient ?: OkHttpClient()

    fun create(baseUrl: String, fingerprint: String = ""): ParadoxApi {
        val client = buildOkHttp(fingerprint)
        _httpClient = client
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ParadoxApi::class.java)
    }

    private fun buildOkHttp(fingerprint: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)

        val trustManager: X509TrustManager = if (fingerprint.isNotBlank()) {
            FingerprintTrustManager(fingerprint)
        } else {
            TrustAllManager()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier { _, _ -> true }

        return builder.build()
    }
}

private class TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

private class FingerprintTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw IllegalStateException("No server certificate")
        val serverCert = chain[0]
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(serverCert.encoded).joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedFingerprint, ignoreCase = true)) {
            throw javax.net.ssl.SSLPeerUnverifiedException(
                "Certificate fingerprint mismatch: expected=$expectedFingerprint actual=$actual"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
