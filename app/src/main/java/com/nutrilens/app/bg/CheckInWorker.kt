package com.nutrilens.app.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutrilens.app.data.CoachNudges
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.isCheckInDue
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.notifications.NotificationBudget
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
        // 3.29: одно напоминание программы в день — по важности: чек-ин, цикл, подсказка.
        notifyCheckIn(today, settings)
        notifyCycle(today, settings)
        notifyNudge(today)
        return Result.success()
    }

    /** Утро дня чек-ина: одно напоминание, если чек-ин ещё не сделан. */
    private fun notifyCheckIn(today: LocalDate, settings: com.nutrilens.app.data.SettingsEntity) {
        if (today.dayOfWeek != CheckInPlanner.dayOfWeek(settings.checkInWeekday)) return
        if (!settings.isCheckInDue(today)) return
        val hour = LocalTime.now().hour
        if (hour < 8 || hour >= 21) return
        val prefs = applicationContext.getSharedPreferences("checkin", Context.MODE_PRIVATE)
        val day = today.toEpochDay()
        if (prefs.getLong("last_notified", 0L) == day) return
        if (!NotificationBudget.claim(applicationContext, NotificationBudget.Kind.CHECK_IN, today)) return
        prefs.edit().putLong("last_notified", day).apply()

        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_REMINDERS,
            NOTIFICATION_ID,
            "Пора подвести неделю",
            "30 секунд: вес и как прошла неделя — и план на новую неделю",
            NotificationHelper.mainActivityPendingIntent(applicationContext, NOTIFICATION_ID)
        )
    }

    /**
     * Помощник пишет первым (3.25): подсказка программы — одним уведомлением днём (10–20 ч),
     * по каждой подсказке не больше одного раза. На «Сегодня» она ещё и карточкой.
     */
    private suspend fun notifyNudge(today: LocalDate) {
        val hour = LocalTime.now().hour
        if (hour < 10 || hour >= 20) return
        if (NotificationBudget.spent(applicationContext, today)) return
        val nudge = runCatching { CoachNudges.load(applicationContext, today) }.getOrNull() ?: return
        if (!CoachNudges.markNotified(applicationContext, nudge.key, today)) return
        if (!NotificationBudget.claim(applicationContext, NotificationBudget.Kind.NUDGE, today)) return
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_REMINDERS,
            NUDGE_NOTIFICATION_ID,
            nudge.title,
            nudge.text,
            NotificationHelper.mainActivityPendingIntent(applicationContext, NUDGE_NOTIFICATION_ID)
        )
    }

    /**
     * Цикл (3.27): в ожидаемый день — одно уведомление «Начались?» днём, по дню не больше
     * одного. Ответить можно карточкой на «Сегодня».
     */
    private fun notifyCycle(today: LocalDate, settings: com.nutrilens.app.data.SettingsEntity) {
        val hour = LocalTime.now().hour
        if (hour < 10 || hour >= 20) return
        if (!com.nutrilens.app.data.CycleStore.shouldAsk(applicationContext, settings, today)) return
        val prefs = applicationContext.getSharedPreferences("checkin", Context.MODE_PRIVATE)
        if (prefs.getLong("cycle_notified", 0L) == today.toEpochDay()) return
        if (!NotificationBudget.claim(applicationContext, NotificationBudget.Kind.CYCLE, today)) return
        prefs.edit().putLong("cycle_notified", today.toEpochDay()).apply()
        NotificationHelper.post(
            applicationContext,
            NotificationHelper.CHANNEL_REMINDERS,
            CYCLE_NOTIFICATION_ID,
            "Месячные начались?",
            "Отметь на «Сегодня» — тогда чек-ин не примет воду за «вес стоит»",
            NotificationHelper.mainActivityPendingIntent(applicationContext, CYCLE_NOTIFICATION_ID)
        )
    }

    companion object {
        const val CYCLE_NOTIFICATION_ID = 1005
        const val NOTIFICATION_ID = 1003
        const val NUDGE_NOTIFICATION_ID = 1004
    }
}
