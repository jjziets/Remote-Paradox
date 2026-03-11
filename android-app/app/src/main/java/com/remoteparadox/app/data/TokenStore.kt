package com.remoteparadox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "paradox_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var role: String?
        get() = prefs.getString(KEY_ROLE, null)
        set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    var serverHost: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var certFingerprint: String?
        get() = prefs.getString(KEY_FINGERPRINT, null)
        set(value) = prefs.edit().putString(KEY_FINGERPRINT, value).apply()

    val isLoggedIn: Boolean get() = token != null && serverHost != null

    val baseUrl: String? get() {
        val h = serverHost ?: return null
        return "https://$h:$serverPort/"
    }

    var alarmCode: String?
        get() = prefs.getString(KEY_ALARM_CODE, null)
        set(value) = prefs.edit().putString(KEY_ALARM_CODE, value).apply()

    val bearerHeader: String get() = "Bearer ${token.orEmpty()}"

    fun saveLogin(host: String, port: Int, fingerprint: String, loginResp: LoginResponse) {
        serverHost = host
        serverPort = port
        certFingerprint = fingerprint
        token = loginResp.token
        username = loginResp.username
        role = loginResp.role
    }

    fun saveRegister(host: String, port: Int, fingerprint: String, resp: RegisterResponse) {
        serverHost = host
        serverPort = port
        certFingerprint = fingerprint
        token = resp.token
        username = resp.username
        role = "user"
    }

    val hasServerConfig: Boolean get() = serverHost != null

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_ROLE)
            .remove(KEY_ALARM_CODE)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_FINGERPRINT = "cert_fingerprint"
        private const val KEY_ALARM_CODE = "alarm_code"
    }
}
