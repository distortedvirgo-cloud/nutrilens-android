package com.nutrilens.app.training

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Индекс силы: теряются ли мышцы на дефиците. Чистый Kotlin без Android.
 *
 * Идея — универсально для любого человека и любой программы:
 *  - в расчёт идут ВСЕ упражнения, которые человек делал хотя бы 2 раза за 6 недель;
 *  - каждое сравнивается только с самим собой (недавний лучший результат против
 *    лучшего в блоке), поэтому неважно, жим это на 5 повторов или икры на 30 —
 *    погрешность формулы у одного упражнения одинаковая и сокращается;
 *  - итог — взвешенная медиана изменений, вес упражнения — число рабочих подходов:
 *    то, что человек делает часто, само становится главным;
 *  - первые 4 недели после перерыва в зале и неделя разгрузки не оцениваются.
 */
object StrengthAnalyzer {
    /** «Недавно» — последние 2 недели: одна плохая тренировка не поднимает тревогу. */
    const val RECENT_DAYS = 14L

    /** Окно, в котором упражнение должно встречаться регулярно, и в котором считаются подходы. */
    const val LOOKBACK_DAYS = 42L

    /** База сравнения, если начало блока неизвестно, — 8 недель назад. */
    const val DEFAULT_BASELINE_DAYS = 56L

    /** База всегда захватывает минимум 4 недели до «недавно», даже если блок только начался. */
    const val MIN_BASELINE_DAYS = 28L

    const val MIN_SESSIONS = 2
    const val MIN_EXERCISES = 3

    /** Подходы длиннее — уже выносливость, формула максимума там бессмысленна. */
    const val MAX_REPS = 30

    /** Перерыв в зале, после которого сила «возвращается» и не оценивается. */
    const val REENTRY_GAP_DAYS = 21L
    const val REENTRY_DAYS = 28L

    /** Пороги индекса, %. */
    const val STABLE_PERCENT = -3.0
    const val YELLOW_PERCENT = -5.0
    const val RED_PERCENT = -10.0

    /** Перерыв между тренировками одного упражнения, после которого оно «нерегулярное». */
    const val IRREGULAR_GAP_DAYS = 14

    enum class Kind { WEIGHTED, ASSISTED, REPS, DURATION, CARDIO }

    enum class Signal { NONE, GREEN, YELLOW, RED }

    /** Ручная настройка — вся необязательная. Ключи — названия упражнений из файла. */
    data class Settings(
        /** Другое название → основное: «Махи в стороны (Гантели)» → «Боковые подъемы (Гантель)». */
        val merges: Map<String, String> = emptyMap(),
        val excluded: Set<String> = emptySet(),
        /** Что показывать в карточке; пусто — самые частые. */
        val pinned: List<String> = emptyList(),
        val kindOverrides: Map<String, Kind> = emptyMap()
    )

    /** Лучший результат упражнения за день. [value] — в единицах [Kind]: кг максимума, повторы или секунды. */
    data class Session(val date: LocalDate, val value: Double, val bestSet: String)

    data class ExerciseTrend(
        val name: String,
        val kind: Kind,
        val sessions: List<Session>,
        /** Рабочих подходов за 6 недель — вес упражнения в индексе. */
        val workingSets: Int,
        val baseline: Session?,
        val recent: Session?,
        /** Изменение недавнего лучшего к лучшему в блоке, %; null — не хватает данных. */
        val changePercent: Double?,
        /** 3 последние тренировки без прогресса. */
        val stalled: Boolean,
        /** Были перерывы больше 2 недель между тренировками этого упражнения. */
        val irregular: Boolean
    ) {
        val counted: Boolean get() = changePercent != null
    }

    data class Report(
        val today: LocalDate,
        /** Все силовые упражнения за 6 недель, самые частые сверху. */
        val exercises: List<ExerciseTrend>,
        /** Что показать в карточке: закреплённые или 4 самых частых. */
        val shown: List<ExerciseTrend>,
        /** Взвешенная медиана изменений, %; null — оценки нет (см. [signal] и [note]). */
        val indexPercent: Double?,
        val countedExercises: Int,
        val signal: Signal,
        /** Сколько чек-инов подряд индекс ≤ −5% (с этим). */
        val downStreak: Int,
        /** До какой даты идёт «возвращение» после перерыва; null — перерыва не было. */
        val reEntryUntil: LocalDate?,
        val note: String
    )

    private val ASSISTED_MARKERS = listOf(
        "с помощью", "гравитрон", "assisted", "graviton", "asistid", "assist", "unterstütz"
    )

    fun canonical(name: String, settings: Settings): String = settings.merges[name] ?: name

    /** Тип по данным: противовес — по названию на нескольких языках, остальное — по заполненным колонкам. */
    fun detectKind(name: String, rows: List<WorkoutCsv.SetRow>): Kind {
        val working = rows.filter { it.type.working }
        val lower = name.lowercase(Locale.ROOT)
        val hasWeight = working.any { (it.weightKg ?: 0.0) > 0 && (it.reps ?: 0) > 0 }
        val hasReps = working.any { (it.reps ?: 0) > 0 }
        val hasDuration = working.any { (it.durationSec ?: 0) > 0 }
        val hasDistance = working.any { (it.distanceKm ?: 0.0) > 0 }
        return when {
            ASSISTED_MARKERS.any { it in lower } && hasWeight -> Kind.ASSISTED
            hasWeight -> Kind.WEIGHTED
            hasReps -> Kind.REPS
            hasDistance -> Kind.CARDIO
            hasDuration -> Kind.DURATION
            else -> Kind.CARDIO
        }
    }

    /** Примерный максимум на 1 повтор (Эпли). */
    fun estimatedMax(weightKg: Double, reps: Int): Double = weightKg * (1 + reps / 30.0)

    fun analyze(
        rows: List<WorkoutCsv.SetRow>,
        today: LocalDate,
        /** Начало текущего блока дефицита; null — сравниваем с последними 8 неделями. */
        blockStart: LocalDate?,
        /** Вес тела на дату — для упражнений с противовесом. */
        bodyweightAt: (LocalDate) -> Double?,
        settings: Settings = Settings(),
        /** Индексы прошлых чек-инов, от последнего к раннему (null — оценки не было). */
        previousIndices: List<Double?> = emptyList(),
        /** Неделя разгрузки: веса специально ниже — не оцениваем. */
        deload: Boolean = false
    ): Report {
        val relevant = rows.filter { !it.date.isAfter(today) }
            .map { it to canonical(it.exercise, settings) }
            .filter { it.second !in settings.excluded }

        // Возвращение после перерыва: ищем последний разрыв ≥ 3 недель между тренировками.
        val dates = relevant.map { it.first.date }.distinct().sorted()
        var reEntryStart: LocalDate? = null
        for (i in 1 until dates.size) {
            if (ChronoUnit.DAYS.between(dates[i - 1], dates[i]) >= REENTRY_GAP_DAYS) reEntryStart = dates[i]
        }
        val reEntryUntil = reEntryStart?.plusDays(REENTRY_DAYS)?.takeIf { it.isAfter(today) }

        val recentStart = today.minusDays(RECENT_DAYS - 1)
        val lookbackStart = today.minusDays(LOOKBACK_DAYS - 1)
        var baselineStart = minOf(
            blockStart ?: today.minusDays(DEFAULT_BASELINE_DAYS),
            recentStart.minusDays(MIN_BASELINE_DAYS)
        )
        // Результаты до перерыва — другая форма, с ними не сравниваем.
        if (reEntryStart != null && reEntryStart.isAfter(baselineStart)) baselineStart = reEntryStart

        val trends = relevant.groupBy({ it.second }, { it.first }).mapNotNull { (name, exRows) ->
            val kind = settings.kindOverrides[name] ?: detectKind(name, exRows)
            if (kind == Kind.CARDIO) return@mapNotNull null
            val sessions = exRows.filter { it.type.working }
                .mapNotNull { r -> setValue(r, kind, bodyweightAt)?.let { r.date to it } }
                .groupBy({ it.first }, { it.second })
                .map { (date, vals) -> vals.maxBy { it.first }.let { Session(date, it.first, it.second) } }
                .sortedBy { it.date }
            if (sessions.isEmpty()) return@mapNotNull null
            val workingSets = exRows.count { it.type.working && !it.date.isBefore(lookbackStart) }
            val inLookback = sessions.count { !it.date.isBefore(lookbackStart) }
            val baseline = sessions.filter { !it.date.isBefore(baselineStart) && it.date.isBefore(recentStart) }
                .maxByOrNull { it.value }
            val recent = sessions.filter { !it.date.isBefore(recentStart) }.maxByOrNull { it.value }
            val change = if (inLookback >= MIN_SESSIONS && baseline != null && recent != null && baseline.value > 0) {
                (recent.value / baseline.value - 1) * 100
            } else null
            val lastFour = sessions.takeLast(4)
            val stalled = lastFour.size == 4 && lastFour.drop(1).maxOf { it.value } <= lastFour.first().value * 1.005
            val window = sessions.filter { !it.date.isBefore(lookbackStart) }
            val irregular = window.zipWithNext().any { (a, b) -> ChronoUnit.DAYS.between(a.date, b.date) > IRREGULAR_GAP_DAYS }
            ExerciseTrend(name, kind, sessions, workingSets, baseline, recent, change, stalled, irregular)
        }.filter { it.workingSets > 0 }
            .sortedWith(compareByDescending<ExerciseTrend> { it.workingSets }.thenBy { it.name })

        val shown = settings.pinned.mapNotNull { p -> trends.firstOrNull { it.name == canonical(p, settings) } }
            .ifEmpty { trends.take(4) }

        val counted = trends.filter { it.counted }
        val lastWorkout = dates.lastOrNull()

        fun report(index: Double?, signal: Signal, streak: Int, note: String) = Report(
            today, trends, shown, index, counted.size, signal, streak, reEntryUntil, note
        )

        when {
            lastWorkout == null ->
                return report(null, Signal.NONE, 0, "Пока нет силовых тренировок — загрузи экспорт из приложения для тренировок")
            ChronoUnit.DAYS.between(lastWorkout, today) >= RECENT_DAYS ->
                return report(null, Signal.NONE, 0, "Силовых не было 2 недели — после возвращения 4 недели сила не оценивается")
            reEntryUntil != null ->
                return report(null, Signal.NONE, 0, "Возвращение после перерыва в зале — сила восстанавливается, оценка с ${formatDate(reEntryUntil)}")
            deload ->
                return report(null, Signal.NONE, 0, "Неделя разгрузки — веса специально ниже, силу не оцениваю")
            counted.size < MIN_EXERCISES ->
                return report(null, Signal.NONE, 0, "Мало данных: нужно хотя бы $MIN_EXERCISES упражнения, которые повторялись за последние недели")
        }

        val index = weightedMedian(counted.map { it.changePercent!! to it.workingSets.toDouble() })
        // Подряд «вниз» — этот чек-ин плюс прошлые, пока индекс был ≤ −5%.
        val streak = if (index <= YELLOW_PERCENT) {
            1 + previousIndices.takeWhile { it != null && it <= YELLOW_PERCENT }.size
        } else 0
        val signal = when {
            index <= RED_PERCENT -> Signal.RED
            streak >= 4 -> Signal.RED
            streak >= 2 -> Signal.YELLOW
            else -> Signal.GREEN
        }
        val note = when {
            signal == Signal.RED -> "Сила заметно падает (${formatPercent(index)}) — мышцам не хватает ресурса: стоит замедлить темп или начать перерыв раньше"
            signal == Signal.YELLOW -> "Сила проседает вторую неделю (${formatPercent(index)}) — проверь сон и белок, тяжёлые подходы не убирай"
            index <= YELLOW_PERCENT -> "Небольшой спад (${formatPercent(index)}) — одна неделя ничего не значит, смотрим дальше"
            index < STABLE_PERCENT -> "Сила почти держится (${formatPercent(index)})"
            index <= 3.0 -> "Сила держится — мышцы на месте"
            else -> "Сила растёт (${formatPercent(index)}) — дефицит не мешает"
        }
        return report(index, signal, streak, note)
    }

    /** Значение подхода и подпись лучшего подхода; null — подход не годится для оценки. */
    private fun setValue(
        r: WorkoutCsv.SetRow,
        kind: Kind,
        bodyweightAt: (LocalDate) -> Double?
    ): Pair<Double, String>? {
        val reps = r.reps ?: 0
        return when (kind) {
            Kind.WEIGHTED -> {
                val w = r.weightKg ?: return null
                if (w <= 0 || reps !in 1..MAX_REPS) return null
                estimatedMax(w, reps) to "${formatKg(w)} × $reps"
            }
            Kind.ASSISTED -> {
                val assist = r.weightKg ?: 0.0
                val bw = bodyweightAt(r.date) ?: return null
                val load = bw - assist
                if (load <= 0 || reps !in 1..MAX_REPS) return null
                estimatedMax(load, reps) to "−${formatKg(assist)} × $reps"
            }
            Kind.REPS -> if (reps > 0) reps.toDouble() to "$reps повт." else null
            Kind.DURATION -> (r.durationSec ?: 0).takeIf { it > 0 }?.let { it.toDouble() to "$it с" }
            Kind.CARDIO -> null
        }
    }

    /** Взвешенная медиана: значение, на котором набирается половина общего веса. */
    fun weightedMedian(values: List<Pair<Double, Double>>): Double {
        require(values.isNotEmpty())
        val sorted = values.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var acc = 0.0
        for ((v, w) in sorted) {
            acc += w
            if (acc >= half) return v
        }
        return sorted.last().first
    }

    fun formatKg(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v).replace('.', ',')

    fun formatPercent(v: Double): String =
        (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(Locale.ROOT, "%.1f", kotlin.math.abs(v)).replace('.', ',') + "%"

    private fun formatDate(d: LocalDate): String = "%02d.%02d".format(d.dayOfMonth, d.monthValue)
}
