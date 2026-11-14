package com.nutrilens.app.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * «Как идёт прогресс» (3.30): один вердикт и шесть понятных строк — темп, талия, сила,
 * дневник, белок, силовые. Чистый Kotlin без Android: данные приходят готовыми.
 *
 * Логика: результат решают темп, талия и сила (то, что тело показывает), привычки —
 * дневник, белок и силовые — объясняют, почему. Мало данных — честно «пока рано».
 */
object ProgressVerdict {
    enum class Level { GOOD, WATCH, BAD, UNKNOWN }

    enum class Kind { PACE, WAIST, STRENGTH, DIARY, PROTEIN, WORKOUTS }

    data class Item(val kind: Kind, val title: String, val level: Level, val value: String, val hint: String)

    data class Result(
        val level: Level,
        val headline: String,
        /** Что сделать в первую очередь; null — всё хорошо. */
        val focus: String?,
        val items: List<Item>,
        /** «С начала программы: −3,2 кг, талия −4 см, 6 недель»; null — программа только началась. */
        val sinceStart: String?
    )

    /** Чек-ин в нужном здесь виде (из CheckInEntity). */
    data class Week(
        val date: LocalDate,
        /** Темп, % веса в неделю (плюс — худеешь); null — не оценивали. */
        val rate: Double?,
        val diaryDays: Int,
        val proteinDays: Int,
        val periodDays: Int,
        val workouts: Int,
        val workoutGoal: Int,
        val strengthSignal: String?,
        val strengthIndex: Double?
    )

    data class Input(
        val today: LocalDate,
        /** Целевой темп, % веса в неделю. */
        val targetRate: Double,
        /** Чек-ины, новые первыми. */
        val weeks: List<Week>,
        /** Обхваты талии по датам (по возрастанию или нет — неважно). */
        val waist: List<Pair<LocalDate, Double>>,
        val startDate: LocalDate?,
        val startWeight: Double?,
        /** Вес по тренду сейчас. */
        val weightNow: Double?,
        /** Перерыв или удержание: вес и должен стоять. */
        val atMaintenance: Boolean
    )

    /** Сравниваем талию с замером не ближе чем 3 недели назад — на меньшем отрезке лента тонет в погрешности. */
    const val WAIST_SPAN_DAYS = 21L

    fun evaluate(input: Input): Result {
        val recent = input.weeks.take(2)
        val items = listOf(
            pace(input, recent),
            waist(input),
            strength(input.weeks.firstOrNull()),
            share(Kind.DIARY, "Дневник", recent.sumOf { it.diaryDays }, recent.sumOf { it.periodDays },
                good = 5.0 / 7, watch = 4.0 / 7, unit = "дней с едой",
                hintGood = "Данных хватает — план видит, как ты ешь",
                hintBad = "Записывай всё, даже примерно: по неполному дневнику план не подстраивается"),
            share(Kind.PROTEIN, "Белок", recent.sumOf { it.proteinDays }, recent.sumOf { it.periodDays },
                good = 5.0 / 7, watch = 3.0 / 7, unit = "дней с нормой",
                hintGood = "Белок держит мышцы на дефиците",
                hintBad = "Белок в каждый приём: творог, яйца, мясо, рыба — иначе мышцам нечем держаться"),
            workouts(recent)
        )
        val body = items.filter { it.kind == Kind.PACE || it.kind == Kind.WAIST || it.kind == Kind.STRENGTH }
        val habits = items - body.toSet()
        val known = items.filter { it.level != Level.UNKNOWN }
        val level = when {
            known.size < 2 || input.weeks.isEmpty() -> Level.UNKNOWN
            body.any { it.level == Level.BAD } -> Level.BAD
            items.count { it.level == Level.WATCH } + habits.count { it.level == Level.BAD } >= 2 -> Level.WATCH
            habits.any { it.level == Level.BAD } || body.any { it.level == Level.WATCH } -> Level.WATCH
            else -> Level.GOOD
        }
        // Главное — самый важный провал: тело важнее привычек, «плохо» важнее «внимание».
        val worst = (body + habits).firstOrNull { it.level == Level.BAD } ?: (body + habits).firstOrNull { it.level == Level.WATCH }
        val headline = when (level) {
            Level.GOOD -> if (input.atMaintenance) "Всё идёт хорошо: держим результат" else "Всё идёт хорошо: уходит жир, мышцы на месте"
            Level.WATCH -> "В целом нормально, но есть что подтянуть"
            Level.BAD -> "Что-то идёт не так — разберёмся"
            Level.UNKNOWN -> "Пока рано судить — нужно 1–2 чек-ина"
        }
        return Result(
            level = level,
            headline = headline,
            focus = if (level == Level.GOOD || level == Level.UNKNOWN) null else worst?.hint,
            items = items,
            sinceStart = sinceStart(input)
        )
    }

    private fun pace(input: Input, recent: List<Week>): Item {
        val target = input.targetRate
        if (input.atMaintenance) {
            return Item(Kind.PACE, "Темп", Level.GOOD, "перерыв", "Сейчас вес и должен стоять — это часть плана")
        }
        val rates = recent.mapNotNull { it.rate }
        if (rates.isEmpty()) return Item(Kind.PACE, "Темп", Level.UNKNOWN, "—", "Появится после чек-ина с парой недель взвешиваний")
        val avg = rates.average()
        val value = "${fmt(avg)}% в неделю · цель ${fmt(target)}%"
        return when {
            avg > 1.2 || avg > target * 2 -> Item(Kind.PACE, "Темп", Level.BAD, value, "Слишком быстро — так уходят и мышцы. Ешь весь план, не урезай сверх него")
            avg > 1.0 || avg > target * 1.6 -> Item(Kind.PACE, "Темп", Level.WATCH, value, "Быстрее цели — следи за силой и белком")
            avg >= target * 0.75 -> Item(Kind.PACE, "Темп", Level.GOOD, value, "Вес уходит как надо")
            avg >= target * 0.4 || rates.size < 2 -> Item(Kind.PACE, "Темп", Level.WATCH, value, "Медленнее цели — план подстроится сам, главное — полный дневник")
            else -> Item(Kind.PACE, "Темп", Level.BAD, value, "Вес почти не уходит — проверь, всё ли записываешь, включая напитки и перекусы")
        }
    }

    private fun waist(input: Input): Item {
        val pts = input.waist.filter { it.second > 0 }.sortedBy { it.first }
        val last = pts.lastOrNull() ?: return Item(Kind.WAIST, "Талия", Level.UNKNOWN, "—", "Замерь талию на чек-ине — раз в неделю")
        val base = pts.lastOrNull { ChronoUnit.DAYS.between(it.first, last.first) >= WAIST_SPAN_DAYS }
            ?: return Item(Kind.WAIST, "Талия", Level.UNKNOWN, "${fmt(last.second)} см", "Нужны замеры хотя бы за 3 недели")
        val change = last.second - base.second
        val weeks = ChronoUnit.DAYS.between(base.first, last.first) / 7.0
        val value = "${signed(change)} см за ${weeks.toInt().coerceAtLeast(1)} нед."
        return when {
            input.atMaintenance && abs(change) < 1.0 -> Item(Kind.WAIST, "Талия", Level.GOOD, value, "Держится — результат закрепляется")
            change <= -0.5 -> Item(Kind.WAIST, "Талия", Level.GOOD, value, "Уменьшается — это и есть уходящий жир")
            change < 1.0 -> Item(Kind.WAIST, "Талия", Level.WATCH, value, "Почти не меняется — смотрим ещё пару недель, замеряй в одно время")
            else -> Item(Kind.WAIST, "Талия", Level.BAD, value, "Талия растёт — дефицита сейчас нет, проверь дневник")
        }
    }

    private fun strength(last: Week?): Item {
        val signal = last?.strengthSignal
            ?: return Item(Kind.STRENGTH, "Сила", Level.UNKNOWN, "—", "Загрузи экспорт из Hevy — увидишь, держатся ли мышцы")
        val value = last.strengthIndex?.let { "${signed(it)}%" } ?: "—"
        return when (signal) {
            "green" -> Item(Kind.STRENGTH, "Сила", Level.GOOD, value, "Держится — мышцы на месте")
            "yellow" -> Item(Kind.STRENGTH, "Сила", Level.WATCH, value, "Проседает — проверь сон и белок, тяжёлые подходы не убирай")
            else -> Item(Kind.STRENGTH, "Сила", Level.BAD, value, "Заметно падает — мышцам не хватает ресурса, план уже смягчён")
        }
    }

    private fun share(
        kind: Kind, title: String, done: Int, total: Int,
        good: Double, watch: Double, unit: String, hintGood: String, hintBad: String
    ): Item {
        if (total <= 0) return Item(kind, title, Level.UNKNOWN, "—", "Появится после первого чек-ина")
        val s = done.toDouble() / total
        val value = "$done из $total $unit"
        return when {
            s >= good -> Item(kind, title, Level.GOOD, value, hintGood)
            s >= watch -> Item(kind, title, Level.WATCH, value, hintBad)
            else -> Item(kind, title, Level.BAD, value, hintBad)
        }
    }

    private fun workouts(recent: List<Week>): Item {
        val goal = recent.sumOf { it.workoutGoal }
        if (goal <= 0) return Item(Kind.WORKOUTS, "Силовые", Level.UNKNOWN, "—", "Появится после первого чек-ина")
        val done = recent.sumOf { it.workouts }
        val value = "$done из $goal"
        return when {
            done >= goal -> Item(Kind.WORKOUTS, "Силовые", Level.GOOD, value, "Есть повод для тела сохранять мышцы")
            done * 2 >= goal -> Item(Kind.WORKOUTS, "Силовые", Level.WATCH, value, "Пропуски — держи хотя бы тяжёлые базовые подходы")
            else -> Item(Kind.WORKOUTS, "Силовые", Level.BAD, value, "Без силовых на дефиците уходят и мышцы — хотя бы 2 в неделю")
        }
    }

    private fun sinceStart(input: Input): String? {
        val start = input.startDate ?: return null
        val weeks = ChronoUnit.DAYS.between(start, input.today) / 7
        if (weeks < 1) return null
        val parts = mutableListOf<String>()
        val w0 = input.startWeight
        val w1 = input.weightNow
        if (w0 != null && w1 != null) parts += "${signed(w1 - w0)} кг"
        val pts = input.waist.filter { it.second > 0 && !it.first.isBefore(start) }.sortedBy { it.first }
        val waistBefore = input.waist.filter { it.second > 0 && !it.first.isAfter(start) }.maxByOrNull { it.first } ?: pts.firstOrNull()
        val waistNow = pts.lastOrNull()
        if (waistBefore != null && waistNow != null && waistNow.first.isAfter(waistBefore.first)) {
            parts += "талия ${signed(waistNow.second - waistBefore.second)} см"
        }
        parts += "$weeks ${plural(weeks.toInt(), "неделя", "недели", "недель")}"
        return "С начала программы: " + parts.joinToString(", ")
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v).replace('.', ',')

    private fun signed(v: Double): String {
        val t = fmt(abs(v))
        return when {
            t == "0,0" -> "0"
            v > 0 -> "+$t"
            else -> "−$t"
        }
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
