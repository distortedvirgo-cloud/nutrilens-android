package com.nutrilens.app.training

import java.util.Locale

/**
 * Сила на недельном чек-ине: одна фраза, до трёх упражнений и максимум одна
 * подсказка. Чистый Kotlin — Android-часть только сохраняет и показывает.
 */
object StrengthCheck {
    /** Сколько упражнений показывать в карточке — больше уже шум. */
    const val SHOWN = 3

    /** Строка карточки: «Жим лежа (Штанга) · 60 × 5 → 62,5 × 5 · +4,2%». */
    data class Lift(val name: String, val before: String?, val now: String?, val changePercent: Double?)

    /** Итог для чек-ина. [signal] — "green" / "yellow" / "red" или null, если силу не оценивали. */
    data class Outcome(
        val signal: String?,
        val indexPercent: Double?,
        val note: String,
        val lifts: List<Lift>,
        val tip: String?,
        /** Сила падает (красный) второй чек-ин подряд — пора в перерыв раньше. */
        val earlyBreak: Boolean
    )

    fun outcome(report: StrengthAnalyzer.Report, previousSignal: String?): Outcome {
        val signal = when (report.signal) {
            StrengthAnalyzer.Signal.NONE -> null
            else -> report.signal.name.lowercase(Locale.ROOT)
        }
        val lifts = report.shown.take(SHOWN).map {
            Lift(it.name, it.baseline?.bestSet, it.recent?.bestSet, it.changePercent)
        }
        return Outcome(
            signal = signal,
            indexPercent = report.indexPercent,
            note = report.note,
            lifts = lifts,
            tip = tip(report),
            earlyBreak = signal == "red" && previousSignal == "red"
        )
    }

    /** Одна подсказка в неделю — только если есть повод. Нерегулярность важнее застоя. */
    fun tip(report: StrengthAnalyzer.Report): String? {
        val main = report.exercises.take(5)
        main.firstOrNull { it.irregular }?.let {
            return "«${it.name}» было с перерывами больше 2 недель — так сила не держится. Делай его каждую неделю"
        }
        main.firstOrNull { it.stalled }?.let {
            return "«${it.name}» стоит 3 тренировки подряд — сделай одну тренировку полегче (−10% веса) или смени диапазон повторов"
        }
        return null
    }

    /** Итоговый сигнал мышц: худший из «тренировки, белок, темп» и силы. */
    fun worst(a: String, b: String?): String {
        val rank = listOf("green", "yellow", "red")
        val ra = rank.indexOf(a).coerceAtLeast(0)
        val rb = b?.let { rank.indexOf(it) } ?: -1
        return rank[maxOf(ra, rb)]
    }

    // Хранение строк карточки в одной колонке: строка на упражнение, поля через табуляцию.
    fun encode(lifts: List<Lift>): String = lifts.joinToString("\n") { l ->
        listOf(l.name, l.before.orEmpty(), l.now.orEmpty(), l.changePercent?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty())
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    fun decode(s: String?): List<Lift> = s.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t')
        if (p.isEmpty() || p[0].isBlank()) return@mapNotNull null
        Lift(
            name = p[0],
            before = p.getOrNull(1)?.takeIf { it.isNotBlank() },
            now = p.getOrNull(2)?.takeIf { it.isNotBlank() },
            changePercent = p.getOrNull(3)?.toDoubleOrNull()
        )
    }
}
