package com.nutrilens.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val id: String,
    val date: String, // "YYYY-MM-DD"
    val time: String, // "HH:mm"
    val name: String,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val aiThoughts: String = "",
    val reasoning: String = "",
    val confidenceScore: Double = 0.0,
    val dailyGoalSnapshot: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "meal_images", indices = [Index("mealId")])
data class MealImageEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val path: String, // абсолютный путь к файлу
    val kind: String, // "FULL" или "THUMB"
    val sortIndex: Int = 0
)

@Entity(tableName = "meal_items", indices = [Index("mealId")])
data class MealItemEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val name: String,
    val weightG: Double,
    val portionBasis: String = "",
    val calorieDensity: Double = 0.0,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val breakdown: String = ""
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double
)

@Entity(tableName = "weights")
data class WeightEntity(
    @PrimaryKey val date: String,
    val weight: Double
)

@Entity(tableName = "water")
data class WaterEntity(
    @PrimaryKey val date: String,
    val ml: Int = 0
)

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val date: String,
    val done: Boolean = false
)

@Entity(tableName = "habits_log", indices = [Index(value = ["date", "habitId"], unique = true)])
data class HabitLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val habitId: String
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val apiKey: String = "",
    val dailyGoal: Double = 2000.0,
    val proteinGoal: Double? = null,
    val fatGoal: Double? = null,
    val carbsGoal: Double? = null,
    val userContext: String = "Я мужчина, 85 кг, жарю на 5г масла",
    val breakfastReminderEnabled: Boolean = true,
    val breakfastTime: String = "08:00",
    val lunchReminderEnabled: Boolean = true,
    val lunchTime: String = "13:00",
    val dinnerReminderEnabled: Boolean = true,
    val dinnerTime: String = "19:00",
    val waterReminderEnabled: Boolean = false,
    val waterIntervalMinutes: Int = 120,
    val weighInReminderEnabled: Boolean = true,
    val updateRepo: String = "distortedvirgo-cloud/nutrilens-android"
)

@Entity(tableName = "analysis_jobs")
data class AnalysisJobEntity(
    @PrimaryKey val id: String,
    val status: String = "QUEUED", // QUEUED|RUNNING|DONE|FAILED
    val note: String = "",
    val photoPaths: String = "[]", // JSON-массив абсолютных путей
    val createdAt: Long = System.currentTimeMillis(),
    val mealId: String? = null,
    val error: String? = null
)