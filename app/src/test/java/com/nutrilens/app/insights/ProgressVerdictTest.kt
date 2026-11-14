package com.nutrilens.app.insights

import com.nutrilens.app.insights.ProgressVerdict.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressVerdictTest {
    private val today = LocalDate.of(2026, 12, 20)

    private fun week(daysAgo: Long, rate: Double?, diary: Int = 7, protein: Int = 6, workouts: Int = 2, strength: String? = "green") =
        ProgressVerdict.Week(today.minusDays(daysAgo), rate, diary, protein, 7, workouts, 2, strength, if (strength == null) null else 1.0)

    private fun input(
        weeks: List<ProgressVerdict.Week>,
        waist: List<Pair<LocalDate, Double>> = listOf(today.minusDays(28) to 96.0, today.minusDays(14) to 95.0, today to 94.0),
        maintenance: Boolean = false
    ) = ProgressVerdict.Input(today, 0.6, weeks, waist, today.minusWeeks(6), 94.0, 91.5, maintenance)

    @Test fun `всё по плану — хорошо`() {
        val r = ProgressVerdict.evaluate(input(listOf(week(0, 0.6), week(7, 0.55))))
        assertEquals(Level.GOOD, r.level)
        assertNull(r.focus)
        assertTrue(r.sinceStart!!.contains("−2,5 кг"))
        assertTrue(r.sinceStart!!.contains("6 недель"))
    }

    @Test fun `вес почти не уходит и талия стоит — плохо, с подсказкой про дневник`() {
        val r = ProgressVerdict.evaluate(
            input(listOf(week(0, 0.05), week(7, 0.1)), waist = listOf(today.minusDays(28) to 95.0, today to 95.2))
        )
        assertEquals(Level.BAD, r.level)
        assertTrue(r.focus!!.contains("записываешь"))
    }

    @Test fun `сила падает — плохо, даже если вес уходит`() {
        val r = ProgressVerdict.evaluate(input(listOf(week(0, 0.7, strength = "red"), week(7, 0.6))))
        assertEquals(Level.BAD, r.level)
        assertTrue(r.focus!!.contains("мышцам"))
    }

    @Test fun `привычки проседают — внимание`() {
        val r = ProgressVerdict.evaluate(input(listOf(week(0, 0.6, diary = 3, protein = 2), week(7, 0.6, diary = 3, protein = 2))))
        assertEquals(Level.WATCH, r.level)
    }

    @Test fun `перерыв — вес стоит, и это хорошо`() {
        val r = ProgressVerdict.evaluate(
            input(listOf(week(0, 0.0), week(7, 0.0)), waist = listOf(today.minusDays(21) to 90.0, today to 90.3), maintenance = true)
        )
        assertEquals(Level.GOOD, r.level)
        assertTrue(r.headline.contains("держим"))
    }

    @Test fun `без чек-инов — пока рано`() {
        val r = ProgressVerdict.evaluate(input(emptyList(), waist = emptyList()))
        assertEquals(Level.UNKNOWN, r.level)
    }
}
