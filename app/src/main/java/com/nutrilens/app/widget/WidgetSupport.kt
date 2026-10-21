package com.nutrilens.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.QuickActions
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.insights.effectiveMacroGoals
import com.nutrilens.app.insights.waterNormaMl
import com.nutrilens.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Снимок дня для виджетов: одно чтение БД на обновление. */
internal data class WidgetDay(
    val eaten: Double,
    val goal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val proteinGoal: Double,
    val fatGoal: Double,
    val carbsGoal: Double,
    val waterMl: Int,
    val waterNormMl: Int
) {
    val remaining: Int get() = (goal - eaten).toInt()
    val isOver: Boolean get() = goal > 0 && eaten > goal
    fun fraction(value: Double, target: Double): Float =
        if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f
}

internal object WidgetSupport {
    suspend fun load(context: Context): WidgetDay {
        val db = NutriLensDatabase.getInstance(context)
        val today = LocalDate.now().toString()
        val settings = SettingsRepository(db.settingsDao()).get()
        val meals = db.mealDao().mealsBetween(today, today)
        val weight = db.weightDao().latest()?.weight
        val workout = db.workoutDao().byDate(today)?.done ?: false
        val goals = effectiveMacroGoals(
            settings.proteinGoal, settings.fatGoal, settings.carbsGoal, settings.dailyGoal, weight
        )
        return WidgetDay(
            eaten = meals.sumOf { it.calories },
            goal = settings.dailyGoal,
            protein = meals.sumOf { it.protein },
            fat = meals.sumOf { it.fat },
            carbs = meals.sumOf { it.carbs },
            proteinGoal = goals.protein,
            fatGoal = goals.fat,
            carbsGoal = goals.carbs,
            waterMl = db.waterDao().byDate(today)?.ml ?: 0,
            waterNormMl = waterNormaMl(weight, workout)
        )
    }

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Открыть приложение на маршруте (add/camera, dashboard, coach…). */
    fun openApp(context: Context, requestCode: Int, route: String): PendingIntent =
        NotificationHelper.mainActivityPendingIntent(context, requestCode, navigate = route)

    /** +250 мл воды прямо из виджета, без открытия приложения. */
    fun addWaterIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            2001,
            Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_ADD_WATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Кольцо прогресса в bitmap (кастомные View в RemoteViews грузятся не
     * всеми лаунчерами, ImageView с bitmap — всеми). Размеры в dp, рисуем
     * с запасом по плотности для чёткости.
     */
    fun ring(
        context: Context,
        sizeDp: Float,
        strokeDp: Float,
        fraction: Float,
        color: Int,
        trackColor: Int
    ): Bitmap {
        val k = context.resources.displayMetrics.density * 1.5f
        val size = (sizeDp * k).toInt().coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = strokeDp * k
        val r = size / 2f - stroke / 2f
        val rect = RectF(size / 2f - r, size / 2f - r, size / 2f + r, size / 2f + r)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = trackColor
        canvas.drawArc(rect, 0f, 360f, false, paint)
        if (fraction > 0.005f) {
            paint.color = color
            canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, paint)
        }
        return bmp
    }

    /** Общая схема обновления: читаем данные в IO и раздаём всем экземплярам. */
    fun update(
        context: Context,
        provider: Class<out AppWidgetProvider>,
        ids: IntArray? = null,
        build: suspend (Context) -> RemoteViews,
        onDone: () -> Unit = {}
    ) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val targets = ids ?: manager.getAppWidgetIds(ComponentName(appContext, provider))
        if (targets.isEmpty()) {
            onDone()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = build(appContext)
                targets.forEach { manager.updateAppWidget(it, views) }
            } catch (_: Exception) {
                // Виджет не должен ронять приложение — поправит следующее обновление.
            } finally {
                onDone()
            }
        }
    }

    // Палитра виджетов (совпадает с темой приложения).
    const val GREEN = 0xFF12A174.toInt()
    const val GREEN_DARK = 0xFF3DDC97.toInt()
    const val ORANGE = 0xFFD9620F.toInt()
    const val ORANGE_DARK = 0xFFFDBA74.toInt()
    const val WATER = 0xFF1F6FAF.toInt()
    const val WATER_DARK = 0xFF6CC0F5.toInt()
    const val TRACK = 0x1A0D1411
    const val TRACK_DARK = 0x24FFFFFF
}

/** Действия из виджетов, выполняемые без открытия приложения. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_WATER) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuickActions.addWater(context.applicationContext, 250)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ADD_WATER = "com.nutrilens.app.widget.ADD_WATER"
    }
}
