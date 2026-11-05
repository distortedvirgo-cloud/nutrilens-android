package com.nutrilens.app.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LeaderboardTest {

    @Test fun `публичный id совпадает с прокси`() {
        // То же значение считает leaderboard-worker: sha256("nutrilens:" + id).slice(0, 16).
        assertEquals("c51688eaa5faa9c6", LeaderboardSync.publicId("aaaaaaaaaaaaaaaa"))
    }

    @Test fun `очки дня`() {
        assertEquals(100, LeaderboardMath.dayScore(2000.0, 2000.0))
        assertEquals(90, LeaderboardMath.dayScore(2200.0, 2000.0))
        assertEquals(null, LeaderboardMath.dayScore(100.0, 0.0))
    }

    @Test fun `дубли и старые файлы скрываются`() {
        val today = LocalDate.now().toString()
        val old = LocalDate.now().minusDays(40).toString()
        val entries = listOf(
            LeaderboardEntry(id = "a", nickname = "Дишка", days = mapOf(today to LeaderboardDay(1900.0, 2000.0)), updatedAt = 2),
            LeaderboardEntry(id = "a", nickname = "Диана", days = mapOf(today to LeaderboardDay(1000.0, 2000.0)), updatedAt = 1),
            LeaderboardEntry(id = "", nickname = "Диана", days = mapOf(old to LeaderboardDay(1900.0, 2000.0))),
            LeaderboardEntry(id = "", nickname = "мишя", days = mapOf(today to LeaderboardDay(1500.0, 2000.0)))
        )
        val names = LeaderboardMath.dedupe(entries).map { it.nickname }.sorted()
        assertEquals(listOf("Дишка", "мишя"), names)
    }
}
