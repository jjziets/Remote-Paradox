package com.remoteparadox.watch.data

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.remoteparadox.watch.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "WatchSyncRx"
const val WATCH_SYNC_PATH = "/paradox/sync-credentials"
private const val WATCH_VERSION_QUERY_PATH = "/paradox/watch-version-query"
private const val WATCH_VERSION_REPLY_PATH = "/paradox/watch-version-reply"
const val ACTION_CREDENTIALS_RECEIVED = "com.remoteparadox.watch.CREDENTIALS_RECEIVED"

@Serializable
data class WatchSyncPayload(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val username: String,
    val alarmCode: String,
)

class WatchSyncReceiver : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== WatchSyncReceiver SERVICE CREATED ===")
    }

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "=== onMessageReceived ===")
        Log.d(TAG, "  path: ${event.path}")
        Log.d(TAG, "  sourceNodeId: ${event.sourceNodeId}")
        Log.d(TAG, "  data size: ${event.data?.size ?: 0} bytes")

        if (event.path == WATCH_VERSION_QUERY_PATH) {
            val version = BuildConfig.VERSION_NAME
            Log.i(TAG, "Version query from ${event.sourceNodeId}, replying with $version")
            Wearable.getMessageClient(this).sendMessage(
                event.sourceNodeId,
                WATCH_VERSION_REPLY_PATH,
                version.toByteArray(Charsets.UTF_8),
            )
            return
        }

        if (event.path != WATCH_SYNC_PATH) {
            Log.d(TAG, "  IGNORING: path doesn't match $WATCH_SYNC_PATH")
            return
        }

        try {
            val payloadStr = String(event.data, Charsets.UTF_8)
            Log.d(TAG, "  Raw payload: ${payloadStr.take(200)}...")

            val payload = json.decodeFromString<WatchSyncPayload>(payloadStr)
            Log.d(TAG, "  Parsed: host=${payload.host}, port=${payload.port}, user=${payload.username}")
            Log.d(TAG, "  Token length: ${payload.token.length}")
            Log.d(TAG, "  Alarm code: ${if (payload.alarmCode.isEmpty()) "EMPTY" else "SET (${payload.alarmCode.length} chars)"}")
            Log.d(TAG, "  Fingerprint: ${if (payload.fingerprint.isEmpty()) "EMPTY" else "SET"}")

            val store = WatchTokenStore(this)
            store.serverHost = payload.host
            store.serverPort = payload.port
            store.certFingerprint = payload.fingerprint
            store.token = payload.token
            store.username = payload.username
            store.alarmCode = payload.alarmCode

            Log.i(TAG, "Credentials STORED for ${payload.username}@${payload.host}:${payload.port}")
            Log.d(TAG, "  Verify isLoggedIn: ${store.isLoggedIn}")
            Log.d(TAG, "  Verify baseUrl: ${store.baseUrl}")

            Log.d(TAG, "Broadcasting ACTION_CREDENTIALS_RECEIVED...")
            val intent = Intent(ACTION_CREDENTIALS_RECEIVED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Broadcast sent!")

        } catch (e: Exception) {
            Log.e(TAG, "=== FAILED to process sync message ===", e)
        }
    }
}
