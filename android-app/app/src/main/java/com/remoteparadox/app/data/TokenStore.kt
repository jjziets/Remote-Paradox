package com.remoteparadox.app.data

import android.content.Context
import android.content.SharedPreferences
import com.remoteparadox.app.diagnostics.ClientDiagnostics
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
        set(value) {
            synchronized(ClientDiagnostics.lock) {
                prefs.edit()
                    .putString(KEY_TOKEN, value)
                    .putLong(KEY_TOKEN_SAVED_AT, System.currentTimeMillis())
                    .apply()
                syncDiagnostics()
            }
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    val tokenAgeMs: Long
        get() {
            val savedAt = prefs.getLong(KEY_TOKEN_SAVED_AT, 0L)
            if (savedAt == 0L) return Long.MAX_VALUE
            return System.currentTimeMillis() - savedAt
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = editCredentials { putString(KEY_USERNAME, value) }

    var role: String?
        get() = prefs.getString(KEY_ROLE, null)
        set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    var serverHost: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = editCredentials { putString(KEY_HOST, value) }

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, 9433)
        set(value) = editCredentials { putInt(KEY_PORT, value) }

    var certFingerprint: String?
        get() = prefs.getString(KEY_FINGERPRINT, null)
        set(value) = editCredentials { putString(KEY_FINGERPRINT, value) }

    val isLoggedIn: Boolean get() = token != null && serverHost != null

    val baseUrl: String? get() {
        val h = serverHost ?: return null
        return "https://$h:$serverPort/"
    }

    var alarmCode: String?
        get() = prefs.getString(KEY_ALARM_CODE, null)
        set(value) = prefs.edit().putString(KEY_ALARM_CODE, value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    val bearerHeader: String get() = "Bearer ${token.orEmpty()}"

    fun saveLogin(host: String, port: Int, fingerprint: String, loginResp: LoginResponse) {
        serverHost = host
        serverPort = port
        certFingerprint = fingerprint
        token = loginResp.token
        refreshToken = loginResp.refreshToken.ifBlank { refreshToken }
        username = loginResp.username
        role = loginResp.role
    }

    fun saveRegister(host: String, port: Int, fingerprint: String, resp: RegisterResponse) {
        serverHost = host
        serverPort = port
        certFingerprint = fingerprint
        token = resp.token
        refreshToken = resp.refreshToken.ifBlank { refreshToken }
        username = resp.username
        role = "user"
    }

    val hasServerConfig: Boolean get() = serverHost != null

    fun clearAuth() {
        editCredentials {
            remove(KEY_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_USERNAME)
            remove(KEY_ROLE)
            remove(KEY_ALARM_CODE)
        }
    }

    fun clear() {
        editCredentials { clear() }
    }

    init { syncDiagnostics() }

    private fun editCredentials(edit: SharedPreferences.Editor.() -> Unit) {
        synchronized(ClientDiagnostics.lock) {
            prefs.edit().apply(edit).apply()
            syncDiagnostics()
        }
    }

    private fun syncDiagnostics() {
        ClientDiagnostics.bind(baseUrl, username, certFingerprint, token)
    }

    companion object {
        private const val KEY_TOKEN_SAVED_AT = "token_saved_at"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_FINGERPRINT = "cert_fingerprint"
        private const val KEY_ALARM_CODE = "alarm_code"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
    }
}
