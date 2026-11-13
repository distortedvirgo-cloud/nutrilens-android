package com.nutrilens.app.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutrilens.app.data.CoachNudges
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.isCheckInDue
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.notifications.NotificationHelper
import java.time.LocalDate
import java.time.LocalTime

/**
 * Напоминание о недельном чек-ине утром в день чек-ина. Воркер периодический
 * (раз в несколько часов), а решает сам: режим включён, сегодня день чек-ина,
 * чек-ин ещё не сделан, уже утро и сегодня ещё не напоминали. Пропущенный
 * чек-ин повторно не «долбит» — его ждёт карточка на «Сегодня». На паузе программы
 * (3.24) напоминаний нет: isCheckInDue учитывает паузу.
 */
class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(NutriLensDatabase.getInstance(applicationContext).settingsDao()).get()
        // Чек-ин начинается со взвешивания — уважаем переключатель «Взвешивание» в настройках.
        if (!settings.weighInReminderEnabled || settings.targetFatPercent == null) return Result.success()
        val today = LocalDate.now()
        notifyNudge(today)
        if (today.dayOfWeek != CheckInPlanner.dayOfWeek(settings.checkInWeekday)) return Result.success()
        if (!settings.isCheckInDue(today)) return Result.success()
        val hour = LocalTime.now().hour
        if (hour < 8 || hour >= 21) return Result.success()

        val prefs = applicationContext.getSharedPreferences("checkin", Context.MODE_PRIVATE)
        val day = today.toEpochDay()
        if (prefs.getLong("last_notified", 0L) == day) return Result.success()
        prefs.edit().putLong("last_notified", day).apply()

        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_REMINDERS,
            NOTIFICATION_ID,
            "Пора подвести неделю",
            "Чек-ин займёт 2 минуты: вес, замер и самочувствие — и план на новую неделю",
            NotificationHelper.mainActivityPendingIntent(applicationContext, NOTIFICATION_ID)
        )
        return Result.success()
    }

    /**
     * Коуч пишет первым (3.25): подсказка программы — одним уведомлением днём (10–20 ч),
     * по каждой подсказке не больше одного раза. На «Сегодня» она ещё и карточкой.
     */
    private suspend fun notifyNudge(today: LocalDate) {
        val hour = LocalTime.now().hour
        if (hour < 10 || hour >= 20) return
        val nudge = runCatching { CoachNudges.load(applicationContext, today) }.getOrNull() ?: return
        if (!CoachNudges.markNotified(applicationContext, nudge.key, today)) return
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_REMINDERS,
            NUDGE_NOTIFICATION_ID,
            nudge.title,
            nudge.text,
            NotificationHelper.mainActivityPendingIntent(applicationContext, NUDGE_NOTIFICATION_ID)
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1003
        const val NUDGE_NOTIFICATION_ID = 1004
    }
}
