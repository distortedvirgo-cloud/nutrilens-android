package com.nutrilens.app.bg

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal const val EXTRA_MEAL = "meal"
internal const val MEAL_BREAKFAST = "breakfast"
internal const val MEAL_LUNCH = "lunch"
internal const val MEAL_DINNER = "dinner"

internal const val REQUEST_BREAKFAST = 101
internal const val REQUEST_LUNCH = 102
internal const val REQUEST_DINNER = 103

private const val UNIQUE_WATER = "water_reminder"
private const val UNIQUE_WEIGH_IN = "weighin_check"

/**
 * Перепланирование всех напоминаний из настроек: будильники еды (AlarmManager),
 * периодический воркер воды и ежедневный воркер взвешивания (WorkManager).
 */
object ReminderSync {

    /** Читает настройки и перепланирует ВСЕ напоминания. */
    suspend fun sync(context: Context) {
        val settings = SettingsRepository(NutriLensDatabase.getInstance(context).settingsDao()).get()

        scheduleMealAlarm(context, MEAL_BREAKFAST, REQUEST_BREAKFAST, settings.breakfastTime, settings.breakfastReminderEnabled)
        scheduleMealAlarm(context, MEAL_LUNCH, REQUEST_LUNCH, settings.lunchTime, settings.lunchReminderEnabled)
        scheduleMealAlarm(context, MEAL_DINNER, REQUEST_DINNER, settings.dinnerTime, settings.dinnerReminderEnabled)

        val workManager = WorkManager.getInstance(context)

        // Вода.
        if (settings.waterReminderEnabled) {
            val intervalMinutes = maxOf(15, settings.waterIntervalMinutes).toLong()
            val waterRequest = PeriodicWorkRequestBuilder<WaterReminderWorker>(intervalMinutes, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WATER, ExistingPeriodicWorkPolicy.UPDATE, waterRequest)
        } else {
            workManager.cancelUniqueWork(UNIQUE_WATER)
        }

        // Взвешивание.
        if (settings.weighInReminderEnabled) {
            val weighInRequest = PeriodicWorkRequestBuilder<WeighInWorker>(1, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_WEIGH_IN, ExistingPeriodicWorkPolicy.UPDATE, weighInRequest)
        } else {
            workManager.cancelUniqueWork(UNIQUE_WEIGH_IN)
        }
    }

    /**
     * Перепланирует ТОЛЬКО один будильник еды (используется MealReminderReceiver
     * после срабатывания).
     */
    suspend fun rescheduleMeal(context: Context, meal: String) {
        val settings = SettingsRepository(NutriLensDatabase.getInstance(context).settingsDao()).get()
        when (meal) {
            MEAL_BREAKFAST -> scheduleMealAlarm(context, MEAL_BREAKFAST, REQUEST_BREAKFAST, settings.breakfastTime, settings.breakfastReminderEnabled)
            MEAL_LUNCH -> scheduleMealAlarm(context, MEAL_LUNCH, REQUEST_LUNCH, settings.lunchTime, settings.lunchReminderEnabled)
            MEAL_DINNER -> scheduleMealAlarm(context, MEAL_DINNER, REQUEST_DINNER, settings.dinnerTime, settings.dinnerReminderEnabled)
        }
    }

    private fun scheduleMealAlarm(
        context: Context,
        meal: String,
        requestCode: Int,
        time: String,
        enabled: Boolean
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = mealAlarmPendingIntent(context, meal, requestCode)
        if (!enabled) {
            alarmManager.cancel(pendingIntent)
            return
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(time),
            pendingIntent
        )
    }

    /** PendingIntent на MealReminderReceiver с extra "meal". */
    fun mealAlarmPendingIntent(context: Context, meal: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MealReminderReceiver::class.java)
            .putExtra(EXTRA_MEAL, meal)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Ближайший будущий момент для времени "HH:mm": сегодня, если ещё не
     * наступило, иначе — завтра. Возвращает epoch millis в системной зоне.
     */
    fun nextTriggerMillis(time: String): Long {
        val parsed = LocalTime.parse(time)
        var trigger = LocalDate.now().atTime(parsed)
        if (!trigger.isAfter(LocalDateTime.now())) {
            trigger = trigger.plusDays(1)
        }
        return trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}