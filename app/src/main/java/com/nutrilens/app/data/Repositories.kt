package com.nutrilens.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class MealRepository(
    private val mealDao: MealDao,
    private val waterDao: WaterDao,
    private val weightDao: WeightDao,
    private val workoutDao: WorkoutDao
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDayMeals(date: String): Flow<List<MealWithImages>> {
        return mealDao.mealsByDate(date).flatMapLatest { meals ->
            if (meals.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(meals.map { mealDao.imagesByMeal(it.id) }) { arrays ->
                    meals.mapIndexed { index, meal ->
                        MealWithImages(meal, arrays[index].toList())
                    }
                }
            }
        }
    }

    suspend fun getWaterMl(date: String): Int = waterDao.byDate(date)?.ml ?: 0

    suspend fun getLatestWeight(): Double? = weightDao.latest()?.weight

    suspend fun getWorkoutDone(date: String): Boolean = workoutDao.byDate(date)?.done ?: false

    suspend fun setWorkoutDone(date: String, done: Boolean) =
        workoutDao.upsert(WorkoutEntity(date, done))

    suspend fun allMealDates(): List<String> = mealDao.allDates()

    suspend fun mealsOn(date: String): List<MealEntity> = mealDao.mealsBetween(date, date)

    suspend fun addMeal(meal: MealEntity, images: List<MealImageEntity>, items: List<MealItemEntity>) {
        mealDao.insertMeal(meal)
        if (images.isNotEmpty()) {
            mealDao.insertImages(images)
        }
        if (items.isNotEmpty()) {
            mealDao.insertItems(items)
        }
    }

    suspend fun updateMeal(meal: MealEntity) = mealDao.updateMeal(meal)

    suspend fun deleteMeal(meal: MealEntity, images: List<MealImageEntity>) {
        images.forEach { image ->
            runCatching { File(image.path).delete() }
        }
        mealDao.deleteMealWithRelated(meal.id)
    }

    suspend fun itemsForMeal(mealId: String) = mealDao.itemsByMeal(mealId)
}

class SettingsRepository(private val settingsDao: SettingsDao) {
    fun observe(): Flow<SettingsEntity> = settingsDao.observe().map { it ?: SettingsEntity() }

    suspend fun get(): SettingsEntity = settingsDao.get() ?: SettingsEntity()

    suspend fun update(transform: (SettingsEntity) -> SettingsEntity) {
        settingsDao.upsert(transform(get()))
    }
}

class WaterRepository(private val waterDao: WaterDao) {
    suspend fun addWater(date: String, deltaMl: Int) {
        val current = waterDao.byDate(date)?.ml ?: 0
        waterDao.upsert(WaterEntity(date, maxOf(0, current + deltaMl)))
    }
}

class WeightRepository(private val weightDao: WeightDao) {
    suspend fun addWeight(date: String, weight: Double) {
        weightDao.upsert(WeightEntity(date, weight))
    }

    suspend fun daysSinceLastWeight(): Int? {
        val latest = weightDao.latest() ?: return null
        return ChronoUnit.DAYS.between(LocalDate.parse(latest.date), LocalDate.now()).toInt()
    }
}

class AnalysisJobRepository(private val jobDao: AnalysisJobDao) {
    suspend fun createJob(note: String, photoPaths: List<String>): AnalysisJobEntity {
        val pathsJson = JSONArray().apply { photoPaths.forEach { put(it) } }.toString()
        val job = AnalysisJobEntity(
            id = UUID.randomUUID().toString(),
            status = "QUEUED",
            note = note,
            photoPaths = pathsJson,
            createdAt = System.currentTimeMillis()
        )
        jobDao.upsert(job)
        return job
    }

    suspend fun byId(id: String): AnalysisJobEntity? = jobDao.byId(id)

    suspend fun active(): List<AnalysisJobEntity> = jobDao.active()

    fun observeActive(): Flow<List<AnalysisJobEntity>> = jobDao.observeActive()

    suspend fun markRunning(id: String) = jobDao.setStatus(id, "RUNNING", null, null)

    suspend fun markDone(id: String, mealId: String) = jobDao.setStatus(id, "DONE", mealId, null)

    suspend fun markFailed(id: String, error: String) = jobDao.setStatus(id, "FAILED", null, error)
}

class FavoriteRepository(private val favoriteDao: FavoriteDao) {
    fun observe(): Flow<List<FavoriteEntity>> = favoriteDao.all()

    suspend fun allList(): List<FavoriteEntity> = favoriteDao.allList()

    suspend fun add(name: String, calories: Double, protein: Double, fat: Double, carbs: Double) {
        favoriteDao.upsert(
            FavoriteEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                calories = calories,
                protein = protein,
                fat = fat,
                carbs = carbs
            )
        )
    }

    suspend fun remove(id: String) = favoriteDao.deleteById(id)

    suspend fun contains(name: String, calories: Double): Boolean =
        favoriteDao.allList().any { it.name == name && it.calories == calories }
}

class HabitsRepository(private val habitLogDao: HabitLogDao) {
    fun observeDate(date: String): Flow<List<HabitLogEntity>> = habitLogDao.byDate(date)

    suspend fun toggle(date: String, habitId: String) {
        if (habitId in idsFor(date)) {
            habitLogDao.deleteByDateAndHabit(date, habitId)
        } else {
            habitLogDao.insert(HabitLogEntity(date = date, habitId = habitId))
        }
    }

    suspend fun idsFor(date: String): Set<String> =
        habitLogDao.byDate(date).first().map { it.habitId }.toSet()
}