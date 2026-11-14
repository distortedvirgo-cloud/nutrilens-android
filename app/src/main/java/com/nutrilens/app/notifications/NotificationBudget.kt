package com.nutrilens.app.notifications

import android.content.Context
import java.time.LocalDate

/**
 * Бюджет напоминаний программы (3.29): не больше одного в день. Поток уведомлений —
 * частая причина бросить приложение, поэтому чек-ин, цикл, взвешивание и подсказки
 * помощника делят один «слот» на день. Кто не успел — ждёт карточкой на «Сегодня».
 * Ответы на действия человека (готов анализ фото и т.п.) бюджет не тратят.
 */
object NotificationBudget {
    private const val PREFS = "notification_budget"
    private const val KEY_DAY = "day"
    private const val KEY_KIND = "kind"

    /** Порядок важности — в этом порядке их и проверяет воркер. */
    enum class Kind { CHECK_IN, CYCLE, WEIGH_IN, NUDGE }

    /** Слот на сегодня свободен — занять и вернуть true; уже занят — false. */
    @Synchronized
    fun claim(context: Context, kind: Kind, today: LocalDate = LocalDate.now()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_DAY, Long.MIN_VALUE) == today.toEpochDay()) return false
        prefs.edit().putLong(KEY_DAY, today.toEpochDay()).putString(KEY_KIND, kind.name).apply()
        return true
    }

    /** Сегодня слот уже потрачен (для проверок до тяжёлой работы). */
    fun spent(context: Context, today: LocalDate = LocalDate.now()): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DAY, Long.MIN_VALUE) == today.toEpochDay()
}
