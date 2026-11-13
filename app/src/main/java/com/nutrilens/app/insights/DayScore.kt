package com.nutrilens.app.insights

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Оценка дня режима «Сжигание жира» (3.23): 0–100 из четырёх частей —
 * калории (35), белок (30), движение (20) и сон (15). «Хороший день» — от 70.
 * Чистый Kotlin без Android: все пороги здесь, чтобы их было легко подкрутить.
 *
 * Шаги в оценке — только цель поведения: калорий к бюджету они не прибавляют,
 * расход и так виден в недельном пересчёте по весу.
 */
object DayScore {
    const val GOOD = 70
    const val MEDIUM = 40

    const val MAX_CALORIES = 35.0
    const val MAX_PROTEIN = 30.0
    const val MAX_MOVEMENT = 20.0
    const val MAX_SLEEP = 15.0

    const val DEFAULT_STEP_GOAL = 8000
    const val MIN_STEP_GOAL = 4000
    const val MAX_STEP_GOAL = 15000
    const val STEP_GOAL_STEP = 500

    // Коридор калорий: полный балл в −10%…+5% плана, ноль на −30% и на +25%.
    // Недоедать на 20%+ так же плохо, как переедать: в большом дефиците уходят мышцы.
    private const val FULL_LOW = 0.90
    private const val FULL_HIGH = 1.05
    private const val ZERO_LOW = 0.70
    private const val ZERO_HIGH = 1.25

    /** Белок «выполнен» с 90% нормы. */
    private const val PROTEIN_FULL = 0.9

    /** Данные одного дня для оценки. */
    data class Day(
        val date: LocalDate,
        val kcal: Double,
        val protein: Double,
        val workout: Boolean,
        /** Есть запись активности дня (день из 3.23 и новее). */
        val hasRecord: Boolean,
        val steps: Int?,
        val sleptWell: Boolean?
    )

    enum class Level { GOOD, MEDIUM, LOW, NONE }

    enum class PartState { DONE, PARTIAL, NONE }

    data class Result(
        val calories: Double,
        val protein: Double,
        val movement: Double,
        val sleep: Double
    ) {
        val total: Int get() = (calories + protein + movement + sleep).roundToInt().coerceIn(0, 100)
        // Хороший день — не только сумма: калории должны быть хотя бы в −15%…+10% от плана (26 из 35),
        // иначе переедание или сильный недобор «закрывались» бы сном и шагами.
        val good: Boolean get() = total >= GOOD && calories >= 26.0
    }

    fun caloriesPoints(kcal: Double, plan: Double): Double {
        if (kcal <= 0.0 || plan <= 0.0) return 0.0 // еды нет — и оценивать нечего
        val r = kcal / plan
        return when {
            r in FULL_LOW..FULL_HIGH -> MAX_CALORIES
            r < FULL_LOW -> (MAX_CALORIES * (r - ZERO_LOW) / (FULL_LOW - ZERO_LOW)).coerceIn(0.0, MAX_CALORIES)
            else -> (MAX_CALORIES * (ZERO_HIGH - r) / (ZERO_HIGH - FULL_HIGH)).coerceIn(0.0, MAX_CALORIES)
        }
    }

    fun proteinPoints(protein: Double, target: Double): Double {
        if (target <= 0.0 || protein <= 0.0) return 0.0
        val need = target * PROTEIN_FULL
        return if (protein >= need) MAX_PROTEIN else MAX_PROTEIN * protein / need
    }

    /** Силовая или шаги от цели — полный балл; иначе доля шагов. */
    fun movementPoints(workout: Boolean, steps: Int?, stepGoal: Int): Double {
        if (workout) return MAX_MOVEMENT
        if (steps == null || stepGoal <= 0) return 0.0
        return if (steps >= stepGoal) MAX_MOVEMENT else MAX_MOVEMENT * steps.toDouble() / stepGoal
    }

    fun sleepPoints(sleptWell: Boolean?): Double = if (sleptWell == true) MAX_SLEEP else 0.0

    fun compute(day: Day, planCalories: Double, planProtein: Double, stepGoal: Int): Result = Result(
        calories = caloriesPoints(day.kcal, planCalories),
        protein = proteinPoints(day.protein, planProtein),
        movement = movementPoints(day.workout, day.steps, stepGoal),
        sleep = sleepPoints(day.sleptWell)
    )

    /** Правило «хорошего дня» из 3.22: калории в коридоре и белок от 90%. */
    fun legacyGood(kcal: Double, protein: Double, planCalories: Double, planProtein: Double): Boolean =
        planCalories > 0 && kcal >= planCalories * FULL_LOW && kcal <= planCalories * FULL_HIGH &&
            planProtein > 0 && protein >= planProtein * PROTEIN_FULL

    /**
     * Хороший ли день для итогов недели. Дни до 3.23 (записи активности нет) считаются
     * по старому правилу — чтобы история задним числом не стала «плохой».
     */
    fun isGood(day: Day, planCalories: Double, planProtein: Double, stepGoal: Int): Boolean =
        if (day.hasRecord) compute(day, planCalories, planProtein, stepGoal).good
        else legacyGood(day.kcal, day.protein, planCalories, planProtein)

    /** Есть ли у дня хоть какие-то данные для оценки. */
    fun hasData(day: Day): Boolean =
        day.kcal > 0.0 || day.workout || day.steps != null || day.sleptWell != null

    fun level(score: Int): Level = when {
        score >= GOOD -> Level.GOOD
        score >= MEDIUM -> Level.MEDIUM
        else -> Level.LOW
    }

    /**
     * Цвет дня в полосе недели и в Прогрессе. Нет данных — нейтральный.
     * День до 3.23, хороший по старому правилу, остаётся хорошим.
     */
    fun dayLevel(day: Day, planCalories: Double, planProtein: Double, stepGoal: Int): Level {
        if (!hasData(day)) return Level.NONE
        if (!day.hasRecord && legacyGood(day.kcal, day.protein, planCalories, planProtein)) return Level.GOOD
        val r = compute(day, planCalories, planProtein, stepGoal)
        val lv = level(r.total)
        return if (lv == Level.GOOD && !r.good) Level.MEDIUM else lv
    }

    fun partState(points: Double, max: Double): PartState = when {
        points >= max - 0.01 -> PartState.DONE
        points > 0.0 -> PartState.PARTIAL
        else -> PartState.NONE
    }

    /** Цель шагов в допустимых пределах и с шагом 500. */
    fun clampStepGoal(goal: Int): Int {
        val c = goal.coerceIn(MIN_STEP_GOAL, MAX_STEP_GOAL)
        return (c / STEP_GOAL_STEP) * STEP_GOAL_STEP
    }
}
