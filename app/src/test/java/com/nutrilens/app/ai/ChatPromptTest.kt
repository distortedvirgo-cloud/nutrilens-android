package com.nutrilens.app.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ChatPromptTest {

    @Test
    fun `системный промпт чата знает остаток и БЖУ`() {
        val prompt = GeminiTools.buildChatSystemInstruction(
            dailyGoal = 2000.0,
            userContext = "бегаю по утрам",
            todayMeals = listOf("Овсянка" to 350, "Курица с рисом" to 600),
            todayTotal = 950,
            todayMacros = Triple(55.0, 20.0, 120.0),
            macroGoals = Triple(130.0, 60.0, 220.0),
            recentDays = "2026-10-20: 1900 ккал",
            weightKg = 72.5,
            now = LocalDateTime.of(2026, 10, 21, 18, 5)
        )
        assertTrue(prompt.contains("осталось 1050"))
        assertTrue(prompt.contains("Б 55/130 г"))
        assertTrue(prompt.contains("18:05"))
        assertTrue(prompt.contains("Овсянка — 350 ккал"))
        assertTrue(prompt.contains("2026-10-20: 1900 ккал"))
    }

    @Test
    fun `перебор формулируется явно`() {
        val prompt = GeminiTools.buildChatSystemInstruction(
            dailyGoal = 1800.0, userContext = "", todayMeals = emptyList(), todayTotal = 2000
        )
        assertTrue(prompt.contains("перебор 200"))
        assertTrue(prompt.contains("пока ничего"))
    }
}
