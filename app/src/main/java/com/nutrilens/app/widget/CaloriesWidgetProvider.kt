package com.nutrilens.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import com.nutrilens.app.MainActivity
import com.nutrilens.app.R
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.insights.effectiveMacroGoals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

/**
 * Виджет 2×1 «Калории»: белая капсула с кольцом прогресса (калории +
 * белки/углеводы вложенной дугой) и списком Б/Ж/У текущего дня.
 * Обновляется при изменении дневника (см. WidgetUpdater) и по расписанию.
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
                ?: SettingsEntity()
            val meals = db.mealDao().mealsBetween(today, today)
            val eaten = meals.sumOf { it.calories }
            val goal = settings.dailyGoal
            val remaining = (goal - eaten).roundToInt()
            val isOver = remaining < 0
            val weightKg = db.weightDao().latest()?.weight

            val protein = meals.sumOf { it.protein }.roundToInt()
            val fat = meals.sumOf { it.fat }.roundToInt()
            val carbs = meals.sumOf { it.carbs }.roundToInt()
            val macroGoals = effectiveMacroGoals(
                settings.proteinGoal, settings.fatGoal, settings.carbsGoal,
                goal, weightKg
            )

            // Светлая/тёмная тема: eсли в системе тёмный режим — тёмная капсула.
            val dark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            // В БД load: синхронный доступ из фонового потока допустим (short-lived).
            val views = RemoteViews(
                context.packageName,
                if (dark) R.layout.widget_calories_dark else R.layout.widget_calories
            )
            views.setImageViewBitmap(
                R.id.widget_ring,
                drawRingBitmap(
                    context,
                    calFraction = (eaten / goal).toFloat().coerceIn(0f, 1f),
                    carbsFraction = if (macroGoals.carbs > 0) {
                        (carbs / macroGoals.carbs).toFloat().coerceIn(0f, 1f)
                    } else 0f,
                    proteinFraction = if (macroGoals.protein > 0) {
                        (protein / macroGoals.protein).toFloat().coerceIn(0f, 1f)
                    } else 0f,
                    isOver = isOver,
                    dark = dark
                )
            )
            if (dark) {
                views.setImageViewResource(R.id.widget_dot_p, R.drawable.widget_dot_protein_dark)
                views.setImageViewResource(R.id.widget_dot_f, R.drawable.widget_dot_fat_dark)
                views.setImageViewResource(R.id.widget_dot_c, R.drawable.widget_dot_carbs_dark)
            }
            views.setTextViewText(
                R.id.widget_remain,
                abs(remaining).toString()
            )
            // Перебор — оранжевым, фирменный акцент тревоги.
            views.setTextColor(
                R.id.widget_remain,
                if (isOver) {
                    if (dark) COLOR_ORANGE_DARK else COLOR_ORANGE
                } else {
                    if (dark) COLOR_WHITE else COLOR_INK
                }
            )
            views.setTextViewText(
                R.id.widget_remain_unit,
                if (isOver) "перебор" else "ккал"
            )
            views.setTextViewText(R.id.widget_p, protein.toString())
            views.setTextViewText(R.id.widget_f, fat.toString())
            views.setTextViewText(R.id.widget_c, carbs.toString())

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

        private const val COLOR_GREEN = 0xFF059669.toInt()
        private const val COLOR_BLUE = 0xFF2F6FD0.toInt()
        private const val COLOR_PURPLE = 0xFF7D5FD6.toInt()
        private const val COLOR_ORANGE = 0xFFEA580C.toInt()
        private const val COLOR_INK = 0xFF0F172A.toInt()
        private const val COLOR_WHITE = 0xFFF8FAFC.toInt()

        // Светлая/тёмная палитра макро.
        private const val COLOR_GREEN_DARK = 0xFF10B981.toInt()
        private const val COLOR_BLUE_DARK = 0xFF6BA3F2.toInt()
        private const val COLOR_PURPLE_DARK = 0xFFA98FF0.toInt()
        private const val COLOR_ORANGE_DARK = 0xFFFB923C.toInt()

        /**
         * Кольцо прогресса: внешнее — калории (зелёное, при переборе
         * оранжевое), вложенная дуга — белки (голубое) и углеводы
         * (фиолетовое), плюс три бусины. Рисуем в bitmap: кастомные View
         * внутри RemoteViews грузятся не всеми лаунчерами (класс может
         * уехать в небазовый dex), а ImageView с bitmap поддерживают все.
         */
        private fun drawRingBitmap(
            context: Context,
            calFraction: Float,
            carbsFraction: Float,
            proteinFraction: Float,
            isOver: Boolean,
            dark: Boolean
        ): Bitmap {
            val density = context.resources.displayMetrics.density
            // Суперсэмплинг ×3, чтобы на плотных экранах кольцо было гладким.
            val size = (52f * density * 3f).toInt().coerceAtLeast(200)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f

            val calColor = when {
                dark && isOver -> COLOR_ORANGE_DARK
                dark -> COLOR_GREEN_DARK
                isOver -> COLOR_ORANGE
                else -> COLOR_GREEN
            }
            val proteinColor = if (dark) COLOR_BLUE_DARK else COLOR_BLUE
            val carbsColor = if (dark) COLOR_PURPLE_DARK else COLOR_PURPLE
            val beadColors = if (dark) intArrayOf(COLOR_GREEN_DARK, COLOR_BLUE_DARK, COLOR_PURPLE_DARK)
            else intArrayOf(COLOR_GREEN, COLOR_BLUE, COLOR_PURPLE)
            val trackAlpha = if (dark) 0.22f else 0.14f

            // Толщины и радиусы колец: жирные, чтобы читались на маленьком размере.
            val s1 = 7.0f * density
            val s2 = 4.2f * density
            val r1 = size / 2f - s1 / 2f
            val r2 = r1 - s1 / 2f - 1.6f * density - s2 / 2f

            fun ring(radius: Float, stroke: Float, color: Int, fraction: Float, trackAlpha: Float) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    strokeCap = Paint.Cap.ROUND
                }
                // Трек: бледный тон цвета дуги (читается на капсуле).
                paint.color = color
                paint.alpha = (trackAlpha * 255).toInt()
                canvas.drawCircle(cx, cy, radius, paint)
                if (fraction > 0.01f) {
                    paint.alpha = 255
                    canvas.drawArc(
                        RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                        -90f, 360f * fraction.coerceIn(0f, 1f), false, paint
                    )
                }
            }

            ring(r1, s1, calColor, calFraction, trackAlpha)

            // Внутреннее кольцо: сначала дуга белков (голубая), затем углеводов.
            val innerTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = s2
                strokeCap = Paint.Cap.ROUND
                color = carbsColor
                alpha = (trackAlpha * 255).toInt()
            }
            canvas.drawCircle(cx, cy, r2, innerTrack)
            if (proteinFraction > 0.01f) {
                innerTrack.alpha = 255
                innerTrack.color = proteinColor
                canvas.drawArc(
                    RectF(cx - r2, cy - r2, cx + r2, cy + r2),
                    -90f, 360f * proteinFraction.coerceIn(0f, 1f), false, innerTrack
                )
            }
            if (carbsFraction > 0.01f) {
                innerTrack.alpha = 255
                innerTrack.color = carbsColor
                canvas.drawArc(
                    RectF(cx - r2, cy - r2, cx + r2, cy + r2),
                    -90f + 360f * proteinFraction.coerceIn(0f, 1f),
                    360f * carbsFraction.coerceIn(0f, 1f), false, innerTrack
                )
            }

            // Бусины сверху — изумрудная, голубая, фиолетовая (палитра макро).
            fun bead(angleDeg: Float, radius: Float, color: Int) {
                val a = Math.toRadians(angleDeg.toDouble())
                val x = cx + (radius * sin(a)).toFloat()
                val y = cy - (radius * cos(a)).toFloat()
                val br = 2.8f * density
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = color
                canvas.drawCircle(x, y, br, paint)
            }
            bead(-22f, r1, beadColors[0])
            bead(4f, r1, beadColors[1])
            bead(28f, r1, beadColors[2])
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
