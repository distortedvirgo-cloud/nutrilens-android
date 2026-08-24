package com.nutrilens.app.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.WeightRepository
import com.nutrilens.app.notifications.NotificationHelper

/**
 * Ежедневное напоминание взвестись, если давно (>= 7 дней) не записывали вес.
 */
class WeighInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = NutriLensDatabase.getInstance(applicationContext)
        val weightRepo = WeightRepository(db.weightDao())
        val daysSince = weightRepo.daysSinceLastWeight()
        if (daysSince == null || daysSince >= 7) {
            // Не чаще раза в 7 дней (иначе воркер спамил бы каждый день).
            val prefs = applicationContext.getSharedPreferences("weighin", Context.MODE_PRIVATE)
            val today = java.time.LocalDate.now().toEpochDay()
            val lastNotified = prefs.getLong("last_notified", 0L)
            if (today - lastNotified >= 7) {
                prefs.edit().putLong("last_notified", today).apply()
                val contentIntent = NotificationHelper.mainActivityPendingIntent(applicationContext, 1002)
                NotificationHelper.post(
                    applicationContext,
                    NotificationHelper.CHANNEL_REMINDERS,
                    1002,
                    "Давно не взвешивались ⚖️",
                    "Запишите вес, чтобы статистика была точной",
                    contentIntent
                )
            }
        }
        return Result.success()
    }
}