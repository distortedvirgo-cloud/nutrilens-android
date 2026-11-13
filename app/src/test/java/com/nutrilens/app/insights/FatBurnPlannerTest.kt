package com.nutrilens.app.insights

import com.nutrilens.app.insights.FatBurnPlanner.DayValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

/**
 * Регрессии 3.28: симуляция тела (жир по энергобалансу, вода/гликоген, шум весов ±0,4 кг)
 * и решения недельного чек-ина. Каждый тест — ошибка, найденная при разборе режима.
 */
class FatBurnPlannerTest {
    private val d0 = LocalDate.of(2026, 1, 1)

    data class Week(val day: Int, val before: Double, val after: Double, val rate: Double?, val note: String)

    /**
     * [intake] — что человек ест в день d при плане; [glycogen] — к какому уровню тянется вода (кг);
     * [signal] и [hunger] — что приходит в чек-ин; [phaseStart] — день начала текущего этапа.
     */
    private fun simulate(
        days: Int,
        tdee: Double,
        checkIns: List<Int>,
        intake: (Int, Double) -> Double = { _, plan -> plan },
        glycogen: (Int) -> Double = { -1.0 },
        signal: (Int) -> FatBurnPlanner.MuscleSignal = { FatBurnPlanner.MuscleSignal.GREEN },
        phaseStart: (Int) -> Int? = { null },
        startPlan: Double = 2150.0,
        pace: FatBurnPlanner.Pace? = null
    ): List<Week> {
        val rnd = Random(7)
        var fat = 23.5
        val lean = 70.5
        var water = 0.0
        val weights = mutableListOf<DayValue>()
        val kcal = mutableListOf<DayValue>()
        var plan = startPlan
        var flat = 0
        var slow = 0
        var red = 0
        var sinceCut = 99
        var lastRate: Double? = null
        val out = mutableListOf<Week>()
        for (d in 0 until days) {
            val eat = intake(d, plan)
            fat += (eat - tdee) / 7700
            water += (glycogen(d) - water) * 0.35
            val w = lean + fat + water + rnd.nextGaussian() * 0.4
            weights += DayValue(d0.plusDays(d.toLong()), w)
            kcal += DayValue(d0.plusDays(d.toLong()), eat)
            if (d !in checkIns) continue
            val ps = phaseStart(d)
            val input = FatBurnPlanner.Input(
                male = true, heightCm = 196.0, weightKg = w, weights = weights.toList(), dailyCalories = kcal.toList(),
                fatPercent = 25.0, targetFatPercent = 13.0, activity = 1.5, previousCalories = plan,
                today = d0.plusDays(d.toLong()), pace = pace,
                phaseStart = ps?.let { d0.plusDays(it.toLong()) }, tdeeHint = tdee
            )
            val trend = Trend.smooth(weights)
            val ctx = FatBurnPlanner.CheckInContext(
                previousProtein = null, previousFat = null, previousCarbs = null,
                trendWeeklyPercent = Trend.weeklyLossPercent(trend), diaryDays = 7, diaryNeeded = 5, periodDays = 7,
                signal = signal(d), hungerHighTwice = false, previousFlatWeeks = flat,
                daysInPhase = ps?.let { d - it }, checkInsSinceCut = sinceCut,
                previousSlowWeeks = slow, previousRedWeeks = red, previousRatePercent = lastRate
            )
            val r = FatBurnPlanner.checkIn(input, ctx)
            flat = r.flatWeeks
            slow = r.slowWeeks
            red = r.redWeeks
            lastRate = r.ratePercent
            sinceCut = if (r.plan.calories < plan - 5) 1 else sinceCut + 1
            out += Week(d, plan, r.plan.calories, r.ratePercent, r.plan.note)
            plan = r.plan.calories
        }
        return out
    }

    @Test fun `красный сигнал не поднимает калории выше расхода минус 10 процентов`() {
        val tdee = 2750.0
        val weeks = simulate(84, tdee, (1..11).map { it * 7 }, signal = { FatBurnPlanner.MuscleSignal.RED })
        val ceiling = Math.round(tdee * 0.9 / 10) * 10.0
        assertTrue(weeks.all { it.after <= ceiling })
        // Третью неделю подряд красный — прибавки прекращаются, причина названа.
        assertEquals(weeks[1].after, weeks[2].after, 0.1)
        assertTrue(weeks[2].note.contains("прибавки калорий тут не помогут"))
    }

    @Test fun `темп вдвое ниже цели — план снижается, но не чаще раза в две недели`() {
        // Реальный расход 2400: план 2150 даёт ~0,3% в неделю при цели 0,6%.
        val weeks = simulate(98, 2400.0, (3..13).map { it * 7 })
        val cuts = weeks.filter { it.after < it.before - 5 }
        assertTrue("должен снизить хотя бы раз", cuts.isNotEmpty())
        // Между снижениями — минимум 2 чек-ина.
        val idx = weeks.indices.filter { weeks[it].after < weeks[it].before - 5 }
        idx.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 2) }
        // План сходится к цели: снижений больше одного, итог ≈ расход − 0,5% веса в неделю.
        assertTrue(cuts.size >= 2)
        assertTrue(weeks.last().after <= 2000.0)
    }

    @Test fun `начало блока после перерыва не принимается за слишком быстрый темп`() {
        // 0..41 блок, 42..55 перерыв на расходе (вода набирается), с 56 — новый блок (вода уходит).
        val weeks = simulate(
            98, 2750.0, listOf(62, 69, 76, 83, 90, 97),
            intake = { d, plan -> if (d in 42..55) 2750.0 else plan },
            glycogen = { d -> if (d in 42..55) 0.0 else -1.0 },
            phaseStart = { d -> if (d >= 56) 56 else 42 }
        )
        // Первые две недели блока — без правок, дальше темп реальный (~0,6%), не «быстрее 1%».
        assertEquals(2150.0, weeks[0].after, 0.1)
        assertEquals(2150.0, weeks[1].after, 0.1)
        assertTrue(weeks[0].note.contains("Начало этапа"))
        assertFalse(weeks.any { it.note.contains("быстрее 1%") })
    }

    @Test fun `обычный ход в норме — без лишних правок`() {
        val weeks = simulate(84, 2750.0, (3..11).map { it * 7 })
        assertTrue(weeks.count { it.after != it.before } <= 1)
    }

    @Test fun `темп по выбору и по умолчанию для пола`() {
        assertEquals(0.006, FatBurnPlanner.weeklyRate(25.0, 13.0, male = true), 1e-9)
        assertEquals(0.004, FatBurnPlanner.weeklyRate(25.0, 20.0, male = false), 1e-9)
        assertEquals(0.008, FatBurnPlanner.weeklyRate(25.0, 13.0, true, FatBurnPlanner.Pace.FAST), 1e-9)
        // У цели — на треть медленнее, но не ниже 0,3%.
        assertEquals(0.004, FatBurnPlanner.weeklyRate(15.0, 13.0, true), 1e-9)
        assertEquals(0.003, FatBurnPlanner.weeklyRate(21.0, 20.0, false), 1e-9)
    }

    @Test fun `активность по шагам`() {
        assertNull(FatBurnPlanner.activityFromSteps(listOf(9000, 9000, 0)))
        assertEquals(1.55, FatBurnPlanner.activityFromSteps(List(10) { 9000 })!!, 1e-9)
        assertEquals(1.35, FatBurnPlanner.activityFromSteps(List(10) { 3000 })!!, 1e-9)
    }

    @Test fun `редкие замеры сглаживаются по числу, а не по дням`() {
        val pts = listOf(
            DayValue(d0, 25.0), DayValue(d0.plusDays(7), 24.0), DayValue(d0.plusDays(14), 20.0)
        )
        // По дням третий замер весил бы ~87%, по числу — половину.
        val t = Trend.smoothByCount(pts)
        assertEquals(22.25, t.last().trend, 1e-9)
        assertTrue(Trend.smooth(pts).last().trend < 21.0)
    }
}
