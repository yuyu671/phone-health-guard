package com.anxin.guard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {

    const val CH_STATUS = "guard_status"
    const val CH_ALERT = "guard_alert"
    const val ID_STATUS = 1001
    const val ID_ALERT = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val statusChannel = NotificationChannel(
            CH_STATUS,
            "守护状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "常驻显示本次使用时长"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CH_ALERT,
            "休息提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "达到使用上限、空间释放时提醒"
            enableVibration(true)
        }

        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun status(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(context))
            .build()

    @SuppressLint("MissingPermission")
    fun notifyStatus(context: Context, title: String, text: String) {
        try {
            NotificationManagerCompat.from(context).notify(ID_STATUS, status(context, title, text))
        } catch (ignored: Throwable) {
            // 通知被系统禁止时不要让守护崩溃
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyAlert(context: Context, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_ALERT, notification)
        } catch (ignored: Throwable) {
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
