package com.nutrilens.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
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
    // Оценка полезности блюда (0–100) + нейтральная фраза о составе. null = нет данных.
    val healthScore: Int? = null,
    val healthNote: String? = null,
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

@Serializable
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double
)

@Serializable
@Entity(tableName = "weights")
data class WeightEntity(
    @PrimaryKey val date: String,
    val weight: Double
)

@Serializable
@Entity(tableName = "water")
data class WaterEntity(
    @PrimaryKey val date: String,
    val ml: Int = 0
)

@Serializable
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val date: String,
    val done: Boolean = false
)

@Serializable
@Entity(tableName = "habits_log", indices = [Index(value = ["date", "habitId"], unique = true)])
data class HabitLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val habitId: String
)

@Serializable
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
    val updateRepo: String = "distortedvirgo-cloud/nutrilens-android",
    val nanoApiKey: String = "",
    val nanoApiEndpoint: String = "",
    // Провайдер ИИ: "nanogpt" (по умолчанию) или "gemini" (бесплатный ключ Google).
    // Старые значения free/simple/advanced читаются через [aiProvider].
    val analysisMode: String = "nanogpt",
    // Ник участника лидерборда (пусто — в лидерборде не участвуем).
    val leaderboardNickname: String = "",
    // Цель по проценту жира (null — функция выключена) и данные для формулы ВМС.
    val targetFatPercent: Double? = null,
    val heightCm: Double? = null,
    // "male" / "female"; пусто — берём из текста «О себе».
    val sex: String = "",
    // Режим «Сжигание жира»: сохранённый недельный план (см. FatBurnPlanner).
    // Хранится, чтобы цифры не прыгали между пересчётами и были доступны виджетам.
    // Ручные цели (dailyGoal и БЖУ выше) план не трогает — выключил режим, и они вернулись.
    val planCalories: Double? = null,
    val planProtein: Double? = null,
    val planFat: Double? = null,
    val planCarbs: Double? = null,
    val planTdee: Double? = null,
    val planNote: String = "",
    // "YYYY-MM-DD" последнего пересчёта; null — плана ещё не было.
    val planDate: String? = null,
    // true — расход посчитан по твоим весу и еде, false — по формуле.
    val planObserved: Boolean = false,
    // Цель %, под которую считался план: сменили цель — пересчитываем сразу.
    val planTargetFat: Double? = null,
    // Недельный чек-ин (3.22): день недели ISO (1 — пн … 7 — вс), цель силовых в неделю,
    // день включения режима и дата прошлого чек-ина ("YYYY-MM-DD"). План меняется только на чек-ине.
    val checkInWeekday: Int = 7,
    val workoutGoal: Int = 3,
    val fatBurnStartDate: String? = null,
    val lastCheckInDate: String? = null,
    // Шаги (3.23): включены ли (разрешение спрашивается только при включении) и цель на день.
    // Шаги — цель поведения, калорий к бюджету они не прибавляют.
    val stepsEnabled: Boolean = false,
    val stepGoal: Int = 8000,
    // Программа с этапами (3.24): блоки, перерывы, удержание и пауза — JSON-строкой
    // (см. FatBurnProgramCodec), чтобы не заводить таблицу ради одного объекта. null — программы нет.
    val fatBurnProgram: String? = null
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

/** Задача уточнения уже добавленного блюда («Поправить»): ИИ пересчитывает с учётом правки. */
@Entity(tableName = "refinement_jobs")
data class RefinementJobEntity(
    @PrimaryKey val id: String,
    val mealId: String,
    val correction: String,
    val status: String = "QUEUED", // QUEUED|RUNNING|DONE|FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null
)

/**
 * Фоновая задача одного из ИИ-инструментов «Ещё» (ideas/fridge/menu/grocery/
 * water/habit). Параметры запроса замораживаются в input (JSON) в момент
 * запуска, результат готовой задачи — в result (JSON или markdown).
 */
@Entity(tableName = "tool_jobs")
data class ToolJobEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val input: String = "{}",
    val status: String = "QUEUED", // QUEUED|RUNNING|DONE|FAILED
    val result: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null
)
/** Провайдер ИИ, выбранный пользователем: "nanogpt" или "gemini" (старые free → gemini). */
val SettingsEntity.aiProvider: String
    get() = if (analysisMode == "free" || analysisMode == "gemini") "gemini" else "nanogpt"

/** Задан ли хоть один ключ — без него анализ еды и чат не работают. */
val SettingsEntity.hasAnyAiKey: Boolean
    get() = apiKey.isNotBlank() || nanoApiKey.isNotBlank()
