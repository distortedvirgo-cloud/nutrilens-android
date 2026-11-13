package com.nutrilens.app.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * Сглаженный тренд веса и процента жира. Чистый Kotlin без Android.
 *
 * Зачем: вес за сутки гуляет на 0,5–1,5 кг от воды и соли, и голое число
 * пугает или зря радует. Экспоненциальное сглаживание (EMA) показывает,
 * куда реально движется тело. Сглаживание «по времени»: пропущенные дни
 * учитываются — чем дольше не взвешивался, тем сильнее новое число
 * сдвигает тренд (затухание применяется за каждый пропущенный день).
 */
object Trend {
    /** Вес нового измерения за один день шага: 0,25 — тренд реагирует за ~неделю. */
    const val ALPHA = 0.25

    /** Точка: дата, сырое значение и значение тренда на эту дату. */
    data class Point(val date: LocalDate, val raw: Double, val trend: Double)

    /**
     * Тренд по датированным точкам. Несколько значений за день — берётся последнее.
     * Возвращает точки по возрастанию даты.
     */
    fun smooth(points: List<FatBurnPlanner.DayValue>, alpha: Double = ALPHA): List<Point> {
        if (points.isEmpty()) return emptyList()
        // sortedBy стабилен: при двух значениях за день остаётся последнее из входа.
        val sorted = points.filter { it.value > 0 }.sortedBy { it.date }
        val daily = ArrayList<FatBurnPlanner.DayValue>(sorted.size)
        for (p in sorted) {
            if (daily.isNotEmpty() && daily[daily.lastIndex].date == p.date) daily[daily.lastIndex] = p
            else daily += p
        }
        val result = ArrayList<Point>(daily.size)
        var prevDate: LocalDate? = null
        var prevTrend = 0.0
        for (day in daily) {
            val date = day.date
            val raw = day.value
            val pd = prevDate
            val trend = if (pd == null) {
                raw
            } else {
                val gap = ChronoUnit.DAYS.between(pd, date).coerceAtLeast(1L).toInt()
                // За каждый день старое значение «остывает» на (1 − alpha).
                val a = 1 - (1 - alpha).pow(gap)
                prevTrend + a * (raw - prevTrend)
            }
            result += Point(date, raw, trend)
            prevDate = date
            prevTrend = trend
        }
        return result
    }

    /** Вес нового замера для редких замеров (лента, весы с % жира): половина — тренд ровнее одного замера. */
    const val MEASUREMENT_ALPHA = 0.5

    /**
     * Тренд для редких измерений (3.28): сглаживание по числу замеров, а не по дням. Сглаживание
     * «по времени» при замерах раз в неделю почти не сглаживает (новый замер весит ~87%),
     * и одна неточная лента могла закрыть блок. Несколько значений за день — последнее.
     */
    fun smoothByCount(points: List<FatBurnPlanner.DayValue>, alpha: Double = MEASUREMENT_ALPHA): List<Point> {
        val sorted = points.filter { it.value > 0 }.sortedBy { it.date }
        val daily = ArrayList<FatBurnPlanner.DayValue>(sorted.size)
        for (p in sorted) {
            if (daily.isNotEmpty() && daily[daily.lastIndex].date == p.date) daily[daily.lastIndex] = p
            else daily += p
        }
        val result = ArrayList<Point>(daily.size)
        var prev: Double? = null
        for (d in daily) {
            val t = prev?.let { it + alpha * (d.value - it) } ?: d.value
            result += Point(d.date, d.value, t)
            prev = t
        }
        return result
    }

    /** Значение тренда на дату: последняя точка не позже даты; null — точек до неё нет. */
    fun valueAt(points: List<Point>, date: LocalDate): Double? = points.lastOrNull { it.date <= date }?.trend

    /**
     * Темп потери по тренду, % веса в неделю (плюс — худеешь, минус — набираешь).
     * Берётся тренд сейчас и [windowDays] дней назад (или первая точка, если история
     * короче). null — меньше двух точек или они ближе 3 дней друг к другу.
     */
    fun weeklyLossPercent(points: List<Point>, windowDays: Long = 7): Double? {
        val last = points.lastOrNull() ?: return null
        val base = points.lastOrNull { it.date <= last.date.minusDays(windowDays) } ?: points.first()
        val span = ChronoUnit.DAYS.between(base.date, last.date)
        if (span < 3 || base.trend <= 0) return null
        return (base.trend - last.trend) / base.trend * 100 * 7 / span
    }

    /** Изменение тренда за неделю в единицах значения (кг или п.п. жира); минус — снижение. */
    fun weeklyChange(points: List<Point>, windowDays: Long = 7): Double? {
        val last = points.lastOrNull() ?: return null
        val base = points.lastOrNull { it.date <= last.date.minusDays(windowDays) } ?: points.first()
        val span = ChronoUnit.DAYS.between(base.date, last.date)
        if (span < 3) return null
        return (last.trend - base.trend) * 7 / span
    }
}
