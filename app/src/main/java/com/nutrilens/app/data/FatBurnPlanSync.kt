package com.nutrilens.app.data

import android.content.Context
import android.util.Log
import com.nutrilens.app.insights.FatBurnPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Связка FatBurnPlanner с БД: собирает вес, еду и замеры, решает, пора ли
 * пересчитать, и сохраняет план в settings. Сам алгоритм — в FatBurnPlanner.
 * Вызывается при старте приложения, открытии «Сегодня», смене режима/цели и
 * новом весе; лишние вызовы безопасны — без нужды план не меняется.
 */
object FatBurnPlanSync {
    private const val TAG = "FatBurnPlanSync"

    // Несколько экранов зовут пересчёт одновременно — mutex, чтобы не посчитать дважды.
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget вариант для Activity/фона. */
    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runCatching { ensureFresh(appContext) } }
    }

    /**
     * Пересчитать план, если режим включён и пора: плана нет, сменилась цель %
     * или прошла неделя. true — план пересчитан и сохранён.
     */
    suspend fun ensureFresh(context: Context): Boolean {
        val appContext = context.applicationContext
        val changed = mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    recalcIfDue(NutriLensDatabase.getInstance(appContext))
                } catch (e: Exception) {
                    Log.w(TAG, "Пересчёт плана не удался: ${e.message}")
                    false
                }
            }
        }
        if (changed) {
            // Новые цифры — виджетам и лидерборду (он публикует эффективную цель).
            com.nutrilens.app.widget.WidgetUpdater.refresh(appContext)
            com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(appContext)
        }
        return changed
    }

    /**
     * Живой расчёт без сохранения — для подробной карточки: темп, чего не хватает.
     * null — режим выключен или веса ещё нет.
     */
    suspend fun preview(context: Context): FatBurnPlanner.Plan? = withContext(Dispatchers.IO) {
        try {
            val db = NutriLensDatabase.getInstance(context.applicationContext)
            val settings = SettingsRepository(db.settingsDao()).get()
            val target = settings.targetFatPercent ?: return@withContext null
            buildInput(db, settings, target, LocalDate.now(), usePrevious = true)?.let { FatBurnPlanner.compute(it) }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun recalcIfDue(db: NutriLensDatabase): Boolean {
        val repo = SettingsRepository(db.settingsDao())
        val settings = repo.get()
        val target = settings.targetFatPercent ?: return false
        val today = LocalDate.now()
        val planDate = settings.planDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val due = settings.planCalories == null ||
            settings.planTargetFat != target ||
            FatBurnPlanner.isDue(planDate, today)
        if (!due) return false
        val input = buildInput(db, settings, target, today, usePrevious = true) ?: return false
        val plan = FatBurnPlanner.compute(input)
        repo.update {
            it.copy(
                planCalories = plan.calories,
                planProtein = plan.protein,
                planFat = plan.fat,
                planCarbs = plan.carbs,
                planTdee = plan.tdee,
                planNote = plan.note,
                planDate = plan.date.toString(),
                planObserved = plan.observed,
                planTargetFat = target
            )
        }
        return true
    }

    private suspend fun buildInput(
        db: NutriLensDatabase,
        settings: SettingsEntity,
        target: Double,
        today: LocalDate,
        usePrevious: Boolean
    ): FatBurnPlanner.Input? {
        val weight = db.weightDao().latest()?.weight?.takeIf { it > 0 } ?: return null
        val start = today.minusDays(FatBurnPlanner.WINDOW_DAYS)
        val weights = db.weightDao().since(start.toString()).mapNotNull { w ->
            runCatching { FatBurnPlanner.DayValue(LocalDate.parse(w.date), w.weight) }.getOrNull()
        }
        val calories = db.mealDao().mealsBetween(start.toString(), today.toString())
            .groupBy { it.date }
            .mapNotNull { (date, meals) ->
                runCatching { FatBurnPlanner.DayValue(LocalDate.parse(date), meals.sumOf { it.calories }) }.getOrNull()
            }
        val fatPercent = db.bodyMeasurementDao().latest()?.fatPercent?.takeIf { it > 0 }
        return FatBurnPlanner.Input(
            male = BodyComposition.isMale(settings),
            heightCm = BodyComposition.heightCm(settings),
            weightKg = weight,
            weights = weights,
            dailyCalories = calories,
            fatPercent = fatPercent,
            targetFatPercent = target,
            activity = FatBurnPlanner.activityFromText(settings.userContext),
            previousCalories = if (usePrevious) settings.planCalories else null,
            previousDate = if (usePrevious) settings.planDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } else null,
            today = today
        )
    }
}
