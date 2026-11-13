package com.nutrilens.app.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Помощник пишет первым (3.25): одна короткая подсказка, когда в данных видно, что программа
 * пошла не так или вот-вот сменится этап. Шаблоны, а не вызов ИИ: бесплатно, мгновенно и
 * одинаково с веб-версией (web/src/data/nudges.ts). Тон — без упрёков: что случилось и что
 * сделать дальше. Чистый модуль без Android.
 */
object Nudges {

    enum class Type(val prefix: String, val cooldownDays: Int) {
        NO_LOG("nolog", 2), PROTEIN("protein", 3), BREAK_END("breakEnd", 14), SLEEP("sleep", 4)
    }

    /** [key] уникален для эпизода: одна и та же подсказка не повторяется. */
    data class Nudge(val key: String, val type: Type, val title: String, val text: String)

    data class Day(val date: LocalDate, val kcal: Double, val protein: Double, val sleptWell: Boolean?)

    data class PhaseInfo(val isBreak: Boolean, val startDate: LocalDate, val daysLeft: Int?, val nextCalories: Int?)

    const val NO_LOG_KCAL = 300.0
    const val LOGGED_KCAL = 800.0
    const val PROTEIN_SHARE = 0.9

    /**
     * Подходящие подсказки по приоритету. [days] — законченные дни до сегодня по возрастанию даты.
     * [phase] null — программы нет, она на паузе или завершена: молчим.
     */
    fun candidates(days: List<Day>, proteinTarget: Double, phase: PhaseInfo?): List<Nudge> {
        if (phase == null) return emptyList()
        val out = mutableListOf<Nudge>()
        val week = days.takeLast(7)

        // 1. Два дня без записей — без дневника недельный пересчёт не работает.
        val two = week.takeLast(2)
        if (two.size == 2 && two.all { it.kcal < NO_LOG_KCAL }) {
            out += Nudge(
                "nolog-${two[0].date}", Type.NO_LOG, "Два дня без записей",
                "Ничего страшного — запиши сегодня хотя бы один приём пищи. План тот же, а неделю чек-ин всё равно разберёт."
            )
        }

        // 2. Белок три записанных дня подряд ниже 90% — главный риск потерять мышцы.
        val three = week.takeLast(3)
        if (proteinTarget > 0 && three.size == 3 &&
            three.all { it.kcal >= LOGGED_KCAL && it.protein < proteinTarget * PROTEIN_SHARE }
        ) {
            val avg = three.map { it.protein }.average().roundToInt()
            out += Nudge(
                "protein-${three[2].date}", Type.PROTEIN, "Белка не хватает третий день",
                "В среднем $avg из ${proteinTarget.roundToInt()} г. На дефиците это первое, что бережёт мышцы: " +
                    "добавь творог, яйца, курицу или рыбу в ближайший приём."
            )
        }

        // 3. Перерыв заканчивается завтра — калории снова станут ниже.
        val left = phase.daysLeft
        if (phase.isBreak && left != null && left <= 1) {
            val kcal = phase.nextCalories
            out += Nudge(
                "breakEnd-${phase.startDate}", Type.BREAK_END, "Завтра снова блок сжигания",
                "Перерыв сделал своё: голод и силы восстановились." +
                    (if (kcal != null) " С завтра план ≈ $kcal ккал" else " С завтра снова дефицит") +
                    " — продукты на первые дни лучше купить заранее."
            )
        }

        // 4. Недосып 3 ночи из 4 — усиливает голод и уводит потери в мышцы.
        val four = week.takeLast(4)
        if (four.count { it.sleptWell == false } >= 3) {
            out += Nudge(
                "sleep-${four.last().date}", Type.SLEEP, "Мало сна три ночи из четырёх",
                "Недосып усиливает голод и мешает сохранить мышцы. Попробуй сегодня лечь на 30 минут раньше — " +
                    "это проще, чем терпеть голод завтра."
            )
        }
        return out
    }

    /**
     * Первая подсказка, которую ещё не показывали и чей тип не «отдыхает» (cooldown).
     * [history] — key → дата, когда подсказку прочитали.
     */
    fun next(today: LocalDate, days: List<Day>, proteinTarget: Double, phase: PhaseInfo?, history: Map<String, LocalDate>): Nudge? =
        candidates(days, proteinTarget, phase).firstOrNull { n ->
            n.key !in history && history.none { (k, d) ->
                k.startsWith("${n.type.prefix}-") && ChronoUnit.DAYS.between(d, today) < n.type.cooldownDays
            }
        }
}
