package com.nutrilens.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.nutrilens.app.insights.CheckInPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Недельный чек-ин режима «Сжигание жира» (одна запись на дату).
 * Хранится всё, что было на экране итогов: так история показывает ровно то,
 * что человек видел, даже если формулы потом поменяются.
 * Схема таблицы обязана совпадать с MIGRATION_10_11 (+ avgScore из MIGRATION_11_12, phaseChange из MIGRATION_12_13) один-в-один.
 */
@Serializable
@Entity(tableName = "check_ins")
data class CheckInEntity(
    @PrimaryKey val date: String, // "YYYY-MM-DD"
    val weight: Double,
    val trendWeight: Double? = null,
    val fatPercent: Double? = null,
    // Самочувствие 1–5; null — шаг пропущен.
    val hunger: Int? = null,
    val energy: Int? = null,
    val sleep: Int? = null,
    val periodDays: Int = 7,
    val diaryDays: Int = 0,
    val goodDays: Int = 0,
    val proteinDays: Int = 0,
    val workouts: Int = 0,
    val workoutGoal: Int = 3,
    // Изменение тренда веса за период, кг (минус — похудел) и темп, % веса в неделю (плюс — худеешь).
    val trendChangeKg: Double? = null,
    val weeklyRatePercent: Double? = null,
    // Изменение процента жира между двумя последними замерами, п.п.
    val fatChange: Double? = null,
    // "green" / "yellow" / "red"
    val muscleSignal: String = "green",
    val signalNote: String = "",
    // Недель подряд «вес стоит» при полном дневнике — для правила «две / три недели».
    val flatWeeks: Int = 0,
    val caloriesBefore: Double? = null,
    val caloriesAfter: Double? = null,
    val proteinBefore: Double? = null,
    val proteinAfter: Double? = null,
    val fatBefore: Double? = null,
    val fatAfter: Double? = null,
    val carbsBefore: Double? = null,
    val carbsAfter: Double? = null,
    // Почему план такой (или почему не менялся) и одно действие на неделю.
    val note: String = "",
    val nextAction: String = "",
    // Средняя оценка дня за период (3.23, 0–100); null — дней с оценкой не было или чек-ин старый.
    val avgScore: Double? = null,
    // Смена этапа программы на этом чек-ине (3.24): «Блок 1 завершён → 2 недели перерыва»; null — этап тот же.
    val phaseChange: String? = null
)

@Dao
interface CheckInDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CheckInEntity)

    @Query("SELECT * FROM check_ins ORDER BY date DESC LIMIT 1")
    suspend fun latest(): CheckInEntity?

    @Query("SELECT * FROM check_ins ORDER BY date DESC")
    fun observeAll(): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins ORDER BY date")
    suspend fun all(): List<CheckInEntity>

    @Query("DELETE FROM check_ins")
    suspend fun deleteAll()
}

private fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * От какой даты считать следующий чек-ин: прошлый чек-ин, иначе день включения
 * режима, иначе дата плана (для тех, кто включил режим ещё в 3.21).
 */
val SettingsEntity.checkInReference: LocalDate?
    get() = parseDate(lastCheckInDate) ?: parseDate(fatBurnStartDate) ?: parseDate(planDate)

/**
 * Чек-ины идут, пока программа не на паузе и не ждёт выбора после итога:
 * на паузе чек-инов и напоминаний нет — человек сам решил отдохнуть.
 */
val SettingsEntity.checkInsActive: Boolean
    get() {
        if (targetFatPercent == null) return false
        val program = fatBurnProgramState ?: return true
        return !program.paused && !program.awaitingChoice
    }

/** Пора ли подвести неделю: режим включён, программа идёт и подошёл (или прошёл) день чек-ина. */
fun SettingsEntity.isCheckInDue(today: LocalDate = LocalDate.now()): Boolean =
    checkInsActive && CheckInPlanner.isDue(today, checkInWeekday, checkInReference)

/** Ближайший чек-ин; null — режим выключен. */
fun SettingsEntity.nextCheckInDate(today: LocalDate = LocalDate.now()): LocalDate? =
    if (targetFatPercent == null) null else CheckInPlanner.nextDate(today, checkInWeekday, checkInReference)
