package com.nutrilens.app.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Цикл и вес (3.27). Чистый Kotlin без Android.
 *
 * Перед месячными и в первые дни вес у многих выше на 1–2 кг — это вода. Без учёта
 * цикла чек-ин видел бы «вес стоит» и урезал калории. Здесь только даты начала
 * месячных: по ним — прогноз следующих и «водная фаза» (≈ неделя до и 3 первых дня).
 */
object Cycle {
    const val DEFAULT_LENGTH = 28
    const val MIN_LENGTH = 21
    const val MAX_LENGTH = 40

    /** Сколько последних циклов брать для средней длины. */
    const val HISTORY = 6

    /** «Водная фаза»: столько дней до начала и столько первых дней месячных. */
    const val PRE_DAYS = 7L
    const val PERIOD_DAYS = 3L

    /** Спрашиваем «Начались?» с ожидаемого дня и ещё столько дней — дальше уже не гадаем. */
    const val ASK_DAYS = 10L

    /** Две отметки ближе этого — одна и та же менструация (дубль из Health Connect или двойной тап). */
    const val SAME_PERIOD_DAYS = 10L

    /** Даты по возрастанию без дублей одного цикла. */
    fun normalize(starts: Collection<LocalDate>): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        for (d in starts.distinct().sorted()) {
            val last = out.lastOrNull()
            if (last == null || ChronoUnit.DAYS.between(last, d) >= SAME_PERIOD_DAYS) out += d
        }
        return out
    }

    /** Средняя длина цикла по последним (правдоподобным) циклам; данных нет — 28. */
    fun averageLength(starts: Collection<LocalDate>): Int {
        val gaps = normalize(starts).zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
            .filter { it in MIN_LENGTH..MAX_LENGTH }
            .takeLast(HISTORY)
        return if (gaps.isEmpty()) DEFAULT_LENGTH else Math.round(gaps.average()).toInt()
    }

    /** Ожидаемое начало следующих месячных; null — отметок ещё нет. */
    fun nextStart(starts: Collection<LocalDate>): LocalDate? {
        val last = normalize(starts).lastOrNull() ?: return null
        return last.plusDays(averageLength(starts).toLong())
    }

    /**
     * День в «водной фазе»: первые 3 дня любых отмеченных месячных, неделя до любых
     * отмеченных (задним числом) и неделя до ожидаемых.
     */
    fun isWaterDay(date: LocalDate, starts: Collection<LocalDate>): Boolean {
        val list = normalize(starts)
        val expected = nextStart(list)
        val anchors = if (expected != null) list + expected else list
        return anchors.any { s ->
            val toStart = ChronoUnit.DAYS.between(date, s)
            (toStart in 1..PRE_DAYS) || (toStart <= 0 && -toStart < PERIOD_DAYS && s in list)
        }
    }

    /**
     * Чек-ин в «водной фазе»: сегодня или 2 из 3 последних дней периода — вода.
     * Тогда «вес стоит» не значит, что план перестал работать.
     */
    fun checkInInWaterPhase(today: LocalDate, starts: Collection<LocalDate>): Boolean {
        if (starts.isEmpty()) return false
        if (isWaterDay(today, starts)) return true
        return (1L..3L).count { isWaterDay(today.minusDays(it), starts) } >= 2
    }

    /** Показывать ли «Начались?»: ожидаемый день наступил, отметки ещё нет, сегодня «Ещё нет» не нажимали. */
    fun shouldAsk(today: LocalDate, starts: Collection<LocalDate>, notYetOn: LocalDate?): Boolean {
        val expected = nextStart(starts) ?: return false
        if (today.isBefore(expected)) return false
        if (ChronoUnit.DAYS.between(expected, today) > ASK_DAYS) return false
        return notYetOn != today
    }
}
