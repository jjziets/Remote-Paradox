package com.remoteparadox.app.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.remoteparadox.app.data.ApiClient
import com.remoteparadox.app.data.PushTokenRequest
import com.remoteparadox.app.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PushManager {
    private const val TAG = "PushManager"
    const val CHANNEL_CRITICAL = "alarm_critical"
    const val CHANNEL_ALERTS = "alarm_alerts"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val alarmVibration = longArrayOf(0, 500, 250, 500, 250, 500)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Alarm triggered", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "House alarm went off"
                enableVibration(true)
                vibrationPattern = alarmVibration
                enableLights(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "System alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Panel offline, disk full, and other bridge alerts"
                enableVibration(true)
            }
        )
    }

    val criticalVibration: LongArray get() = alarmVibration

    /** Fetch the current FCM token and register it with the bridge. Call after login. */
    fun registerCurrentToken(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(context, token) }
            .addOnFailureListener { e -> Log.w(TAG, "FCM token fetch failed", e) }
    }

    /** Register a specific FCM token with the bridge (only if logged in). */
    fun register(context: Context, fcmToken: String) {
        val store = TokenStore(context)
        val base = store.baseUrl ?: return
        if (store.token == null) return
        val fingerprint = store.certFingerprint.orEmpty()
        val auth = store.bearerHeader
        scope.launch {
            try {
                ApiClient.create(base, fingerprint).registerPushToken(auth, PushTokenRequest(fcmToken, "phone"))
                Log.i(TAG, "Registered push token with bridge")
            } catch (e: Exception) {
                Log.w(TAG, "Push token registration failed: ${e.message}")
            }
        }
    }
}
