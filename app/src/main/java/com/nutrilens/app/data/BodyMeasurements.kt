package com.nutrilens.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Замер тела на дату (одна запись в день, как у веса). Обхваты — в см.
 * fatPercent хранится уже посчитанным: свой процент (весы, калипер, DEXA)
 * важнее формулы, и график не должен пересчитываться при смене роста в профиле.
 */
@Serializable
@Entity(tableName = "body_measurements")
data class BodyMeasurementEntity(
    @PrimaryKey val date: String, // "YYYY-MM-DD"
    val waistCm: Double? = null,
    val neckCm: Double? = null,
    // Бёдра нужны только женской формуле.
    val hipsCm: Double? = null,
    val manualFatPercent: Double? = null,
    val fatPercent: Double = 0.0
)

@Dao
interface BodyMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BodyMeasurementEntity)

    @Query("SELECT * FROM body_measurements ORDER BY date DESC LIMIT 1")
    suspend fun latest(): BodyMeasurementEntity?

    @Query("SELECT * FROM body_measurements ORDER BY date")
    fun observeAll(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements ORDER BY date")
    suspend fun all(): List<BodyMeasurementEntity>

    @Query("DELETE FROM body_measurements WHERE date = :date")
    suspend fun delete(date: String)

    @Query("DELETE FROM body_measurements")
    suspend fun deleteAll()
}

class BodyMeasurementRepository(private val dao: BodyMeasurementDao) {
    fun observeAll(): Flow<List<BodyMeasurementEntity>> = dao.observeAll()

    suspend fun latest(): BodyMeasurementEntity? = dao.latest()

    /** Сохранить замер за дату: повторный ввод в тот же день перезаписывает запись. */
    suspend fun save(entity: BodyMeasurementEntity) {
        dao.upsert(entity)
        DataEvents.notifyChanged()
    }

    suspend fun delete(date: String) {
        dao.delete(date)
        DataEvents.notifyChanged()
    }

    suspend fun daysSinceLast(): Int? {
        val last = dao.latest() ?: return null
        return ChronoUnit.DAYS.between(LocalDate.parse(last.date), LocalDate.now()).toInt()
    }
}

/** Последний процент жира — для цели по белку там, где нужен только он. */
suspend fun NutriLensDatabase.latestFatPercent(): Double? = bodyMeasurementDao().latest()?.fatPercent
