package com.wmail.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL_MAIL = "mail"
    private const val CHANNEL_SERVICE = "service"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "新邮件", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "同步服务", NotificationManager.IMPORTANCE_MIN),
        )
    }

    private fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyNewMail(context: Context, accountName: String, subject: String, id: Int) {
        ensureChannel(context)
        if (!canNotify(context)) return
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(accountName)
            .setContentText(subject.ifBlank { "收到新邮件" })
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun serviceNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("w-mail 正在实时同步")
            .setOngoing(true)
            .build()
    }
}
