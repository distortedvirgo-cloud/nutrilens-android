package com.nutrilens.app.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CycleTest {
    private val d0 = LocalDate.of(2026, 9, 1)

    @Test fun averageAndPrediction() {
        assertNull(Cycle.nextStart(emptyList()))
        assertEquals(28, Cycle.averageLength(listOf(d0)))
        val starts = listOf(d0, d0.plusDays(30), d0.plusDays(60))
        assertEquals(30, Cycle.averageLength(starts))
        assertEquals(d0.plusDays(90), Cycle.nextStart(starts))
        // Пропущенная отметка (60 дней) — не цикл, в среднее не идёт.
        assertEquals(30, Cycle.averageLength(listOf(d0, d0.plusDays(30), d0.plusDays(90))))
    }

    @Test fun duplicatesAreOnePeriod() {
        val starts = listOf(d0, d0.plusDays(2), d0.plusDays(29))
        assertEquals(listOf(d0, d0.plusDays(29)), Cycle.normalize(starts))
        assertEquals(29, Cycle.averageLength(starts))
    }

    @Test fun waterDays() {
        val starts = listOf(d0, d0.plusDays(28))
        val next = d0.plusDays(56)
        assertTrue(Cycle.isWaterDay(next.minusDays(1), starts))
        assertTrue(Cycle.isWaterDay(next.minusDays(7), starts))
        assertFalse(Cycle.isWaterDay(next.minusDays(8), starts))
        // Первые 3 дня отмеченных месячных — вода, четвёртый уже нет.
        assertTrue(Cycle.isWaterDay(d0.plusDays(28), starts))
        assertTrue(Cycle.isWaterDay(d0.plusDays(30), starts))
        assertFalse(Cycle.isWaterDay(d0.plusDays(31), starts))
        // Ожидаемый день сам по себе — не «отмеченные» месячные.
        assertFalse(Cycle.isWaterDay(next, starts))
        assertFalse(Cycle.isWaterDay(d0.plusDays(40), starts))
    }

    @Test fun checkInPhase() {
        val starts = listOf(d0, d0.plusDays(28))
        assertTrue(Cycle.checkInInWaterPhase(d0.plusDays(53), starts))
        assertFalse(Cycle.checkInInWaterPhase(d0.plusDays(42), starts))
        // День после водной недели, но 2 из 3 прошлых дней — вода.
        assertTrue(Cycle.checkInInWaterPhase(d0.plusDays(31), starts))
        assertFalse(Cycle.checkInInWaterPhase(d0.plusDays(42), emptyList()))
    }

    @Test fun asking() {
        val starts = listOf(d0, d0.plusDays(28))
        val next = d0.plusDays(56)
        assertFalse(Cycle.shouldAsk(next.minusDays(1), starts, null))
        assertTrue(Cycle.shouldAsk(next, starts, null))
        assertFalse(Cycle.shouldAsk(next, starts, notYetOn = next))
        assertTrue(Cycle.shouldAsk(next.plusDays(1), starts, notYetOn = next))
        assertFalse(Cycle.shouldAsk(next.plusDays(11), starts, null))
        assertFalse(Cycle.shouldAsk(next, emptyList(), null))
    }
}
