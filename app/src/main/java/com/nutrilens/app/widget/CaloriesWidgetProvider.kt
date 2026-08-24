package com.nutrilens.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
            val isOver = remaining < 0
            views.setImageViewBitmap(
                R.id.widget_ring,
                drawRingBitmap(context, (eaten / goal).toFloat().coerceIn(0f, 1f), isOver)
            )
            views.setTextViewText(R.id.widget_remain_label, if (isOver) "Перебор" else "Осталось")
            if (isOver) {
                views.setTextViewText(R.id.widget_remain, "${abs(remaining)} ккал")
            } else {
                views.setTextViewText(R.id.widget_remain, "$remaining ккал")
            }
            val protein = meals.sumOf { it.protein }.roundToInt()
            val fat = meals.sumOf { it.fat }.roundToInt()
            val carbs = meals.sumOf { it.carbs }.roundToInt()
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

        /**
         * Кольцо прогресса рисуем в битмап прямо в провайдере: кастомные View
         * внутри RemoteViews грузятся не всеми лаунчерами (класс может уехать
         * в небазовый dex), а ImageView с bitmap поддерживают все.
         */
        private fun drawRingBitmap(context: Context, fraction: Float, isOver: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density
            val size = (52 * density).toInt().coerceAtLeast(64)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val stroke = size * 0.12f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
            }
            val inset = stroke / 2f + 1f
            val rect = RectF(inset, inset, size - inset, size - inset)
            paint.color = 0xFFDCFCE7.toInt()
            canvas.drawArc(rect, 0f, 360f, false, paint)
            if (fraction > 0f) {
                paint.color = if (isOver) 0xFFEA580C.toInt() else 0xFF059669.toInt()
                canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
            }
            return bmp
        }
    }
}

/** Точки вызова обновления виджета (из воркера и экранов). */
object WidgetUpdater {
    fun refresh(context: Context) {
        CaloriesWidgetProvider.requestUpdate(context)
    }
}