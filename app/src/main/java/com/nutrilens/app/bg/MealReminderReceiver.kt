package com.nutrilens.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Будильник приёма пищи: шлёт уведомление и перепланирует себя
 * на следующее срабатывание. Если пользователь уже записал еду
 * в последние полтора часа — напоминание пропускается, чтобы
 * не дублировать недавний приём пищи.
 */
class MealReminderReceiver : BroadcastReceiver() {

    /** Свежая запись еды в этом окне до срабатывания: напоминание не нужно. */
    private val recentMealWindowMs = 90L * 60_000L

    override fun onReceive(context: Context, intent: Intent) {
        val meal = intent.getStringExtra(EXTRA_MEAL) ?: return
        if (meal != MEAL_BREAKFAST && meal != MEAL_LUNCH && meal != MEAL_DINNER) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val db = NutriLensDatabase.getInstance(context)
                val settings = SettingsRepository(db.settingsDao()).get()

                // Недавно поел (еда записана за последние 90 минут) —
                // без дублирующего напоминания, сразу перепланируемся.
                val ateRecently = db.mealDao().countSince(System.currentTimeMillis() - recentMealWindowMs) > 0
                if (ateRecently) {
                    ReminderSync.rescheduleMeal(context, meal)
                    return@launch
                }

                val mealsToday = db.mealDao().mealsByDate(date).first()
                val sumCalories = mealsToday.sumOf { it.calories }

                val title = when (meal) {
                    MEAL_BREAKFAST -> "Пора позавтракать 🍳"
                    MEAL_LUNCH -> "Пора пообедать 🍲"
                    else -> "Пора поужинать 🍽️"
                }
                val text = "Съедено сегодня: ${sumCalories.roundToInt()} из ${settings.dailyGoal.roundToInt()} ккал"
                val notificationId = when (meal) {
                    MEAL_BREAKFAST -> 1101
                    MEAL_LUNCH -> 1102
                    else -> 1103
                }
                val contentIntent = NotificationHelper.mainActivityPendingIntent(
                    context,
                    notificationId,
                    navigate = "dashboard",
                    date = date
                )
                NotificationHelper.post(
                    context,
                    NotificationHelper.CHANNEL_REMINDERS,
                    notificationId,
                    title,
                    text,
                    contentIntent
                )

                // Перепланировать только этот будильник на следующее срабатывание.
                ReminderSync.rescheduleMeal(context, meal)
            } finally {
                pendingResult.finish()
            }
        }
    }
}