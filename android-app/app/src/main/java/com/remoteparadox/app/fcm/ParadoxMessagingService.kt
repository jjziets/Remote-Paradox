package com.remoteparadox.app.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.remoteparadox.app.MainActivity

class ParadoxMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushManager.register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushManager.ensureChannels(this)
        val data = message.data
        val critical = data["critical"] == "1" || data["type"] == "alarm"
        val title = message.notification?.title ?: data["title"] ?: "Remote Paradox"
        val body = message.notification?.body ?: data["body"] ?: ""
        val channel = if (critical) PushManager.CHANNEL_CRITICAL else PushManager.CHANNEL_ALERTS

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (critical) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (critical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(PushManager.criticalVibration)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        val id = if (critical) 1001 else (2000..2999).random()
        nm?.notify(id, notification)
    }
}
