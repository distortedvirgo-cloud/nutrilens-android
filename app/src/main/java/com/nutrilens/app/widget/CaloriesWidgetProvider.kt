package com.nutrilens.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import com.nutrilens.app.MainActivity
import com.nutrilens.app.R
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Виджет 2×1 «Калории»: сколько осталось на сегодня + макросы.
 * Обновляется при изменении дневника (см. WidgetUpdater) и по расписанию
 * (updatePeriodMillis, каждые 30 минут).
 */
class CaloriesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = buildViews(context)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } catch (_: Exception) {
                // Виджет не должен ронять приложение — данные исправит следующее обновление.
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    companion object {
        private suspend fun buildViews(context: Context): RemoteViews {
            val db = NutriLensDatabase.getInstance(context)
            val today = LocalDate.now().toString()
            val settings = SettingsRepository(db.settingsDao()).get()
                ?: com.nutrilens.app.data.SettingsEntity()
            val meals = db.mealDao().mealsBetween(today, today)
            val eaten = meals.sumOf { it.calories }
            val goal = settings.dailyGoal
            val remaining = (goal - eaten).roundToInt()

            // В БД load: синхронный доступ из фонового потока допустим (short-lived).
            val views = RemoteViews(context.packageName, R.layout.widget_calories)
            if (remaining >= 0) {
                views.setTextViewText(R.id.widget_remain, "Осталось $remaining ккал")
            } else {
                views.setTextViewText(R.id.widget_remain, "Перебор ${abs(remaining)} ккал")
            }
            val m = meals
            val protein = m.sumOf { it.protein }.roundToInt()
            val fat = m.sumOf { it.fat }.roundToInt()
            val carbs = m.sumOf { it.carbs }.roundToInt()
            views.setTextViewText(R.id.widget_macros, "Б $protein · Ж $fat · У $carbs")

            views.setOnClickPendingIntent(
                R.id.widget_root,
                com.nutrilens.app.notifications.NotificationHelper.mainActivityPendingIntent(
                    context, 1001, navigate = "dashboard"
                )
            )
            return views
        }

        /** Перерисовать все виджеты (вызывается после изменений дневника). */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, CaloriesWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val views = buildViews(appContext)
                    ids.forEach { manager.updateAppWidget(it, views) }
                } catch (_: Exception) {
                }
            }
        }
    }
}

/** Точки вызова обновления виджета (из воркера и экранов). */
object WidgetUpdater {
    fun refresh(context: Context) {
        CaloriesWidgetProvider.requestUpdate(context)
    }
}