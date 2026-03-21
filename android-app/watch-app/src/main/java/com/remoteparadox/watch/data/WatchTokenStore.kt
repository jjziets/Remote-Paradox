package com.remoteparadox.watch.data

import android.content.Context
import android.content.SharedPreferences

class WatchTokenStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "paradox_watch_prefs", Context.MODE_PRIVATE,
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var serverHost: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, 9433)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var certFingerprint: String?
        get() = prefs.getString(KEY_FINGERPRINT, null)
        set(value) = prefs.edit().putString(KEY_FINGERPRINT, value).apply()

    var alarmCode: String?
        get() = prefs.getString(KEY_ALARM_CODE, null)
        set(value) = prefs.edit().putString(KEY_ALARM_CODE, value).apply()

    var armAwayEnabled: Boolean
        get() = prefs.getBoolean(KEY_ARM_AWAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ARM_AWAY_ENABLED, value).apply()

    var armStayEnabled: Boolean
        get() = prefs.getBoolean(KEY_ARM_STAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ARM_STAY_ENABLED, value).apply()

    var pendingActionPartition: Int
        get() = prefs.getInt(KEY_PENDING_PARTITION, -1)
        set(value) = prefs.edit().putInt(KEY_PENDING_PARTITION, value).apply()

    var pendingActionTime: Long
        get() = prefs.getLong(KEY_PENDING_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_TIME, value).apply()

    fun setPendingAction(partitionId: Int) {
        pendingActionPartition = partitionId
        pendingActionTime = System.currentTimeMillis()
    }

    fun clearPendingAction() {
        pendingActionPartition = -1
        pendingActionTime = 0L
    }

    fun isPendingAction(partitionId: Int): Boolean {
        if (pendingActionPartition != partitionId) return false
        return System.currentTimeMillis() - pendingActionTime < 5000
    }

    val isLoggedIn: Boolean get() = token != null && serverHost != null

    val baseUrl: String?
        get() {
            val h = serverHost ?: return null
            return "https://$h:$serverPort/"
        }

    val bearerHeader: String get() = "Bearer ${token.orEmpty()}"

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_FINGERPRINT = "cert_fingerprint"
        private const val KEY_ALARM_CODE = "alarm_code"
        private const val KEY_ARM_AWAY_ENABLED = "arm_away_enabled"
        private const val KEY_ARM_STAY_ENABLED = "arm_stay_enabled"
        private const val KEY_PENDING_PARTITION = "pending_action_partition"
        private const val KEY_PENDING_TIME = "pending_action_time"
    }
}
