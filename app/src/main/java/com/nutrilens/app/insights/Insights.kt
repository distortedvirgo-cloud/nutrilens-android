package com.nutrilens.app.insights

import com.nutrilens.app.data.MealEntity
import java.time.LocalDate

/* ============================================================
   Мозг умного ассистента. Вся логика — детерминированная и
   считается ЛОКАЛЬНО из данных пользователя (приёмы пищи, вода,
   вес, цели). Не требует ни сети, ни API-ключа, поэтому подсказки
   доступны всегда и мгновенно. ИИ остаётся для глубоких вопросов
   (чат, рецепты), а здесь — быстрые персональные выводы «на сейчас».
   ============================================================ */

/** Сводка по дню: суммарное КБЖУ, число записей и час последней. */
data class DayTotals(
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val count: Int,
    /** Час (0-23) последней записи за день, либо null если записей нет. */
    val lastEntryHour: Int?
)

/** Сводка по дню: суммарное КБЖУ, число записей и час последней. */
fun getDayTotals(meals: List<MealEntity>): DayTotals {
    var calories = 0.0
    var protein = 0.0
    var fat = 0.0
    var carbs = 0.0
    for (m in meals) {
        calories += m.calories
        protein += m.protein
        fat += m.fat
        carbs += m.carbs
    }
    var lastEntryHour: Int? = null
    if (meals.isNotEmpty()) {
        // Время в формате "HH:mm" — лексикографическая сортировка совпадает с хронологической.
        val h = meals.maxByOrNull { it.time }?.time?.substringBefore(':')?.toIntOrNull()
        if (h != null) lastEntryHour = h
    }
    return DayTotals(calories, protein, fat, carbs, meals.size, lastEntryHour)
}

/** Серия дней подряд с записями (как на дашборде, считаем независимо). */
fun calcStreak(dates: Set<String>, now: LocalDate): Int {
    if (dates.isEmpty()) return 0
    var streak = 0
    var check = now
    if (dates.contains(check.toString())) {
        streak++
    } else {
        check = check.minusDays(1)
        if (!dates.contains(check.toString())) return 0
    }
    while (true) {
        check = check.minusDays(1)
        if (dates.contains(check.toString())) streak++
        else break
    }
    return streak
}

/** Приветствие по времени суток. */
data class Greeting(val emoji: String, val text: String)

fun greeting(hour: Int): Greeting {
    if (hour < 5) return Greeting("🌙", "Доброй ночи")
    if (hour < 12) return Greeting("☀️", "Доброе утро")
    if (hour < 18) return Greeting("🌤️", "Добрый день")
    return Greeting("🌆", "Добрый вечер")
}

/**
 * Суточная норма воды: ~35 мл/кг (округление до 50 мл), при тренировке +500 мл.
 * Если вес неизвестен — типовые 2000 мл.
 */
fun waterNormaMl(weightKg: Double?, workout: Boolean): Int {
    val base = if (weightKg != null) Math.round(weightKg * 35 / 50.0).toInt() * 50 else 2000
    return if (workout) base + 500 else base
}

data class MacroGoals(val protein: Double, val fat: Double, val carbs: Double)

/**
 * Эффективные цели по БЖУ. Если пользователь задал их в настройках — берём их;
 * иначе выводим из дневной цели калорий и веса (белок 1.8 г/кг, жиры 30% ккал,
 * углеводы — остаток). Это делает ассистента полезным сразу, без ручной настройки.
 */
fun effectiveMacroGoals(
    proteinGoal: Double?,
    fatGoal: Double?,
    carbsGoal: Double?,
    dailyGoal: Double,
    weightKg: Double?,
): MacroGoals {
    val protein = goalOr(proteinGoal) {
        if (weightKg != null) Math.round(weightKg * 1.8).toDouble()
        else Math.round(dailyGoal * 0.3 / 4).toDouble()
    }
    val fat = goalOr(fatGoal) { Math.round(dailyGoal * 0.3 / 9).toDouble() }
    val carbs = goalOr(carbsGoal) { Math.round(dailyGoal * 0.4 / 4).toDouble() }
    return MacroGoals(protein, fat, carbs)
}

/**
 * Повторяет семантику `||` из оригинала: цель используется только если
 * задана и не равна нулю (ноль в JS считается falsy и отбрасывается).
 */
private fun goalOr(v: Double?, computed: () -> Double): Double =
    if (v != null && v != 0.0) v else computed()

/** Целевые диапазоны БЖУ под тренировочный день (зависят от веса). */
private data class WorkoutMacros(
    val proteinLow: Int,
    val proteinHigh: Int,
    val carbsLow: Int,
    val carbsHigh: Int,
    val fatLow: Int,
    val fatHigh: Int,
)

private fun workoutMacros(weightKg: Double?): WorkoutMacros? {
    if (weightKg == null) return null
    return WorkoutMacros(
        proteinLow = Math.round(weightKg * 1.8).toInt(),
        proteinHigh = Math.round(weightKg * 2.0).toInt(),
        carbsLow = Math.round(weightKg * 4).toInt(),
        carbsHigh = Math.round(weightKg * 5).toInt(),
        fatLow = Math.round(weightKg * 0.8).toInt(),
        fatHigh = Math.round(weightKg * 1.0).toInt(),
    )
}

/* ============================================================
   Инсайты «на сейчас»
   ============================================================ */

data class Insight(val id: String, val emoji: String, val title: String, val text: String)

data class InsightContext(
    val hour: Int,
    val totals: DayTotals,
    val dailyGoal: Double,
    val macroGoals: MacroGoals,
    val waterMl: Int,
    val waterNormMl: Int,
    val streak: Int,
    val workoutDone: Boolean,
    val hasAnyMealsEver: Boolean,
)

/**
 * Собирает все релевантные подсказки, сортирует по приоритету и отдаёт
 * топ-4. Приоритеты: сначала срочное (перебор, пустой день, дефициты),
 * затем позитив — чтобы экран был сбалансированным, а не «пугалкой».
 */
fun buildInsights(ctx: InsightContext): List<Insight> {
    val hour = ctx.hour
    val totals = ctx.totals
    val dailyGoal = ctx.dailyGoal
    val macroGoals = ctx.macroGoals
    val waterToday = ctx.waterMl
    val waterGoal = ctx.waterNormMl
    val streak = ctx.streak
    val workout = ctx.workoutDone

    val remaining = dailyGoal - totals.calories
    val out = mutableListOf<RankedInsight>()

    // Пустой день — приглашение начать.
    if (totals.count == 0) {
        out += RankedInsight(
            priority = 95,
            insight = Insight(
                id = "empty",
                emoji = "📷",
                title = "Пока нет записей",
                text = "Запишите первый приём пищи — и я начну следить за балансом и подсказывать, что съесть дальше.",
            ),
        )
    }

    // Перебор калорий.
    if (totals.count > 0 && remaining < 0) {
        out += RankedInsight(
            priority = 100,
            insight = Insight(
                id = "over",
                emoji = "⚠️",
                title = "Перебор на ${Math.round(-remaining)} ккал",
                text = "Не критично. Следующий приём сделайте легче: белок + овощи, без жирного и сладкого.",
            ),
        )
    }

    // Белок: дефицит или выполнение нормы.
    val proteinDiff = macroGoals.protein - totals.protein
    if (totals.count > 0 && proteinDiff > 15) {
        out += RankedInsight(
            priority = 80,
            insight = Insight(
                id = "protein-low",
                emoji = "🍗",
                title = "Не хватает ${Math.round(proteinDiff)} г белка",
                text = "Белок даёт сытость и сохраняет мышцы. Добавьте творог, яйца, курицу или протеиновый коктейль.",
            ),
        )
    } else if (totals.count > 0 && totals.protein >= macroGoals.protein) {
        out += RankedInsight(
            priority = 40,
            insight = Insight(
                id = "protein-ok",
                emoji = "🍗",
                title = "Норма белка выполнена",
                text = "${Math.round(totals.protein)} г белка за день — отлично для мышц и контроля аппетита.",
            ),
        )
    }

    // Вода: мало или норма.
    if (waterToday < waterGoal * 0.5) {
        out += RankedInsight(
            priority = 70,
            insight = Insight(
                id = "water-low",
                emoji = "💧",
                title = "Выпито $waterToday из $waterGoal мл",
                text = "Вода помогает контролировать аппетит и уровень энергии. Выпейте стакан прямо сейчас.",
            ),
        )
    } else if (waterGoal > 0 && waterToday >= waterGoal) {
        out += RankedInsight(
            priority = 38,
            insight = Insight(
                id = "water-ok",
                emoji = "💧",
                title = "Норма воды выполнена 💧",
                text = "Отличная гидратация сегодня — так держать!",
            ),
        )
    }

    // Долгая пауза без записей.
    val lastEntryHour = totals.lastEntryHour
    if (lastEntryHour != null && totals.count > 0) {
        val gap = hour - lastEntryHour
        if (gap >= 5 && hour >= 6 && hour <= 23) {
            out += RankedInsight(
                priority = 60,
                insight = Insight(
                    id = "gap",
                    emoji = "⏰",
                    title = "Без записей уже $gap ч",
                    text = "Долгие паузы часто ведут к перееданию вечером. Запишите перекус или приём пищи.",
                ),
            )
        }
    }

    // Вечер, а калорий ещё много — напоминание поужинать.
    if (totals.count > 0 && remaining > 0 && hour >= 17 && remaining > dailyGoal * 0.35) {
        out += RankedInsight(
            priority = 55,
            insight = Insight(
                id = "evening",
                emoji = "🍽️",
                title = "На ужин ещё ${Math.round(remaining)} ккал",
                text = "Не пропускайте ужин — иначе велик риск сорваться ночью. Выберите белок и овощи.",
            ),
        )
    }

    // Почти у цели по калориям.
    if (totals.count > 0 && remaining > 0 && remaining < 200) {
        out += RankedInsight(
            priority = 35,
            insight = Insight(
                id = "nearly",
                emoji = "🎯",
                title = "Почти у цели",
                text = "Осталось всего ${Math.round(remaining)} ккал. Лёгкий перекус — и день идеально сбалансирован.",
            ),
        )
    }

    // Тренировочный день.
    if (workout) {
        val macros = workoutMacros(null)
        out += RankedInsight(
            priority = 50,
            insight = Insight(
                id = "workout",
                emoji = "💪",
                title = "Тренировочный день 💪",
                text = if (macros != null)
                    "Сделайте упор на белок (~${macros.proteinLow}–${macros.proteinHigh} г) и углеводы до и после нагрузки — это ускорит восстановление."
                else
                    "Поешьте за 1,5–2 ч до тренировки (углеводы + белок) и в течение часа после. Укажите вес в дневнике — посчитаю нормы точнее.",
            ),
        )
    }

    // Серия дней.
    if (streak >= 3) {
        out += RankedInsight(
            priority = 30,
            insight = Insight(
                id = "streak",
                emoji = "🔥",
                title = "Серия $streak дн. 🔥",
                text = "Вы ведёте дневник без пропусков. Регулярность — главный секрет результата.",
            ),
        )
    }

    return out.sortedByDescending { it.priority }.take(4).map { it.insight }
}

/** Внутренняя обёртка: приоритет нужен только для сортировки, в Insight он не входит. */
private data class RankedInsight(val priority: Int, val insight: Insight)

/* ============================================================
   «Что съесть дальше» — рекомендация фокуса следующего приёма.
   ============================================================ */

/**
 * Рекомендация следующего приёма по самому отстающему макронутриенту.
 * Тексты дословно из веб-версии; возвращается строка вида «заголовок \n текст».
 * Если за день ещё нет записей — возвращает пустую строку (в веб-версии null).
 */
fun nextMealFocus(totals: DayTotals, goals: MacroGoals): String {
    if (totals.count == 0) return ""

    val deficits = listOf(
        Triple("protein", totals.protein / goals.protein, goals.protein - totals.protein),
        Triple("fat", totals.fat / goals.fat, goals.fat - totals.fat),
        Triple("carbs", totals.carbs / goals.carbs, goals.carbs - totals.carbs),
    )

    if (deficits.all { it.third <= 0 }) {
        return "Все цели по БЖУ закрыты\nОтличный баланс! Следующий приём сделайте лёгким: овощи + нежирный белок."
    }

    // Самый отстающий макронутриент (минимальная доля выполнения).
    val worst = deficits.minByOrNull { it.second } ?: return ""
    val left = worst.third
    return when (worst.first) {
        "protein" ->
            "Акцент на белок (+${Math.round(left)} г)\n" +
                "Курица, рыба, яйца, творог или бобовые. Добавьте овощи — они дадут объём без лишних калорий."
        "fat" ->
            "Доберите полезных жиров (+${Math.round(left)} г)\n" +
                "Авокадо, орехи, оливковое масло или жирная рыба. Жиры важны для гормонов и сытости."
        else ->
            "Нужны углеводы (+${Math.round(left)} г)\n" +
                "Крупы, цельнозерновой хлеб, фрукты. Это энергия — особенно если впереди тренировка."
    }
}