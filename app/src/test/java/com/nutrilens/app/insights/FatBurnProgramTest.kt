package com.nutrilens.app.insights

import com.nutrilens.app.data.FatBurnProgramCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Этапы программы 3.24: те же четыре сценария, что проверялись при разработке. */
class FatBurnProgramTest {
    private val start = LocalDate.of(2026, 11, 15)

    /** Еженедельные чек-ины; возвращает события по неделям. */
    private fun run(
        p0: FatBurnProgram.Program,
        weeks: Int,
        fatAt: (Int) -> Double,
        flatAt: (Int) -> Int = { 0 }
    ): Pair<FatBurnProgram.Program, List<Pair<Int, FatBurnProgram.Event>>> {
        var p = p0
        val events = mutableListOf<Pair<Int, FatBurnProgram.Event>>()
        for (w in 1..weeks) {
            val t = FatBurnProgram.onCheckIn(p, start.plusWeeks(w.toLong()), fatAt(w), flatAt(w), p0.startWeight, true)
            t.event?.let { events += w to it }
            p = t.program
            if (p.completed) break
        }
        return p to events
    }

    @Test fun `обычный ход — блоки кончаются по сроку, между ними перерыв`() {
        val p0 = FatBurnProgram.create(start, 22.0, 15.0, 85.0, true)
        assertEquals(listOf(8, 8, 3), p0.phases.filter { it.type == FatBurnProgram.PhaseType.BLOCK }.map { it.plannedWeeks })
        assertEquals(listOf(19.5, 17.5, 15.0), p0.phases.filter { it.type == FatBurnProgram.PhaseType.BLOCK }.map { it.targetFat })
        assertTrue(p0.inActiveBlock)
        val (p, events) = run(p0, 20, { w -> 22.0 - 0.22 * w })
        assertEquals(
            listOf(
                8 to FatBurnProgram.Event.BLOCK_TIME, 10 to FatBurnProgram.Event.BREAK_DONE,
                18 to FatBurnProgram.Event.BLOCK_TIME, 20 to FatBurnProgram.Event.BREAK_DONE
            ),
            events
        )
        assertEquals(3, FatBurnProgram.blockOrdinal(p, p.currentPhase!!))
    }

    @Test fun `промежуточная цель раньше срока — сразу перерыв`() {
        val (p, events) = run(FatBurnProgram.create(start, 25.0, 13.0, 90.0, true), 3, { w -> 25.0 - 1.1 * w })
        assertEquals(listOf(3 to FatBurnProgram.Event.BLOCK_TARGET), events)
        assertEquals(FatBurnProgram.PhaseType.BREAK, p.currentPhase?.type)
        assertFalse(p.inActiveBlock)
    }

    @Test fun `плато три недели — перерыв досрочно`() {
        val (p, events) = run(
            FatBurnProgram.create(start, 25.0, 13.0, 90.0, true), 5,
            { w -> if (w <= 2) 25.0 - 0.5 * w else 24.0 },
            { w -> if (w >= 3) w - 2 else 0 }
        )
        assertEquals(listOf(5 to FatBurnProgram.Event.PLATEAU), events)
        assertEquals(FatBurnProgram.EndReason.PLATEAU, p.phases[0].endReason)
    }

    @Test fun `сила падает второй чек-ин подряд — перерыв досрочно`() {
        val p0 = FatBurnProgram.create(start, 25.0, 13.0, 90.0, true)
        val t = FatBurnProgram.onCheckIn(p0, start.plusWeeks(4), 23.5, 0, 88.0, true, strengthDrop = true)
        assertEquals(FatBurnProgram.Event.STRENGTH, t.event)
        assertEquals(FatBurnProgram.PhaseType.BREAK, t.program.currentPhase?.type)
        assertEquals(FatBurnProgram.EndReason.STRENGTH, t.program.phases[0].endReason)
        // Причина переживает сохранение.
        assertEquals(t.program, FatBurnProgramCodec.decode(FatBurnProgramCodec.encode(t.program)))
        // В перерыве сигнал силы уже ничего не меняет.
        val inBreak = FatBurnProgram.onCheckIn(t.program, start.plusWeeks(5), 23.4, 0, 88.0, true, strengthDrop = true)
        assertEquals(null, inBreak.event)
    }

    @Test fun `итоговая цель — удержание — программа завершена`() {
        val (p, events) = run(FatBurnProgram.create(start, 16.0, 13.0, 80.0, true), 16, { w -> (16.0 - 0.4 * w).coerceAtLeast(13.2) })
        assertEquals(FatBurnProgram.Event.FINAL_TARGET, events.first { it.second == FatBurnProgram.Event.FINAL_TARGET }.second)
        assertEquals(FatBurnProgram.Event.MAINTENANCE_DONE, events.last().second)
        assertTrue(p.completed)
        assertTrue(p.awaitingChoice)
        assertEquals(p, FatBurnProgramCodec.decode(FatBurnProgramCodec.encode(p)))
    }

    @Test fun `пауза не идёт в срок этапа`() {
        var p = FatBurnProgram.create(start, 22.0, 15.0, 85.0, true)
        p = FatBurnProgram.pause(p, start.plusDays(10))
        assertFalse(p.inActiveBlock)
        p = FatBurnProgram.resume(p, start.plusDays(20))
        assertEquals(17, FatBurnProgram.daysInPhase(p, start.plusDays(27)))
    }
}
