package com.nutrilens.app.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.notifications.NotificationHelper
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Периодическое напоминание выпить воды (в рабочие часы 8–21, если норма
 * ещё не выполнена).
 */
class WaterReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val hour = LocalTime.now().hour
        if (hour !in 8..21) return Result.success()

        val db = NutriLensDatabase.getInstance(applicationContext)
        val today = LocalDate.now().toString()
        val drunkMl = db.waterDao().byDate(today)?.ml ?: 0

        val lastWeight = db.weightDao().latest()?.weight ?: 75.0
        val normMl = lastWeight * 35
        if (drunkMl >= normMl) return Result.success()

        val text = "Сегодня: $drunkMl мл из ~${normMl.roundToInt()} мл"
        val contentIntent = NotificationHelper.mainActivityPendingIntent(applicationContext, 1001)
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_WATER,
            1001,
            "Выпейте стакан воды 💧",
            text,
            contentIntent
        )
        return Result.success()
    }
}