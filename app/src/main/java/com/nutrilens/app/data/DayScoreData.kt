package com.nutrilens.app.data

import com.nutrilens.app.insights.DayScore
import com.nutrilens.app.insights.effectiveDailyGoal
import com.nutrilens.app.insights.effectiveMacroGoals
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/*
 * Связка DayScore с БД: дни (еда, силовая, шаги, сон) и план, против которого
 * считается оценка. Сама формула — в insights/DayScore.
 */

/** План для оценки дня: калории и белок (эффективные, как везде) и цель шагов. */
data class ScorePlan(val calories: Double, val protein: Double, val stepGoal: Int)

suspend fun NutriLensDatabase.scorePlan(settings: SettingsEntity): ScorePlan {
    val weight = weightDao().latest()?.weight
    val fat = latestFatPercent()
    return ScorePlan(
        calories = settings.effectiveDailyGoal,
        protein = effectiveMacroGoals(settings, weight, fat).protein,
        stepGoal = DayScore.clampStepGoal(settings.stepGoal)
    )
}

/** Дни с [start] по [end] включительно — по одному на дату, даже пустые. */
suspend fun NutriLensDatabase.scoreDays(start: LocalDate, end: LocalDate): List<DayScore.Day> {
    val count = ChronoUnit.DAYS.between(start, end).toInt()
    if (count < 0) return emptyList()
    val s = start.toString()
    val e = end.toString()
    val meals = mealDao().mealsBetween(s, e).groupBy { it.date }
    val workouts = workoutDao().between(s, e).filter { it.done }.map { it.date }.toSet()
    val activity = dayActivityDao().between(s, e).associateBy { it.date }
    return (0..count).map { i ->
        val date = start.plusDays(i.toLong())
        val key = date.toString()
        val list = meals[key].orEmpty()
        val act = activity[key]
        DayScore.Day(
            date = date,
            kcal = list.sumOf { it.calories },
            protein = list.sumOf { it.protein },
            workout = key in workouts,
            hasRecord = act != null,
            steps = act?.steps,
            sleptWell = act?.sleptWell
        )
    }
}

/**
 * Оценка дня для ИИ-коуча: сегодняшние части и средняя за 7 прошлых дней.
 * Пусто — режим «Сжигание жира» выключен.
 */
suspend fun NutriLensDatabase.dayScoreCoachContext(settings: SettingsEntity): String {
    if (settings.targetFatPercent == null) return ""
    val plan = scorePlan(settings)
    val today = LocalDate.now()
    val days = scoreDays(today.minusDays(7), today)
    val todayDay = days.lastOrNull() ?: return ""
    val r = DayScore.compute(todayDay, plan.calories, plan.protein, plan.stepGoal)
    val past = days.dropLast(1).filter { it.hasRecord && it.kcal > 0 }
        .map { DayScore.compute(it, plan.calories, plan.protein, plan.stepGoal).total }
    return buildString {
        append(" Оценка дня (0–100, хороший день от 70; калории 35, белок 30, движение 20, сон 15): сегодня пока ${r.total}")
        append(" — калории ${r.calories.roundToInt()}, белок ${r.protein.roundToInt()}, движение ${r.movement.roundToInt()}, сон ${r.sleep.roundToInt()}.")
        todayDay.steps?.let { append(" Шагов сегодня $it при цели ${plan.stepGoal}.") }
        if (past.isNotEmpty()) append(" Средняя оценка за прошлые дни недели ${past.average().roundToInt()}.")
        append(" Шаги не добавляют калорий к плану.")
    }
}
