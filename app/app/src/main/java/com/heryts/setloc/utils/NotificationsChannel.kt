package com.heryts.setloc.utils

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.heryts.setloc.R

class NotificationsChannel {
    private var channelReady = false

    fun showNotification(context: Context, options: (NotificationCompat.Builder) -> Unit) {
        if (!channelReady) {
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.title))
                .setDescription(context.getString(R.string.des))
                .build()
                .also { NotificationManagerCompat.from(context).createNotificationChannel(it) }
            channelReady = true
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).also(options).build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    fun cancelAllNotifications(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    private companion object {
        const val CHANNEL_ID = "set.location"
        const val NOTIF_ID = 123
    }
}
