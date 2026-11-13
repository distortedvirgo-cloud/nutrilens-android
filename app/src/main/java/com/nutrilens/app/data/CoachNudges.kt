package com.nutrilens.app.data

import android.content.Context
import com.nutrilens.app.insights.FatBurnProgram
import com.nutrilens.app.insights.Nudges
import java.time.LocalDate

/**
 * Подсказка помощника на сегодня (3.25): данные из БД → чистый модуль Nudges.
 * Прочитанные подсказки и уже отправленные уведомления — в SharedPreferences:
 * это удобство одного устройства, не данные пользователя (в бэкап не идут).
 */
object CoachNudges {
    private const val PREFS = "coach_nudges"
    private const val READ_PREFIX = "read:"
    private const val NOTIFIED_PREFIX = "notified:"

    /** Подсказка на сегодня или null: только в идущей программе (не пауза, не завершена). */
    suspend fun load(context: Context, today: LocalDate = LocalDate.now()): Nudges.Nudge? {
        val db = NutriLensDatabase.getInstance(context)
        val settings = SettingsRepository(db.settingsDao()).get()
        val program = settings.fatBurnProgramState ?: return null
        if (program.paused || program.completed) return null
        val phase = program.currentPhase ?: return null
        val phaseStart = phase.startDate ?: return null
        val plan = db.scorePlan(settings)
        // Дни до начала программы не считаем: «два дня без записей» до старта — не повод.
        val from = maxOf(today.minusDays(7), program.startDate)
        val days = if (from > today.minusDays(1)) emptyList()
        else db.scoreDays(from, today.minusDays(1)).map { Nudges.Day(it.date, it.kcal, it.protein, it.sleptWell) }
        val isBreak = phase.type == FatBurnProgram.PhaseType.BREAK
        val info = Nudges.PhaseInfo(
            isBreak = isBreak,
            startDate = phaseStart,
            daysLeft = if (isBreak) FatBurnProgram.breakDaysLeft(program, today) else null,
            // В перерыве план в настройках — на уровне расхода; калории следующего блока
            // посчитает пересчёт, поэтому в тексте просто «снова дефицит».
            nextCalories = null
        )
        return Nudges.next(today, days, plan.protein, info, history(context))
    }

    /** «Понятно» на карточке: подсказка больше не покажется, её тип отдохнёт cooldown дней. */
    fun dismiss(context: Context, key: String, today: LocalDate = LocalDate.now()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putString(READ_PREFIX + key, today.toString())
        // Старше 30 дней — не нужны: cooldown короче.
        val cut = today.minusDays(30).toString()
        prefs.all.forEach { (k, v) -> if (v is String && v < cut) edit.remove(k) }
        edit.apply()
    }

    /** Уведомление по этой подсказке ещё не отправляли — отметить и вернуть true. */
    fun markNotified(context: Context, key: String, today: LocalDate = LocalDate.now()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(NOTIFIED_PREFIX + key)) return false
        prefs.edit().putString(NOTIFIED_PREFIX + key, today.toString()).apply()
        return true
    }

    private fun history(context: Context): Map<String, LocalDate> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            if (!k.startsWith(READ_PREFIX) || v !is String) null
            else runCatching { k.removePrefix(READ_PREFIX) to LocalDate.parse(v) }.getOrNull()
        }.toMap()
}
