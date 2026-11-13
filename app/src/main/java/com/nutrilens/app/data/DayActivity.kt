package com.nutrilens.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Активность дня (3.23): шаги и отметка «поспал 8 часов» — одна запись на дату.
 * Нужна для оценки дня в режиме «Сжигание жира» и плитки шагов на «Сегодня».
 * Схема таблицы обязана совпадать с MIGRATION_11_12 один-в-один:
 * Int? → INTEGER без NOT NULL, String? → TEXT без NOT NULL, Boolean? → INTEGER без NOT NULL.
 */
@Serializable
@Entity(tableName = "day_activity")
data class DayActivityEntity(
    @PrimaryKey val date: String, // "YYYY-MM-DD"
    // Шаги за день; null — неизвестно (шаги выключены или ещё не было ни одного чтения).
    val steps: Int? = null,
    // Откуда шаги: "health_connect" / "sensor" / "manual"; null — шагов нет.
    val stepsSource: String? = null,
    // «Поспал 8 часов» про прошедшую ночь: true / false; null — не отвечал.
    val sleptWell: Boolean? = null
)

object StepsSource {
    const val HEALTH_CONNECT = "health_connect"
    const val SENSOR = "sensor"
    const val MANUAL = "manual"
}

@Dao
interface DayActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DayActivityEntity)

    /** Пустая запись дня, если её ещё нет: так день «принадлежит» 3.23 и считается по оценке. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: DayActivityEntity)

    @Query("SELECT * FROM day_activity WHERE date = :date")
    suspend fun byDate(date: String): DayActivityEntity?

    @Query("SELECT * FROM day_activity WHERE date = :date")
    fun observeByDate(date: String): Flow<DayActivityEntity?>

    @Query("SELECT * FROM day_activity WHERE date BETWEEN :start AND :end ORDER BY date")
    suspend fun between(start: String, end: String): List<DayActivityEntity>

    @Query("SELECT * FROM day_activity ORDER BY date")
    suspend fun all(): List<DayActivityEntity>

    @Query("DELETE FROM day_activity")
    suspend fun deleteAll()
}

/** Запись шагов и сна. Ручной ввод шагов главнее автоматического на этот день. */
class DayActivityRepository(private val dao: DayActivityDao) {
    fun observe(date: String): Flow<DayActivityEntity?> = dao.observeByDate(date)

    suspend fun get(date: String): DayActivityEntity? = dao.byDate(date)

    suspend fun ensureDay(date: String) = dao.insertIfMissing(DayActivityEntity(date))

    suspend fun setSleptWell(date: String, value: Boolean?) {
        val current = dao.byDate(date) ?: DayActivityEntity(date)
        dao.upsert(current.copy(sleptWell = value))
    }

    /** Ручные шаги; null — убрать ручное значение, дальше снова считает телефон. */
    suspend fun setManualSteps(date: String, steps: Int?) {
        val current = dao.byDate(date) ?: DayActivityEntity(date)
        dao.upsert(
            if (steps == null) current.copy(steps = null, stepsSource = null)
            else current.copy(steps = steps.coerceIn(0, 200_000), stepsSource = StepsSource.MANUAL)
        )
    }

    /**
     * Шаги от Health Connect или датчика. Ручное значение не трогаем. Если день уже
     * посчитан Health Connect (он видит и часы/браслет), датчик телефона перезаписывает
     * его только большим числом — чтобы источник не «прыгал», когда HC разок не ответил.
     */
    suspend fun setAutoSteps(date: String, steps: Int, source: String) {
        val current = dao.byDate(date) ?: DayActivityEntity(date)
        if (current.stepsSource == StepsSource.MANUAL) return
        val existing = current.steps
        if (source == StepsSource.SENSOR && current.stepsSource == StepsSource.HEALTH_CONNECT &&
            existing != null && existing >= steps
        ) return
        if (existing == steps && current.stepsSource == source) return
        dao.upsert(current.copy(steps = steps.coerceAtLeast(0), stepsSource = source))
    }
}
