package com.nutrilens.app.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WorkoutCsvTest {
    private val header =
        "\"title\",\"start_time\",\"end_time\",\"description\",\"exercise_title\",\"superset_id\",\"exercise_notes\",\"set_index\",\"set_type\",\"weight_kg\",\"reps\",\"distance_km\",\"duration_seconds\",\"rpe\""

    @Test
    fun parsesHevyRussianExport() {
        val csv = """
            $header
            "День 1, верх","15 сент. 2026, 09:15","15 сент. 2026, 10:17","","Жим лежа (Штанга)",,"",0,"warmup",0,12,,,
            "День 1, верх","15 сент. 2026, 09:15","15 сент. 2026, 10:17","","Жим лежа (Штанга)",,"",1,"normal",62.5,5,,,8.5
            "День 2","3 июля 2026, 18:00","3 июля 2026, 19:00","","Планка",,"",0,"normal",,,,45,
            "День 3","12 мая 2026, 08:05","12 мая 2026, 09:00","","Подтягивания (С помощью)",,"",1,"normal",54,12,,,
        """.trimIndent()
        val r = WorkoutCsv.parse("﻿" + csv)
        assertEquals(0, r.skipped)
        assertEquals(4, r.rows.size)
        val bench = r.rows[1]
        assertEquals(LocalDateTime.of(2026, 9, 15, 9, 15), bench.start)
        assertEquals("День 1, верх", bench.workoutTitle) // запятая внутри кавычек
        assertEquals(WorkoutCsv.SetType.NORMAL, bench.type)
        assertEquals(62.5, bench.weightKg!!, 1e-9)
        assertEquals(5, bench.reps)
        assertEquals(8.5, bench.rpe!!, 1e-9)
        assertEquals(WorkoutCsv.SetType.WARMUP, r.rows[0].type)
        assertEquals(45, r.rows[2].durationSec)
        assertNull(r.rows[2].weightKg)
        assertEquals(LocalDate.of(2026, 5, 12), r.rows[3].date)
        assertEquals(3, r.workoutDates.size)
    }

    @Test
    fun parsesTabsAndEnglishDates() {
        val csv = "title\tstart_time\texercise_title\tset_type\tweight_kg\treps\n" +
            "Legs\t15 Sep 2026, 09:15\tSquat (Barbell)\tnormal\t100\t5\n" +
            "Legs\tSep 22, 2026, 18:30\tSquat (Barbell)\tfailure\t100\t6\n" +
            "Legs\t2026-09-29T07:00:00\tSquat (Barbell)\tdropset\t80\t10\n" +
            "Legs\tнепонятно\tSquat (Barbell)\tnormal\t80\t10\n"
        val r = WorkoutCsv.parse(csv)
        assertEquals(3, r.rows.size)
        assertEquals(1, r.skipped)
        assertEquals(LocalDate.of(2026, 9, 22), r.rows[1].date)
        assertEquals(WorkoutCsv.SetType.FAILURE, r.rows[1].type)
        assertEquals(LocalDate.of(2026, 9, 29), r.rows[2].date)
    }

    @Test
    fun allRussianMonths() {
        val words = listOf("янв.", "февр.", "мар.", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")
        words.forEachIndexed { i, w ->
            assertEquals(i + 1, WorkoutCsv.parseDateTime("1 $w 2026, 10:00")!!.monthValue)
        }
    }

    @Test(expected = WorkoutCsv.FormatException::class)
    fun rejectsOtherFiles() {
        WorkoutCsv.parse("date,kcal\n2026-01-01,2000\n")
    }
}

class StrengthAnalyzerTest {
    private val today = LocalDate.of(2026, 11, 29)

    private fun set(date: LocalDate, ex: String, w: Double?, reps: Int?, type: WorkoutCsv.SetType = WorkoutCsv.SetType.NORMAL, sec: Int? = null) =
        WorkoutCsv.SetRow(date.atTime(18, 0), "T", ex, 1, type, w, reps, null, sec, null)

    /**
     * Две тренировки в неделю 8 недель. [factor] — во сколько раз последние 2 недели
     * отличаются от прежних (1.0 — держится, 0.88 — минус 12%).
     */
    private fun program(factor: (String) -> Double): List<WorkoutCsv.SetRow> {
        val rows = mutableListOf<WorkoutCsv.SetRow>()
        val base = mapOf("Жим" to 60.0, "Присед" to 80.0, "Тяга" to 100.0, "Махи" to 8.0)
        var d = today.minusDays(55)
        while (!d.isAfter(today)) {
            val recent = !d.isBefore(today.minusDays(StrengthAnalyzer.RECENT_DAYS - 1))
            for ((ex, w) in base) {
                val k = if (recent) factor(ex) else 1.0
                rows += set(d, ex, w * k, 5)
                rows += set(d, ex, w * k * 0.9, 5)
            }
            d = d.plusDays(if (d.dayOfWeek.value == 1) 3 else 4)
        }
        return rows
    }

    private fun analyze(rows: List<WorkoutCsv.SetRow>, prev: List<Double?> = emptyList(), deload: Boolean = false, settings: StrengthAnalyzer.Settings = StrengthAnalyzer.Settings()) =
        StrengthAnalyzer.analyze(rows, today, blockStart = null, bodyweightAt = { 94.0 }, settings = settings, previousIndices = prev, deload = deload)

    @Test
    fun stableIsGreen() {
        val r = analyze(program { 1.0 })
        assertEquals(StrengthAnalyzer.Signal.GREEN, r.signal)
        assertEquals(0.0, r.indexPercent!!, 0.01)
        assertEquals(4, r.countedExercises)
    }

    @Test
    fun bigDropIsRed() {
        val r = analyze(program { 0.88 })
        assertEquals(StrengthAnalyzer.Signal.RED, r.signal)
        assertEquals(-12.0, r.indexPercent!!, 0.01)
    }

    @Test
    fun moderateDropNeedsTwoCheckIns() {
        val rows = program { 0.94 }
        assertEquals(StrengthAnalyzer.Signal.GREEN, analyze(rows).signal)
        assertEquals(StrengthAnalyzer.Signal.YELLOW, analyze(rows, prev = listOf(-6.0)).signal)
        // Жёлтый три недели подряд — уже красный.
        assertEquals(StrengthAnalyzer.Signal.RED, analyze(rows, prev = listOf(-6.0, -5.5, -7.0)).signal)
        // Цепочка прерывается неделей без спада.
        assertEquals(StrengthAnalyzer.Signal.YELLOW, analyze(rows, prev = listOf(-6.0, 1.0, -7.0, -7.0)).signal)
    }

    @Test
    fun oneBadExerciseDoesNotMoveMedian() {
        val r = analyze(program { if (it == "Махи") 0.7 else 1.0 })
        assertEquals(StrengthAnalyzer.Signal.GREEN, r.signal)
        assertEquals(0.0, r.indexPercent!!, 0.01)
    }

    @Test
    fun reEntryAfterGapIsNotJudged() {
        // Тренировки 3 месяца назад, потом 2 месяца пусто, потом 2 недели слабее.
        val old = program { 1.0 }.map { it.copy(start = it.start.minusDays(90)) }
        val back = listOf(today.minusDays(10), today.minusDays(6), today.minusDays(2)).flatMap { d ->
            listOf("Жим", "Присед", "Тяга").map { set(d, it, 40.0, 8) }
        }
        val r = analyze(old + back)
        assertEquals(StrengthAnalyzer.Signal.NONE, r.signal)
        assertNotNull(r.reEntryUntil)
        assertEquals(today.minusDays(10).plusDays(StrengthAnalyzer.REENTRY_DAYS), r.reEntryUntil)
    }

    @Test
    fun deloadAndTooFewExercisesAreNotJudged() {
        assertEquals(StrengthAnalyzer.Signal.NONE, analyze(program { 0.8 }, deload = true).signal)
        val few = program { 0.8 }.filter { it.exercise == "Жим" || it.exercise == "Присед" }
        val r = analyze(few)
        assertEquals(StrengthAnalyzer.Signal.NONE, r.signal)
        assertNull(r.indexPercent)
    }

    @Test
    fun noRecentWorkouts() {
        val r = analyze(program { 1.0 }.filter { it.date.isBefore(today.minusDays(20)) })
        assertEquals(StrengthAnalyzer.Signal.NONE, r.signal)
    }

    @Test
    fun assistedUsesBodyweightMinusAssist() {
        val rows = listOf(set(today, "Подтягивания (С помощью)", 54.0, 12))
        val ex = StrengthAnalyzer.analyze(rows, today, null, { 94.0 }).exercises.single()
        assertEquals(StrengthAnalyzer.Kind.ASSISTED, ex.kind)
        // (94 − 54) × (1 + 12/30) = 56
        assertEquals(56.0, ex.sessions.single().value, 1e-9)
        assertEquals("−54 × 12", ex.sessions.single().bestSet)
        // Меньше противовес — больше результат.
        val lighter = StrengthAnalyzer.analyze(listOf(set(today, "Подтягивания (С помощью)", 44.0, 12)), today, null, { 94.0 })
        assertTrue(lighter.exercises.single().sessions.single().value > 56.0)
    }

    @Test
    fun kindsAndWarmupsAndMerges() {
        val rows = listOf(
            set(today, "Планка", null, null, sec = 45),
            set(today, "Отжимания", null, 25),
            set(today, "Махи в стороны (Гантели)", 7.0, 15),
            set(today.minusDays(3), "Боковые подъемы (Гантель)", 7.0, 12),
            set(today, "Жим", 100.0, 1, type = WorkoutCsv.SetType.WARMUP),
            set(today, "Жим", 60.0, 5)
        )
        val settings = StrengthAnalyzer.Settings(
            merges = mapOf("Махи в стороны (Гантели)" to "Боковые подъемы (Гантель)"),
            pinned = listOf("Жим")
        )
        val r = StrengthAnalyzer.analyze(rows, today, null, { 80.0 }, settings)
        val byName = r.exercises.associateBy { it.name }
        assertEquals(StrengthAnalyzer.Kind.DURATION, byName.getValue("Планка").kind)
        assertEquals(StrengthAnalyzer.Kind.REPS, byName.getValue("Отжимания").kind)
        assertEquals(2, byName.getValue("Боковые подъемы (Гантель)").sessions.size)
        // Разминка 100 × 1 не считается: лучший подход — 60 × 5.
        assertEquals("60 × 5", byName.getValue("Жим").sessions.single().bestSet)
        assertEquals(listOf("Жим"), r.shown.map { it.name })
    }

    @Test
    fun weightedMedian() {
        assertEquals(-2.0, StrengthAnalyzer.weightedMedian(listOf(-20.0 to 1.0, -2.0 to 6.0, 5.0 to 3.0)), 1e-9)
        assertEquals(5.0, StrengthAnalyzer.weightedMedian(listOf(-20.0 to 1.0, -2.0 to 2.0, 5.0 to 9.0)), 1e-9)
    }
}
