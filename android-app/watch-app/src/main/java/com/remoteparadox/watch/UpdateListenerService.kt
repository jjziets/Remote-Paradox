package com.remoteparadox.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

private const val TAG = "UpdateListenerSvc"

class UpdateListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "Message received: path=${event.path}")
        when (event.path) {
            WATCH_VERSION_QUERY_PATH -> replyWithVersion(event.sourceNodeId)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path == WATCH_UPDATE_CHANNEL_PATH) {
            Log.i(TAG, "Update channel opened, receiving APK")
            receiveAndInstallApk(channel)
        }
    }

    private fun replyWithVersion(sourceNodeId: String) {
        val version = BuildConfig.VERSION_NAME
        Log.i(TAG, "Version query from $sourceNodeId, replying: $version")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(
                        sourceNodeId,
                        WATCH_VERSION_REPLY_PATH,
                        version.toByteArray(Charsets.UTF_8),
                    )
                    .await()
                Log.i(TAG, "Version reply sent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply with version", e)
            }
        }
    }

    private fun receiveAndInstallApk(channel: ChannelClient.Channel) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channelClient = Wearable.getChannelClient(applicationContext)
                val updatesDir = File(cacheDir, "updates")
                updatesDir.mkdirs()
                val apkFile = File(updatesDir, "watch-update.apk")

                Log.i(TAG, "Receiving APK via input stream...")
                val inputStream = channelClient.getInputStream(channel).await()
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytes = 0L
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    Log.i(TAG, "APK received: $totalBytes bytes")
                }
                inputStream.close()
                channelClient.close(channel).await()

                if (apkFile.length() < 1000) {
                    Log.e(TAG, "APK too small (${apkFile.length()} bytes), skipping install")
                    return@launch
                }

                val contentUri = FileProvider.getUriForFile(
                    applicationContext, "${packageName}.fileprovider", apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Primary path: pop the installer up directly. On Wear OS the
                // foreground installer appears immediately after the transfer —
                // that's the expected UX and what worked before. (Officially a
                // background activity launch is restricted on Android 10+, but
                // the watch grants it here; we don't rely on that assumption.)
                var popped = false
                try {
                    startActivity(installIntent)
                    popped = true
                    Log.i(TAG, "Install activity launched")
                } catch (e: Exception) {
                    Log.w(TAG, "Direct install launch failed: ${e.message}")
                }
                // Fallback: if the OS ever silently drops the launch, a tappable
                // notification guarantees the install is never a dead end.
                showInstallNotification(installIntent)
                Log.i(TAG, "Install notification posted (popup launched=$popped)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to receive/install APK", e)
            }
        }
    }

    private fun showInstallNotification(installIntent: Intent) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "watch_updates", "Watch updates", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Watch app update ready to install" }
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, "watch_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Watch update ready")
            .setContentText("Tap to install the new Remote Paradox watch app")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(9001, notification)
    }
}
