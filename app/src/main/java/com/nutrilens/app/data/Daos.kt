package com.nutrilens.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MealDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMeal(meal: MealEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun updateMeal(meal: MealEntity)

    @Query("SELECT * FROM meals WHERE date = :date ORDER BY time")
    abstract fun mealsByDate(date: String): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE date BETWEEN :start AND :end ORDER BY date, time")
    abstract suspend fun mealsBetween(start: String, end: String): List<MealEntity>

    @Query("SELECT * FROM meals ORDER BY date DESC, time DESC LIMIT :limit")
    abstract suspend fun recentMeals(limit: Int): List<MealEntity>

    @Query("SELECT DISTINCT date FROM meals")
    abstract suspend fun allDates(): List<String>

    @Query("SELECT * FROM meals WHERE id = :id")
    abstract suspend fun mealById(id: String): MealEntity?

    @Query("DELETE FROM meals WHERE id = :id")
    abstract suspend fun deleteMealById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertImages(images: List<MealImageEntity>)

    @Query("SELECT * FROM meal_images WHERE mealId = :mealId ORDER BY sortIndex")
    abstract fun imagesByMeal(mealId: String): Flow<List<MealImageEntity>>

    @Query("DELETE FROM meal_images WHERE mealId = :mealId")
    abstract suspend fun deleteImagesByMeal(mealId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertItems(items: List<MealItemEntity>)

    @Query("SELECT * FROM meal_items WHERE mealId = :mealId")
    abstract suspend fun itemsByMeal(mealId: String): List<MealItemEntity>

    @Query("DELETE FROM meal_items WHERE mealId = :mealId")
    abstract suspend fun deleteItemsByMeal(mealId: String)

    @Query("SELECT * FROM meals")
    abstract suspend fun allMeals(): List<MealEntity>

    /** Сколько приёмов пищи записано после момента [since] (epoch millis). */
    @Query("SELECT COUNT(*) FROM meals WHERE createdAt >= :since")
    abstract suspend fun countSince(since: Long): Int

    @Query("DELETE FROM meals")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM meal_images")
    abstract suspend fun deleteAllImages()

    @Query("DELETE FROM meal_items")
    abstract suspend fun deleteAllItems()

    @Transaction
    open suspend fun deleteMealWithRelated(mealId: String) {
        deleteItemsByMeal(mealId)
        deleteImagesByMeal(mealId)
        deleteMealById(mealId)
    }
}

@Dao
interface WeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(weight: WeightEntity)

    @Query("SELECT * FROM weights ORDER BY date DESC LIMIT 1")
    suspend fun latest(): WeightEntity?

    @Query("SELECT * FROM weights WHERE date >= :since ORDER BY date")
    suspend fun since(since: String): List<WeightEntity>

    @Query("SELECT * FROM weights ORDER BY date")
    suspend fun all(): List<WeightEntity>

    @Query("DELETE FROM weights")
    suspend fun deleteAll()
}

@Dao
interface WaterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(water: WaterEntity)

    @Query("SELECT * FROM water WHERE date = :date")
    suspend fun byDate(date: String): WaterEntity?

    @Query("SELECT * FROM water WHERE date BETWEEN :start AND :end")
    suspend fun between(start: String, end: String): List<WaterEntity>

    @Query("SELECT * FROM water")
    suspend fun all(): List<WaterEntity>

    @Query("DELETE FROM water")
    suspend fun deleteAll()
}

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workout: WorkoutEntity)

    @Query("SELECT * FROM workouts WHERE date = :date")
    suspend fun byDate(date: String): WorkoutEntity?

    @Query("SELECT * FROM workouts")
    suspend fun all(): List<WorkoutEntity>

    @Query("DELETE FROM workouts")
    suspend fun deleteAll()
}

@Dao
interface HabitLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(habitLog: HabitLogEntity)

    @Query("DELETE FROM habits_log WHERE date = :date AND habitId = :habitId")
    suspend fun deleteByDateAndHabit(date: String, habitId: String)

    @Query("SELECT * FROM habits_log WHERE date = :date")
    fun byDate(date: String): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habits_log")
    suspend fun all(): List<HabitLogEntity>

    @Query("DELETE FROM habits_log")
    suspend fun deleteAll()
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites ORDER BY name")
    fun all(): Flow<List<FavoriteEntity>>

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM favorites ORDER BY rowid DESC")
    suspend fun allList(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}

@Dao
interface AnalysisJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: AnalysisJobEntity)

    @Query("SELECT * FROM analysis_jobs WHERE status = 'QUEUED' OR status = 'RUNNING' ORDER BY createdAt ASC")
    suspend fun active(): List<AnalysisJobEntity>

    @Query("SELECT * FROM analysis_jobs WHERE status = 'QUEUED' OR status = 'RUNNING' ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<AnalysisJobEntity>>

    @Query("SELECT * FROM analysis_jobs WHERE id = :id")
    suspend fun byId(id: String): AnalysisJobEntity?

    @Query("UPDATE analysis_jobs SET status = :status, mealId = :mealId, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: String, mealId: String?, error: String?)
}