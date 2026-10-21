package com.nutrilens.app.data

import android.content.Context
import com.nutrilens.app.leaderboard.LeaderboardSync
import com.nutrilens.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Сигнал «данные дня изменились» вне Flow-запросов Room (вода, вес, тренировка
 * пишутся точечно): экраны перечитывают снимки, когда действие пришло из
 * панели «+», виджета или другого экрана.
 */
object DataEvents {
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }
}

/**
 * Быстрые действия без открытия экранов: панель «+», виджеты, подсказки.
 * Каждое пишет в БД, обновляет виджеты и рассылает DataEvents.
 */
object QuickActions {
    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

    private fun db(context: Context) = NutriLensDatabase.getInstance(context)

    private fun mealRepo(context: Context): MealRepository {
        val d = db(context)
        return MealRepository(d.mealDao(), d.waterDao(), d.weightDao(), d.workoutDao())
    }

    private fun afterChange(context: Context) {
        WidgetUpdater.refresh(context)
        DataEvents.notifyChanged()
    }

    /** +ml воды на сегодня; возвращает новое значение за день. */
    suspend fun addWater(context: Context, ml: Int): Int {
        val today = LocalDate.now().toString()
        WaterRepository(db(context).waterDao()).addWater(today, ml)
        afterChange(context)
        return mealRepo(context).getWaterMl(today)
    }

    suspend fun setWeight(context: Context, kg: Double) {
        WeightRepository(db(context).weightDao()).addWeight(LocalDate.now().toString(), kg)
        afterChange(context)
    }

    /** Переключает отметку тренировки за сегодня; возвращает новое состояние. */
    suspend fun toggleWorkout(context: Context): Boolean {
        val today = LocalDate.now().toString()
        val repo = mealRepo(context)
        val next = !repo.getWorkoutDone(today)
        repo.setWorkoutDone(today, next)
        afterChange(context)
        return next
    }

    /** Записывает блюдо из избранного в дневник на текущее время. */
    suspend fun addFavorite(context: Context, favorite: FavoriteEntity) {
        val now = LocalDateTime.now()
        val settings = SettingsRepository(db(context).settingsDao()).get()
        val meal = MealEntity(
            id = UUID.randomUUID().toString(),
            date = now.toLocalDate().toString(),
            time = now.toLocalTime().format(HH_MM),
            name = favorite.name,
            calories = favorite.calories,
            protein = favorite.protein,
            fat = favorite.fat,
            carbs = favorite.carbs,
            aiThoughts = "Добавлено из избранного",
            reasoning = "",
            confidenceScore = 10.0,
            dailyGoalSnapshot = settings.dailyGoal,
            createdAt = System.currentTimeMillis()
        )
        mealRepo(context).addMeal(meal, emptyList(), emptyList())
        afterChange(context)
        LeaderboardSync.pushAsync(context)
    }
}
