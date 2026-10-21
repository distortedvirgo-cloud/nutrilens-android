package com.nutrilens.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import com.nutrilens.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Виджет 4×2 «Сегодня»: калории, остаток, БЖУ, вода, кнопки «Еда» и «+250». */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        WidgetSupport.update(context, TodayWidgetProvider::class.java, appWidgetIds, ::build) { pending.finish() }
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
        fun requestUpdate(context: Context) {
            WidgetSupport.update(context, TodayWidgetProvider::class.java, build = ::build)
        }

        private suspend fun build(context: Context): RemoteViews {
            val day = WidgetSupport.load(context)
            val dark = WidgetSupport.isDark(context)
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val ringColor = when {
                day.isOver && dark -> WidgetSupport.ORANGE_DARK
                day.isOver -> WidgetSupport.ORANGE
                dark -> WidgetSupport.GREEN_DARK
                else -> WidgetSupport.GREEN
            }
            views.setImageViewBitmap(
                R.id.w_ring,
                WidgetSupport.ring(
                    context, 84f, 8f, day.fraction(day.eaten, day.goal), ringColor,
                    if (dark) WidgetSupport.TRACK_DARK else WidgetSupport.TRACK
                )
            )
            views.setTextViewText(R.id.w_eaten, day.eaten.roundToInt().toString())
            views.setTextViewText(R.id.w_goal, "из ${day.goal.roundToInt()}")
            views.setTextViewText(R.id.w_remain_label, if (day.isOver) "ПЕРЕБОР" else "ОСТАЛОСЬ")
            views.setTextViewText(R.id.w_remain, "${abs(day.remaining)} ккал")
            views.setTextColor(R.id.w_remain, ringColor)

            fun macro(textId: Int, barId: Int, label: String, value: Double, goal: Double) {
                views.setTextViewText(textId, "$label ${value.roundToInt()} / ${goal.roundToInt()} г")
                views.setProgressBar(barId, 100, (day.fraction(value, goal) * 100).roundToInt(), false)
            }
            macro(R.id.w_protein_text, R.id.w_protein_bar, "Белки", day.protein, day.proteinGoal)
            macro(R.id.w_fat_text, R.id.w_fat_bar, "Жиры", day.fat, day.fatGoal)
            macro(R.id.w_carbs_text, R.id.w_carbs_bar, "Углеводы", day.carbs, day.carbsGoal)

            views.setTextViewText(R.id.w_water, "Вода ${day.waterMl} / ${day.waterNormMl} мл")
            views.setOnClickPendingIntent(R.id.widget_root, WidgetSupport.openApp(context, 1101, "dashboard"))
            views.setOnClickPendingIntent(R.id.w_btn_camera, WidgetSupport.openApp(context, 1102, "add/camera"))
            views.setOnClickPendingIntent(R.id.w_btn_water, WidgetSupport.addWaterIntent(context))
            return views
        }
    }
}

/** Виджет 2×2 «Вода»: кольцо выпитого от нормы и кнопка «+250 мл». */
class WaterWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        WidgetSupport.update(context, WaterWidgetProvider::class.java, appWidgetIds, ::build) { pending.finish() }
    }

    companion object {
        fun requestUpdate(context: Context) {
            WidgetSupport.update(context, WaterWidgetProvider::class.java, build = ::build)
        }

        private suspend fun build(context: Context): RemoteViews {
            val day = WidgetSupport.load(context)
            val dark = WidgetSupport.isDark(context)
            val views = RemoteViews(context.packageName, R.layout.widget_water)
            views.setImageViewBitmap(
                R.id.w_ring,
                WidgetSupport.ring(
                    context, 96f, 9f,
                    day.fraction(day.waterMl.toDouble(), day.waterNormMl.toDouble()),
                    if (dark) WidgetSupport.WATER_DARK else WidgetSupport.WATER,
                    if (dark) WidgetSupport.TRACK_DARK else WidgetSupport.TRACK
                )
            )
            views.setTextViewText(R.id.w_water_ml, "${day.waterMl}")
            views.setTextViewText(R.id.w_water_norm, "из ${day.waterNormMl} мл")
            views.setOnClickPendingIntent(R.id.widget_root, WidgetSupport.openApp(context, 1201, "dashboard"))
            views.setOnClickPendingIntent(R.id.w_btn_water, WidgetSupport.addWaterIntent(context))
            return views
        }
    }
}

/** Виджет 1×1: камера для фото еды одним тапом. */
class QuickAddWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetSupport.openApp(context, 1301, "add/camera"))
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }
}
