package com.nutrilens.app.ai

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Программная проверка ответа анализатора еды. Модели ошибаются предсказуемо:
 * пропускают калории при заполненных БЖУ (и наоборот), отдают отрицательные или
 * нечисловые значения, выходят за шкалы оценок. Здесь — только безопасные
 * правки, которые не спорят с моделью там, где она могла быть права.
 */

/** Калории по Этуотеру: белки и углеводы — 4 ккал/г, жиры — 9 ккал/г. */
internal fun atwaterKcal(protein: Double, fat: Double, carbs: Double): Double =
    protein * 4 + fat * 9 + carbs * 4

/**
 * Чинит один продукт:
 * - отрицательные и нечисловые значения → 0;
 * - калорий нет, но есть БЖУ → калории по Этуотеру;
 * - калорий и БЖУ нет, но есть вес и плотность → калории из «вес × плотность»;
 * - вес не указан, но есть калории и плотность → вес из них же (для показа «г» в деталях).
 */
internal fun sanitizeItem(item: AnalyzedItemResult): AnalyzedItemResult {
    fun clean(v: Double) = if (v.isNaN() || v.isInfinite() || v < 0) 0.0 else v
    var weight = clean(item.weightG)
    val density = clean(item.calorieDensity)
    val protein = clean(item.protein)
    val fat = clean(item.fat)
    val carbs = clean(item.carbs)
    var calories = clean(item.calories)

    val fromMacros = atwaterKcal(protein, fat, carbs)
    if (calories <= 0.0 && fromMacros > 0.0) calories = fromMacros
    if (calories <= 0.0 && weight > 0.0 && density > 0.0) calories = weight * density / 100.0
    if (weight <= 0.0 && calories > 0.0 && density > 0.0) weight = calories / density * 100.0

    return item.copy(
        weightG = weight.roundToInt().toDouble(),
        calorieDensity = density,
        calories = calories.roundToInt().toDouble(),
        protein = round1(protein),
        fat = round1(fat),
        carbs = round1(carbs)
    )
}

/**
 * Итоговая обработка результата: чистка продуктов, сверка итогов с суммой
 * продуктов ([fixMealDrift]), шкалы оценок в допустимых границах, непустое имя.
 */
fun finalizeMeal(result: MealAnalysisResult): MealAnalysisResult {
    val items = result.items
        .map(::sanitizeItem)
        .filter { it.name.isNotBlank() || it.calories > 0.0 }
    var fixed = fixMealDrift(result.copy(items = items))

    // Итоги без продуктов: калорий нет, а БЖУ есть — восстанавливаем по Этуотеру.
    if (fixed.calories <= 0.0) {
        val fromMacros = atwaterKcal(fixed.protein, fixed.fat, fixed.carbs)
        if (fromMacros > 0.0) fixed = fixed.copy(calories = fromMacros)
    }

    val name = fixed.name.trim().ifBlank {
        items.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifBlank { "Приём пищи" }
    }
    // Калории сильно расходятся с БЖУ — расчёт внутренне противоречив, высокая
    // уверенность модели тут не заслужена.
    val confidence = when {
        fixed.confidenceScore <= 0.0 -> 0.0
        atwaterMismatch(fixed) > 0.35 -> fixed.confidenceScore.coerceIn(1.0, 5.0)
        else -> fixed.confidenceScore.coerceIn(1.0, 10.0)
    }
    return fixed.copy(
        name = name,
        calories = fixed.calories.coerceAtLeast(0.0).roundToInt().toDouble(),
        protein = round1(fixed.protein.coerceAtLeast(0.0)),
        fat = round1(fixed.fat.coerceAtLeast(0.0)),
        carbs = round1(fixed.carbs.coerceAtLeast(0.0)),
        // 0 — модель поле не заполнила: оставляем 0, экран покажет «нет оценки».
        confidenceScore = confidence,
        healthScore = fixed.healthScore?.coerceIn(0.0, 100.0)
    )
}

/**
 * Насколько итоговые калории расходятся с БЖУ по Этуотеру (0.0 — совпадают).
 * Клетчатка, сахарные спирты и алкоголь дают законные расхождения, поэтому это
 * сигнал для снижения уверенности, а не повод переписывать числа.
 */
internal fun atwaterMismatch(result: MealAnalysisResult): Double {
    val fromMacros = atwaterKcal(result.protein, result.fat, result.carbs)
    if (result.calories <= 0.0 || fromMacros <= 0.0) return 0.0
    return abs(result.calories - fromMacros) / result.calories
}

private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

/** Модель честно сказала, что еды нет (см. промпт): не повторять запрос, а сообщить пользователю. */
fun isNoFood(result: MealAnalysisResult): Boolean =
    result.items.isEmpty() && result.calories <= 0.0 &&
        result.name.contains("не найден", ignoreCase = true)

/** Сообщение об ошибке «еды нет» — его ловит воркер и не ретраит. */
const val NO_FOOD_ERROR = "Еда на фото не найдена"
