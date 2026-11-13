package com.nutrilens.app.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Недельный чек-ин режима «Сжигание жира»: когда он «пора», итоги недели и
 * сигнал сохранения мышц. Чистый Kotlin без Android — решение по калориям
 * принимает FatBurnPlanner.checkIn.
 */
object CheckInPlanner {
    /** Не чаще раза в 5 дней: иначе вес и дневник не успевают ничего показать. */
    const val MIN_GAP_DAYS = 5L

    /** Пропустил — чек-ин покрывает всё время с прошлого, но не больше трёх недель. */
    const val MAX_PERIOD_DAYS = 21

    const val DEFAULT_WEEKDAY = 7 // воскресенье, ISO-номер
    const val DEFAULT_WORKOUT_GOAL = 2
    const val MIN_WORKOUT_GOAL = 2
    const val MAX_WORKOUT_GOAL = 5

    /**
     * Пора ли чек-ин: с дня чек-ина и до тех пор, пока не сделан. [reference] —
     * дата прошлого чек-ина (или включения режима). Назначенный день считается
     * «закрытым», если прошлый чек-ин был меньше чем за 5 дней до него.
     */
    fun isDue(today: LocalDate, weekday: Int, reference: LocalDate?): Boolean {
        val scheduled = today.with(TemporalAdjusters.previousOrSame(dayOfWeek(weekday)))
        if (reference == null) return true
        if (reference.isAfter(today)) return false // сменили часы — не дёргаем
        return ChronoUnit.DAYS.between(reference, scheduled) >= MIN_GAP_DAYS
    }

    /** Дата ближайшего чек-ина (сегодня, если уже пора). */
    fun nextDate(today: LocalDate, weekday: Int, reference: LocalDate?): LocalDate {
        if (isDue(today, weekday, reference)) return today
        var d = today.plusDays(1)
        repeat(28) {
            if (isDue(d, weekday, reference)) return d
            d = d.plusDays(1)
        }
        return today.with(TemporalAdjusters.next(dayOfWeek(weekday)))
    }

    /** Сколько дней покрывает чек-ин: с прошлого, от 7 до 21. */
    fun periodDays(today: LocalDate, reference: LocalDate?): Int {
        if (reference == null) return 7
        val days = ChronoUnit.DAYS.between(reference, today).toInt()
        return days.coerceIn(7, MAX_PERIOD_DAYS)
    }

    fun dayOfWeek(weekday: Int): DayOfWeek = DayOfWeek.of(weekday.coerceIn(1, 7))

    /** «5 из 7» в масштабе периода: 14 дней → 10. */
    fun scaled(perWeek: Int, periodDays: Int): Int = ceil(perWeek * periodDays / 7.0).toInt()

    /**
     * День дневника для итогов: калории, белок, отметка силовой и (с 3.23) активность —
     * есть ли запись дня, шаги и «поспал 8 часов». Без записи день считается по правилу 3.22.
     */
    data class Day(
        val date: LocalDate,
        val kcal: Double,
        val protein: Double,
        val workout: Boolean,
        val hasActivity: Boolean = false,
        val steps: Int? = null,
        val sleptWell: Boolean? = null
    ) {
        fun toScoreDay(): DayScore.Day = DayScore.Day(date, kcal, protein, workout, hasActivity, steps, sleptWell)
    }

    data class Review(
        val periodDays: Int,
        /** Дней с записями ≥ 800 ккал. */
        val diaryDays: Int,
        val diaryNeeded: Int,
        /** Оценка дня ≥ 70 (дни до 3.23 — калории в −10%…+5% и белок ≥ 90%). */
        val goodDays: Int,
        /** Дней с белком ≥ 90% нормы. */
        val proteinDays: Int,
        val proteinNeeded: Int,
        val workouts: Int,
        val workoutGoal: Int,
        val signal: FatBurnPlanner.MuscleSignal,
        val signalNote: String,
        val action: String,
        /** Средняя оценка дня за период по дням с едой и записью активности; null — таких нет. */
        val avgScore: Double? = null
    ) {
        val diaryComplete: Boolean get() = diaryDays >= diaryNeeded
    }

    /**
     * Итоги периода. [days] — дни периода (без сегодняшнего: он ещё не закончен),
     * [weeklyLossPercent] — темп по тренду (плюс — худеешь).
     */
    fun review(
        days: List<Day>,
        periodDays: Int,
        planCalories: Double,
        planProtein: Double,
        workoutGoalPerWeek: Int,
        weeklyLossPercent: Double?,
        stepGoal: Int = DayScore.DEFAULT_STEP_GOAL
    ): Review {
        val logged = days.filter { it.kcal >= FatBurnPlanner.MIN_DAY_KCAL }
        val proteinOk = { d: Day -> planProtein > 0 && d.protein >= planProtein * 0.9 }
        // Хороший день — по оценке дня; дни без записи активности (до 3.23) — по старому правилу.
        val good = logged.count { d -> DayScore.isGood(d.toScoreDay(), planCalories, planProtein, stepGoal) }
        val scored = logged.filter { it.hasActivity }
            .map { DayScore.compute(it.toScoreDay(), planCalories, planProtein, stepGoal).total }
        val avgScore = if (scored.isEmpty()) null else scored.average()
        val proteinDays = logged.count(proteinOk)
        val workouts = days.count { it.workout }
        val workoutGoal = scaled(workoutGoalPerWeek, periodDays)
        val diaryNeeded = scaled(5, periodDays)
        val proteinNeeded = scaled(5, periodDays)

        val workoutsOk = workouts >= workoutGoal
        val proteinGood = proteinDays >= proteinNeeded
        val paceOk = weeklyLossPercent == null || weeklyLossPercent <= 1.0
        val fails = listOf(workoutsOk, proteinGood, paceOk).count { !it }
        val signal = when {
            fails == 0 -> FatBurnPlanner.MuscleSignal.GREEN
            fails == 1 -> FatBurnPlanner.MuscleSignal.YELLOW
            else -> FatBurnPlanner.MuscleSignal.RED
        }
        // Одна простая фраза — что поправить в первую очередь.
        val signalNote = when {
            !workoutsOk -> "Чтобы тело держалось за мышцы, нужно $workoutGoal ${plural(workoutGoal, "силовая", "силовые", "силовых")} за период — добавь ещё ${workoutGoal - workouts}"
            !proteinGood -> "Добирай белок: хотя бы $proteinNeeded ${plural(proteinNeeded, "день", "дня", "дней")} с 90% нормы и выше"
            !paceOk -> "Вес уходит слишком быстро — так уходят и мышцы, план добавит калорий"
            else -> "Мышцы под защитой: силовые, белок и спокойный темп"
        }
        val diaryComplete = logged.size >= diaryNeeded
        val action = when {
            !diaryComplete -> "Записывай еду каждый день, даже перекусы, — тогда план станет точнее"
            !proteinGood -> "Белок в каждый приём пищи: творог, яйца, курица, рыба — до ${planProtein.roundToInt()} г в день"
            !workoutsOk -> "Запланируй $workoutGoalPerWeek ${plural(workoutGoalPerWeek, "силовую", "силовые", "силовых")} на неделю и отмечай их галочкой «Тренировка»"
            !paceOk -> "Не урезай еду сверх плана — темп и так быстрый, ешь свою норму"
            else -> "Продолжай в том же духе — план работает"
        }
        return Review(
            periodDays = periodDays,
            diaryDays = logged.size,
            diaryNeeded = diaryNeeded,
            goodDays = good,
            proteinDays = proteinDays,
            proteinNeeded = proteinNeeded,
            workouts = workouts,
            workoutGoal = workoutGoal,
            signal = signal,
            signalNote = signalNote,
            action = action,
            avgScore = avgScore
        )
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && (m100 < 12 || m100 > 14) -> few
            else -> many
        }
    }
}
