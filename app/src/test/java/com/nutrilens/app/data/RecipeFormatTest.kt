package com.nutrilens.app.data

import com.nutrilens.app.ai.RecipeAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeFormatTest {

    @Test fun `штучные количества — дробями`() {
        assertEquals("1½", RecipeFormat.amount(1.5, "шт"))
        assertEquals("¼", RecipeFormat.amount(0.25, "ч. л."))
        assertEquals("2", RecipeFormat.amount(2.02, "зубчика"))
    }

    @Test fun `граммы округляются`() {
        assertEquals("250", RecipeFormat.amount(248.0, "г"))
        assertEquals("15", RecipeFormat.amount(15.4, "г"))
        assertEquals("2,5", RecipeFormat.amount(2.5, "г"))
    }

    @Test fun `пересчёт порций и по вкусу`() {
        val i = RecipeIngredient(name = "Спагетти", amount = 200.0, unit = "г")
        assertEquals("300 г", RecipeFormat.ingredientAmount(i, 1.5))
        assertEquals("по вкусу", RecipeFormat.ingredientAmount(RecipeIngredient(name = "Соль", amount = null, unit = "по вкусу"), 2.0))
    }

    @Test fun `ссылки YouTube в канонический вид`() {
        assertEquals("https://www.youtube.com/watch?v=QFwCbCmN5wY", RecipeAi.youtubeWatchUrl("https://www.youtube.com/shorts/QFwCbCmN5wY"))
        assertEquals("https://www.youtube.com/watch?v=abcDEF12345", RecipeAi.youtubeWatchUrl("https://youtu.be/abcDEF12345?si=x"))
        assertNull(RecipeAi.youtubeWatchUrl("https://example.com/recipe"))
        assertEquals("tiktok", RecipeAi.kindOf("https://vm.tiktok.com/ZMabc/"))
        assertEquals("instagram", RecipeAi.kindOf("https://www.instagram.com/reel/abc/"))
    }

    @Test fun `разбор ответа ИИ — калории сверяются с ингредиентами`() {
        val raw = """{"is_recipe":true,"title":"Паста","servings":2,"category":"dinner","difficulty":"easy",
            "ingredients":[{"name":"Спагетти","emoji":"🍝","amount":200,"unit":"г","grams":200,"calories":700},
                           {"name":"Томаты","emoji":"🍅","amount":250,"unit":"г","grams":250,"calories":50}],
            "steps":[{"text":"Сварить","minutes":9},{"text":"Смешать","minutes":null}],
            "nutrition_per_serving":{"calories":100,"protein":12,"fat":5,"carbs":70},"cover_time_sec":12.5}"""
        val (r, cover) = RecipeAi.parse(raw, RecipeAi.Input(kind = "video"))
        assertEquals(375.0, r.nutrition.calories, 0.5) // 750 ккал / 2 порции, а не 100 из ответа
        assertEquals(9, r.totalMinutes)
        assertEquals(12.5, cover!!, 0.01)
        assertEquals("🍝", r.ingredients.first().emoji)
    }

    @Test fun `нет рецепта — понятная ошибка`() {
        val e = runCatching { RecipeAi.parse("""{"is_recipe":false,"reason":"это видео про котов"}""", RecipeAi.Input(kind = "video")) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("котов"))
    }
}
