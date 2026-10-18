package com.nutrilens.app.leaderboard

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Один день участника: съеденные ккал и его дневная цель на тот день. */
@Serializable
data class LeaderboardDay(
    val calories: Double = 0.0,
    val goal: Double = 0.0
)

/**
 * Файл участника: дни по ключу «yyyy-MM-dd». Новые файлы называются по
 * постоянному id игрока (data/p_<id>.json), ник — просто поле, поэтому смена
 * ника не плодит дубликатов. Старые файлы data/<ник>.json (id пустой)
 * сливаются в файл с id при первом пуше обновлённого приложения.
 */
@Serializable
data class LeaderboardEntry(
    val id: String = "",
    val nickname: String = "",
    val days: Map<String, LeaderboardDay> = emptyMap(),
    val updatedAt: Long = 0L
)

/** Период лидерборда: сегодняшний день, текущая неделя (Пн–Вс) или текущий месяц. */
enum class LeaderboardPeriod { DAY, WEEK, MONTH }

/** Строка таблицы лидерборда, готовая к показу на экране. */
data class LeaderboardRow(
    val id: String,
    val nickname: String,
    val score: Int,
    val percent: Int,
    val calories: Double,
    val goal: Double,
    val daysCounted: Int,
    val isOver: Boolean,
    val isSelf: Boolean
)

/** Подсчёт очков и строк лидерборда за выбранный период. */
object LeaderboardMath {

    /** Очки дня: 100 - |процент - 100| (не ниже 0); null — цель <= 0. */
    fun dayScore(calories: Double, goal: Double): Int? {
        if (goal <= 0.0) return null
        val percent = percentOf(calories, goal)
        return (100 - abs(percent - 100)).coerceAtLeast(0)
    }

    /**
     * Строки периода, отсортированы score DESC, затем daysCounted DESC, затем nickname ASC.
     * DAY — только сегодняшний день; WEEK — текущая неделя Пн–Вс; MONTH — текущий месяц
     * (java.time, minSdk 26). Для недели/месяца calories/goal — суммы по зачтённым дням,
     * percent — от сумм, score — среднее dayScore по дням с данными (вниз),
     * daysCounted — число зачтённых дней (записей с целью > 0).
     */
    fun rows(
        entries: List<LeaderboardEntry>,
        period: LeaderboardPeriod,
        selfId: String,
        selfNickname: String
    ): List<LeaderboardRow> {
        val today = LocalDate.now()
        val dates: List<LocalDate> = when (period) {
            LeaderboardPeriod.DAY -> listOf(today)
            LeaderboardPeriod.WEEK -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                (0L..6L).map { monday.plusDays(it) }
            }
            LeaderboardPeriod.MONTH -> {
                val first = today.withDayOfMonth(1)
                (0L until today.lengthOfMonth().toLong()).map { first.plusDays(it) }
            }
        }
        return entries.map { entry ->
            val dayRecords = dates.mapNotNull { date -> entry.days[date.toString()] }
            when (period) {
                LeaderboardPeriod.DAY -> {
                    val day = dayRecords.firstOrNull()
                    val calories = day?.calories ?: 0.0
                    val goal = day?.goal ?: 0.0
                    LeaderboardRow(
                        id = entry.id,
                        nickname = entry.nickname,
                        score = dayScore(calories, goal) ?: 0,
                        percent = percentOf(calories, goal),
                        calories = calories,
                        goal = goal,
                        daysCounted = if (goal > 0.0) 1 else 0,
                        isOver = calories > goal,
                        isSelf = isSelf(entry, selfId, selfNickname)
                    )
                }
                LeaderboardPeriod.WEEK, LeaderboardPeriod.MONTH -> {
                    val counted = dayRecords.filter { it.goal > 0.0 }
                    val calories = counted.sumOf { it.calories }
                    val goal = counted.sumOf { it.goal }
                    val score = if (counted.isEmpty()) {
                        0
                    } else {
                        counted.mapNotNull { dayScore(it.calories, it.goal) }
                            .average()
                            .let { floor(it).toInt() }
                    }
                    LeaderboardRow(
                        id = entry.id,
                        nickname = entry.nickname,
                        score = score,
                        percent = percentOf(calories, goal),
                        calories = calories,
                        goal = goal,
                        daysCounted = counted.size,
                        isOver = calories > goal,
                        isSelf = isSelf(entry, selfId, selfNickname)
                    )
                }
            }
        }.sortedWith(
            compareByDescending<LeaderboardRow> { it.score }
                .thenByDescending { it.daysCounted }
                .thenBy { it.nickname }
        )
    }

    /** Свой ли участник: по id, а у старых файлов без id — по нику. */
    private fun isSelf(entry: LeaderboardEntry, selfId: String, selfNickname: String): Boolean =
        if (entry.id.isNotEmpty()) entry.id == selfId else entry.nickname == selfNickname

    /**
     * Убирает дубликаты: по каждому id остаётся самая свежая запись; старый
     * файл без id скрывается, если есть запись с id и тем же ником (игрок уже
     * обновил приложение, а старый файл ещё не удалён).
     */
    fun dedupe(entries: List<LeaderboardEntry>): List<LeaderboardEntry> {
        val withId = entries.filter { it.id.isNotEmpty() }
            .groupBy { it.id }
            .map { (_, list) -> list.maxBy { it.updatedAt } }
        val knownNames = withId.map { it.nickname.trim().lowercase() }.toSet()
        val legacy = entries.filter { it.id.isEmpty() }
            .filter { it.nickname.trim().lowercase() !in knownNames }
            .groupBy { it.nickname.trim().lowercase() }
            .map { (_, list) -> list.maxBy { it.updatedAt } }
        return withId + legacy
    }

    /** Процент выполнения плана, округлённый до целого; цель <= 0 — ноль. */
    private fun percentOf(calories: Double, goal: Double): Int =
        if (goal <= 0.0) 0 else (calories / goal * 100).roundToInt()
}
