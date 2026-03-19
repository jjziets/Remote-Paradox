package com.remoteparadox.app.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "WatchSync"
const val WATCH_SYNC_PATH = "/paradox/sync-credentials"

@Serializable
data class WatchSyncPayload(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val username: String,
    val alarmCode: String,
)

class WatchSync(private val context: Context) {
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun sendCredentialsToWatch(tokenStore: TokenStore): WatchSyncResult {
        Log.d(TAG, "=== sendCredentialsToWatch START ===")

        val host = tokenStore.serverHost
        if (host == null) {
            Log.e(TAG, "ABORT: serverHost is null")
            return WatchSyncResult.Error("No server configured")
        }
        val token = tokenStore.token
        if (token == null) {
            Log.e(TAG, "ABORT: token is null")
            return WatchSyncResult.Error("Not logged in")
        }

        Log.d(TAG, "Building payload: host=$host, port=${tokenStore.serverPort}, user=${tokenStore.username}, alarmCode=${if (tokenStore.alarmCode.isNullOrEmpty()) "EMPTY" else "SET"}")

        val payload = WatchSyncPayload(
            host = host,
            port = tokenStore.serverPort,
            fingerprint = tokenStore.certFingerprint.orEmpty(),
            token = token,
            username = tokenStore.username.orEmpty(),
            alarmCode = tokenStore.alarmCode.orEmpty(),
        )
        val json = Json.encodeToString(payload)
        val bytes = json.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Payload JSON size: ${bytes.size} bytes")

        return try {
            Log.d(TAG, "Querying connected nodes...")
            val nodes = nodeClient.connectedNodes.await()
            Log.d(TAG, "Found ${nodes.size} connected node(s)")

            if (nodes.isEmpty()) {
                Log.w(TAG, "No nodes found! Watch may not be paired or nearby.")
                return WatchSyncResult.Error("No watch connected. Make sure your watch is paired and nearby.")
            }

            var sent = 0
            for (node in nodes) {
                Log.i(TAG, "Sending to node: name='${node.displayName}', id='${node.id}', isNearby=${node.isNearby}")
                try {
                    messageClient.sendMessage(node.id, WATCH_SYNC_PATH, bytes).await()
                    Log.i(TAG, "SUCCESS: Message sent to ${node.displayName}")
                    sent++
                } catch (e: Exception) {
                    Log.e(TAG, "FAILED to send to node ${node.displayName}: ${e.message}", e)
                }
            }
            Log.d(TAG, "=== sendCredentialsToWatch DONE: sent to $sent node(s) ===")
            WatchSyncResult.Success(sent)
        } catch (e: Exception) {
            Log.e(TAG, "=== sendCredentialsToWatch EXCEPTION ===", e)
            WatchSyncResult.Error("Failed to sync: ${e.message}")
        }
    }
}

sealed class WatchSyncResult {
    data class Success(val nodeCount: Int) : WatchSyncResult()
    data class Error(val message: String) : WatchSyncResult()
}
