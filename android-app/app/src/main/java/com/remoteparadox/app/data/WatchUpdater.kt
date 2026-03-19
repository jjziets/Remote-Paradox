package com.remoteparadox.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "WatchUpdater"
private const val WATCH_VERSION_QUERY_PATH = "/paradox/watch-version-query"
private const val WATCH_VERSION_REPLY_PATH = "/paradox/watch-version-reply"
private const val WATCH_UPDATE_CHANNEL_PATH = "/paradox/watch-update-apk"
private const val VERSION_QUERY_TIMEOUT_MS = 8_000L

class WatchUpdater(private val context: Context) {

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val channelClient: ChannelClient = Wearable.getChannelClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun queryWatchVersion(): String? {
        Log.d(TAG, "Querying watch version...")
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch nodes")
            return null
        }

        val deferred = CompletableDeferred<String>()
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == WATCH_VERSION_REPLY_PATH) {
                val version = String(event.data, Charsets.UTF_8)
                Log.i(TAG, "Watch version reply: $version")
                deferred.complete(version)
            }
        }

        messageClient.addListener(listener)
        try {
            for (node in nodes) {
                Log.d(TAG, "Sending version query to ${node.displayName}")
                messageClient.sendMessage(node.id, WATCH_VERSION_QUERY_PATH, byteArrayOf()).await()
            }

            return withTimeoutOrNull(VERSION_QUERY_TIMEOUT_MS) {
                deferred.await()
            }.also {
                if (it == null) Log.w(TAG, "Watch version query timed out")
            }
        } finally {
            messageClient.removeListener(listener)
        }
    }

    suspend fun sendApkToWatch(apkFile: File): Boolean {
        Log.d(TAG, "Sending APK to watch: ${apkFile.name} (${apkFile.length()} bytes)")
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch nodes")
            return false
        }

        val targetNode = nodes.first()
        Log.d(TAG, "Opening channel to ${targetNode.displayName}")

        return try {
            val channel = channelClient.openChannel(
                targetNode.id,
                WATCH_UPDATE_CHANNEL_PATH,
            ).await()

            Log.d(TAG, "Channel opened, sending file...")
            channelClient.sendFile(channel, Uri.fromFile(apkFile)).await()
            Log.i(TAG, "APK sent successfully")

            channelClient.close(channel).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send APK to watch", e)
            false
        }
    }
}
