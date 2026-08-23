package com.nutrilens.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nutrilens.app.MainActivity

/**
 * Помощник для всех уведомлений приложения: создаёт каналы и постит
 * уведомления из воркеров/ресиверов.
 */
object NotificationHelper {

    const val CHANNEL_ANALYSIS = "analysis"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_WATER = "water"

    /** Создаёт (идемпотентно) все каналы уведомлений. */
    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ANALYSIS,
                "Результаты анализа",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Напоминания",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATER,
                "Вода",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /** Тестовое уведомление для экрана настроек (канал reminders, id 9001). */
    fun postSample(context: Context, title: String, text: String) {
        post(context, CHANNEL_REMINDERS, 9001, title, text, mainActivityPendingIntent(context, 9001))
    }

    /** Внутренняя функция постинга уведомлений (используется из воркеров/ресиверов). */
    fun post(
        context: Context,
        channelId: String,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        ongoing: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Intent на MainActivity с опциональными extras (navigate/date). */
    fun mainActivityIntent(
        context: Context,
        navigate: String? = null,
        date: String? = null
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (navigate != null) putExtra("navigate", navigate)
            if (date != null) putExtra("date", date)
        }
    }

    /** PendingIntent, открывающий MainActivity (опционально с extras). */
    fun mainActivityPendingIntent(
        context: Context,
        requestCode: Int,
        navigate: String? = null,
        date: String? = null
    ): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            mainActivityIntent(context, navigate, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}