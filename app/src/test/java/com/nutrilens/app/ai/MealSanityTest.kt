package com.nutrilens.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealSanityTest {

    private fun item(
        name: String = "Рис",
        weight: Double = 0.0,
        density: Double = 0.0,
        kcal: Double = 0.0,
        p: Double = 0.0,
        f: Double = 0.0,
        c: Double = 0.0
    ) = AnalyzedItemResult(
        name = name, weightG = weight, calorieDensity = density,
        calories = kcal, protein = p, fat = f, carbs = c
    )

    @Test
    fun `калории без значения восстанавливаются по БЖУ`() {
        val fixed = sanitizeItem(item(p = 10.0, f = 5.0, c = 20.0))
        assertEquals(165.0, fixed.calories, 0.01)
    }

    @Test
    fun `калории без значения и без БЖУ — из веса и плотности`() {
        val fixed = sanitizeItem(item(weight = 200.0, density = 130.0))
        assertEquals(260.0, fixed.calories, 0.01)
    }

    @Test
    fun `вес восстанавливается из калорий и плотности`() {
        val fixed = sanitizeItem(item(kcal = 260.0, density = 130.0))
        assertEquals(200.0, fixed.weightG, 0.01)
    }

    @Test
    fun `отрицательные и нечисловые значения обнуляются`() {
        val fixed = sanitizeItem(item(kcal = -50.0, p = Double.NaN, f = -1.0, c = 10.0))
        assertTrue(fixed.calories >= 0.0)
        assertEquals(0.0, fixed.protein, 0.0)
        assertEquals(0.0, fixed.fat, 0.0)
        // Калории были отрицательными → пересчитаны по оставшимся углеводам.
        assertEquals(40.0, fixed.calories, 0.01)
    }

    @Test
    fun `итоги сверяются с суммой продуктов`() {
        val result = MealAnalysisResult(
            name = "Обед", calories = 900.0, protein = 10.0, fat = 10.0, carbs = 10.0,
            items = listOf(
                item("Курица", kcal = 330.0, p = 62.0, f = 7.0, c = 0.0),
                item("Рис", kcal = 260.0, p = 5.0, f = 0.6, c = 57.0)
            )
        )
        val fixed = finalizeMeal(result)
        assertEquals(590.0, fixed.calories, 0.01)
        assertEquals(67.0, fixed.protein, 0.01)
    }

    @Test
    fun `итоги без продуктов — калории по Этуотеру`() {
        val fixed = finalizeMeal(MealAnalysisResult(name = "Йогурт", protein = 10.0, fat = 3.0, carbs = 12.0))
        assertEquals(115.0, fixed.calories, 0.01)
    }

    @Test
    fun `пустое имя собирается из продуктов`() {
        val fixed = finalizeMeal(
            MealAnalysisResult(name = " ", items = listOf(item("Омлет", kcal = 200.0), item("Кофе", kcal = 5.0)))
        )
        assertEquals("Омлет, Кофе", fixed.name)
    }

    @Test
    fun `уверенность снижается, если калории противоречат БЖУ`() {
        val result = MealAnalysisResult(
            name = "Салат", calories = 800.0, protein = 5.0, fat = 5.0, carbs = 10.0,
            confidenceScore = 9.0
        )
        assertEquals(5.0, finalizeMeal(result).confidenceScore, 0.0)
    }

    @Test
    fun `согласованный расчёт сохраняет уверенность, шкалы в границах`() {
        val result = MealAnalysisResult(
            name = "Курица", calories = 330.0, protein = 62.0, fat = 7.0, carbs = 0.0,
            confidenceScore = 14.0, healthScore = 140.0
        )
        val fixed = finalizeMeal(result)
        assertEquals(10.0, fixed.confidenceScore, 0.0)
        assertEquals(100.0, fixed.healthScore!!, 0.0)
    }

    @Test
    fun `ответ «еда не найдена» распознаётся`() {
        assertTrue(isNoFood(MealAnalysisResult(name = "Еда не найдена")))
        assertFalse(isNoFood(MealAnalysisResult(name = "Суп", calories = 120.0)))
    }

    @Test
    fun `строковые поля принимаются и массивом`() {
        val json = """{"name": ["Суп", "хлеб"], "aiThoughts": ["Суп 300 г", "Хлеб 40 г"], "calories": 300}"""
        val parsed = mealJson.decodeFromString(MealAnalysisResult.serializer(), json)
        assertEquals("Суп\nхлеб", parsed.name)
        assertEquals("Суп 300 г\nХлеб 40 г", parsed.aiThoughts)
    }

    @Test
    fun `null и мусор в числах не роняют разбор`() {
        val json = """{"name": "Чай", "calories": null, "items": [{"name": "Чай", "calories": null, "protein": 0}]}"""
        val parsed = finalizeMeal(mealJson.decodeFromString(MealAnalysisResult.serializer(), json))
        assertEquals(0.0, parsed.calories, 0.0)
        assertEquals("Чай", parsed.name)
    }

    @Test
    fun `обёртка json-блока срезается`() {
        assertEquals("{\"a\": 1}", stripJsonFence("```json\n{\"a\": 1}\n```"))
        assertEquals("{\"a\": 1}", stripJsonFence("  {\"a\": 1} "))
    }

    @Test
    fun `промпт анализа задаёт порядок полей и самопроверку`() {
        val prompt = buildMealAnalysisPrompt("", "борщ 300 г", photoCount = 0)
        assertTrue(prompt.contains("САМОПРОВЕРКА"))
        assertTrue(prompt.indexOf("\"reasoning\"") < prompt.indexOf("\"calories\""))
        assertTrue(prompt.contains("борщ 300 г"))
    }
}
