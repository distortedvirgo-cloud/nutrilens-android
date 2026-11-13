package com.nutrilens.app.training

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Разбор экспорта тренировок из Hevy (Профиль → Настройки → Экспорт данных →
 * «Экспорт тренировок»). Чистый Kotlin без Android.
 *
 * Формат: одна строка — один подход. Колонки по заголовку, порядок не важен:
 * title, start_time, end_time, exercise_title, set_index, set_type, weight_kg,
 * reps, distance_km, duration_seconds, rpe. Разделитель — запятая или табуляция
 * (у Hevy бывают оба). Даты — на языке приложения: «15 сент. 2026, 09:15»,
 * «15 Sep 2026, 09:15»; на всякий случай понимаем и ISO.
 */
object WorkoutCsv {

    enum class SetType { WARMUP, NORMAL, FAILURE, DROPSET;
        /** Рабочий подход — всё, кроме разминки. */
        val working: Boolean get() = this != WARMUP
    }

    data class SetRow(
        val start: LocalDateTime,
        val workoutTitle: String,
        val exercise: String,
        val setIndex: Int,
        val type: SetType,
        val weightKg: Double?,
        val reps: Int?,
        val distanceKm: Double?,
        val durationSec: Int?,
        val rpe: Double?
    ) {
        val date: LocalDate get() = start.toLocalDate()
    }

    data class Result(
        val rows: List<SetRow>,
        /** Строки, которые не удалось разобрать (битая дата, нет упражнения). */
        val skipped: Int
    ) {
        /** Даты, в которые была хотя бы одна тренировка. */
        val workoutDates: Set<LocalDate> get() = rows.mapTo(sortedSetOf()) { it.date }
    }

    class FormatException(message: String) : Exception(message)

    private val REQUIRED = listOf("start_time", "exercise_title")

    /** Разбирает весь файл. Бросает [FormatException], если это не экспорт тренировок. */
    fun parse(text: String): Result {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.substringBefore('\n')
        val delimiter = if (firstLine.count { it == '\t' } > firstLine.count { it == ',' }) '\t' else ','
        val records = splitRecords(clean, delimiter)
        if (records.isEmpty()) throw FormatException("Файл пустой")
        val header = records.first().map { it.trim().lowercase() }
        val missing = REQUIRED.filter { it !in header }
        if (missing.isNotEmpty()) {
            throw FormatException("Это не похоже на экспорт тренировок: нет колонок ${missing.joinToString()}")
        }
        val col = header.withIndex().associate { it.value to it.index }
        fun List<String>.at(name: String): String? = col[name]?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        val rows = ArrayList<SetRow>(records.size)
        var skipped = 0
        for (r in records.drop(1)) {
            if (r.all { it.isBlank() }) continue
            val start = r.at("start_time")?.let(::parseDateTime)
            val exercise = r.at("exercise_title")
            if (start == null || exercise == null) {
                skipped++
                continue
            }
            rows += SetRow(
                start = start,
                workoutTitle = r.at("title").orEmpty(),
                exercise = exercise,
                setIndex = r.at("set_index")?.toIntOrNull() ?: 0,
                type = parseSetType(r.at("set_type")),
                weightKg = r.at("weight_kg")?.let(::parseNumber),
                reps = r.at("reps")?.let(::parseNumber)?.toInt(),
                distanceKm = r.at("distance_km")?.let(::parseNumber),
                durationSec = r.at("duration_seconds")?.let(::parseNumber)?.toInt(),
                rpe = r.at("rpe")?.let(::parseNumber)
            )
        }
        return Result(rows, skipped)
    }

    private fun parseSetType(s: String?): SetType = when (s?.lowercase()) {
        "warmup" -> SetType.WARMUP
        "failure" -> SetType.FAILURE
        "dropset" -> SetType.DROPSET
        else -> SetType.NORMAL
    }

    private fun parseNumber(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    /** Префиксы месяцев: русские родительные и сокращения, английские. Порядок важен: «мар» до «май» не пересекается. */
    private val MONTHS: List<Pair<String, Int>> = listOf(
        "янв" to 1, "фев" to 2, "мар" to 3, "апр" to 4, "мая" to 5, "май" to 5, "июн" to 6, "июл" to 7,
        "авг" to 8, "сен" to 9, "окт" to 10, "ноя" to 11, "дек" to 12,
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7,
        "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    private val DAY_MONTH_YEAR = Regex("""^(\d{1,2})\s+([\p{L}]+)\.?\s+(\d{4}),?\s+(\d{1,2}):(\d{2})""")
    private val MONTH_DAY_YEAR = Regex("""^([\p{L}]+)\.?\s+(\d{1,2}),?\s+(\d{4}),?\s+(\d{1,2}):(\d{2})""")
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})""")

    private fun month(word: String): Int? {
        val w = word.lowercase()
        return MONTHS.firstOrNull { w.startsWith(it.first) }?.second
    }

    /** «15 сент. 2026, 09:15», «15 Sep 2026, 09:15», «Sep 15, 2026, 09:15», «2026-09-15 09:15». */
    fun parseDateTime(s: String): LocalDateTime? = runCatching {
        val t = s.trim()
        DAY_MONTH_YEAR.find(t)?.let { m ->
            val (d, mon, y, h, min) = m.destructured
            return@runCatching LocalDateTime.of(y.toInt(), month(mon) ?: return@runCatching null, d.toInt(), h.toInt(), min.toInt())
        }
        MONTH_DAY_YEAR.find(t)?.let { m ->
            val (mon, d, y, h, min) = m.destructured
            return@runCatching LocalDateTime.of(y.toInt(), month(mon) ?: return@runCatching null, d.toInt(), h.toInt(), min.toInt())
        }
        ISO.find(t)?.let { m ->
            val (y, mo, d, h, min) = m.destructured
            return@runCatching LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), min.toInt())
        }
        null
    }.getOrNull()

    /** CSV по RFC 4180: кавычки, удвоенные кавычки внутри, переводы строк внутри кавычек. */
    internal fun splitRecords(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"'); i++
                    } else inQuotes = false
                } else sb.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                delimiter -> { fields += sb.toString(); sb.setLength(0) }
                '\r' -> Unit
                '\n' -> {
                    fields += sb.toString(); sb.setLength(0)
                    records += fields; fields = ArrayList()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || fields.isNotEmpty()) {
            fields += sb.toString()
            records += fields
        }
        return records
    }
}
