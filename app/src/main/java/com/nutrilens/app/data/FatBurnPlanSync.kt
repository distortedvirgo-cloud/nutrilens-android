package com.nutrilens.app.data

import android.content.Context
import android.util.Log
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.Trend
import com.nutrilens.app.insights.effectiveDailyGoal
import com.nutrilens.app.insights.effectiveMacroGoals
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
 *
 * С 3.22 план сам по таймеру не пересчитывается: только при включении режима
 * (плана нет), первом весе/замере, смене цели % и на недельном чек-ине
 * ([completeCheckIn]) — человек видит, почему цифры поменялись.
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
     * Пересчитать план, если режим включён и пора: плана нет или сменилась цель %.
     * Недельная правка — только через чек-ин. true — план пересчитан и сохранён.
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
        // Тихого пересчёта раз в неделю больше нет — его заменил чек-ин.
        val due = settings.planCalories == null || settings.planTargetFat != target
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

    /**
     * Завершить недельный чек-ин: записать вес (и замер, если был), подвести итоги
     * периода, принять решение по плану (FatBurnPlanner.checkIn) и сохранить всё —
     * чек-ин в историю, новый план в settings. null — режим выключен.
     */
    suspend fun completeCheckIn(
        context: Context,
        weightKg: Double,
        measurement: BodyMeasurementEntity?,
        heightCm: Double?,
        hunger: Int?,
        energy: Int?,
        sleep: Int?
    ): CheckInEntity? {
        val appContext = context.applicationContext
        val result = mutex.withLock {
            withContext(Dispatchers.IO) {
                runCheckIn(NutriLensDatabase.getInstance(appContext), weightKg, measurement, heightCm, hunger, energy, sleep)
            }
        }
        DataEvents.notifyChanged()
        com.nutrilens.app.widget.WidgetUpdater.refresh(appContext)
        com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(appContext)
        return result
    }

    private suspend fun runCheckIn(
        db: NutriLensDatabase,
        weightKg: Double,
        measurement: BodyMeasurementEntity?,
        heightCm: Double?,
        hunger: Int?,
        energy: Int?,
        sleep: Int?
    ): CheckInEntity? {
        val repo = SettingsRepository(db.settingsDao())
        val today = LocalDate.now()
        val todayStr = today.toString()

        // 1. Сначала сохраняем то, что ввёл человек: вес сегодня, замер и рост.
        db.weightDao().upsert(WeightEntity(todayStr, weightKg))
        if (heightCm != null) repo.update { it.copy(heightCm = heightCm) }
        if (measurement != null) db.bodyMeasurementDao().upsert(measurement)

        val settings = repo.get()
        val target = settings.targetFatPercent ?: return null

        // 2. Период: с прошлого чек-ина (или включения режима), от 7 до 21 дня, без сегодняшнего дня.
        val period = CheckInPlanner.periodDays(today, settings.checkInReference)
        val start = today.minusDays(period.toLong())
        val end = today.minusDays(1)

        // 3. Тренд веса — по истории побольше периода, чтобы сглаживание успело «разогреться».
        val trendPoints = Trend.smooth(
            db.weightDao().since(today.minusDays(90).toString()).mapNotNull { w ->
                runCatching { FatBurnPlanner.DayValue(LocalDate.parse(w.date), w.weight) }.getOrNull()
            }
        )
        val trendNow = trendPoints.lastOrNull()?.trend
        // Нет взвешиваний до периода — считаем от первого внутри него (если их хотя бы два).
        val trendStart = Trend.valueAt(trendPoints, start)
            ?: trendPoints.takeIf { it.size >= 2 }?.first()?.trend
        val trendChange = if (trendNow != null && trendStart != null) trendNow - trendStart else null
        val weeklyRate = Trend.weeklyLossPercent(trendPoints, period.toLong())

        // 4. Дневник периода: калории, белок и отметки силовых по дням.
        val meals = db.mealDao().mealsBetween(start.toString(), end.toString()).groupBy { it.date }
        val workoutDates = db.workoutDao().between(start.toString(), end.toString())
            .filter { it.done }
            .map { it.date }
            .toSet()
        // Активность дня (3.23): шаги и сон для оценки; дней без записи — правило 3.22.
        val activity = db.dayActivityDao().between(start.toString(), end.toString()).associateBy { it.date }
        val days = (0 until period).map { i ->
            val date = start.plusDays(i.toLong())
            val key = date.toString()
            val list = meals[key].orEmpty()
            val act = activity[key]
            CheckInPlanner.Day(
                date = date,
                kcal = list.sumOf { it.calories },
                protein = list.sumOf { it.protein },
                workout = key in workoutDates,
                hasActivity = act != null,
                steps = act?.steps,
                sleptWell = act?.sleptWell
            )
        }

        // 5. Итоги — против плана, который действовал всю прошлую неделю.
        val measurements = db.bodyMeasurementDao().all()
        val fatPercent = measurements.lastOrNull()?.fatPercent?.takeIf { it > 0 }
        val goalsBefore = effectiveMacroGoals(settings, weightKg, fatPercent)
        val kcalBefore = settings.effectiveDailyGoal
        val review = CheckInPlanner.review(
            days = days,
            periodDays = period,
            planCalories = kcalBefore,
            planProtein = goalsBefore.protein,
            workoutGoalPerWeek = settings.workoutGoal.coerceIn(CheckInPlanner.MIN_WORKOUT_GOAL, CheckInPlanner.MAX_WORKOUT_GOAL),
            weeklyLossPercent = weeklyRate,
            stepGoal = com.nutrilens.app.insights.DayScore.clampStepGoal(settings.stepGoal)
        )

        // 6. Решение по плану.
        val previous = db.checkInDao().latest()
        val input = buildInput(db, settings, target, today, usePrevious = true) ?: return null
        val decision = FatBurnPlanner.checkIn(
            input,
            FatBurnPlanner.CheckInContext(
                previousProtein = settings.planProtein,
                previousFat = settings.planFat,
                previousCarbs = settings.planCarbs,
                trendWeeklyPercent = weeklyRate,
                diaryDays = review.diaryDays,
                diaryNeeded = review.diaryNeeded,
                periodDays = period,
                signal = review.signal,
                hungerHighTwice = hunger == 5 && previous?.hunger == 5,
                previousFlatWeeks = previous?.flatWeeks ?: 0
            )
        )
        val plan = decision.plan

        val fatChange = if (measurements.size >= 2) {
            measurements[measurements.size - 1].fatPercent - measurements[measurements.size - 2].fatPercent
        } else null

        val entity = CheckInEntity(
            date = todayStr,
            weight = weightKg,
            trendWeight = trendNow,
            fatPercent = fatPercent,
            hunger = hunger,
            energy = energy,
            sleep = sleep,
            periodDays = period,
            diaryDays = review.diaryDays,
            goodDays = review.goodDays,
            proteinDays = review.proteinDays,
            workouts = review.workouts,
            workoutGoal = review.workoutGoal,
            trendChangeKg = trendChange,
            weeklyRatePercent = weeklyRate,
            fatChange = fatChange,
            muscleSignal = review.signal.name.lowercase(),
            signalNote = review.signalNote,
            flatWeeks = decision.flatWeeks,
            caloriesBefore = settings.planCalories ?: kcalBefore,
            caloriesAfter = plan.calories,
            proteinBefore = settings.planProtein ?: goalsBefore.protein,
            proteinAfter = plan.protein,
            fatBefore = settings.planFat ?: goalsBefore.fat,
            fatAfter = plan.fat,
            carbsBefore = settings.planCarbs ?: goalsBefore.carbs,
            carbsAfter = plan.carbs,
            note = plan.note,
            nextAction = review.action,
            avgScore = review.avgScore
        )
        db.checkInDao().upsert(entity)
        repo.update {
            it.copy(
                planCalories = plan.calories,
                planProtein = plan.protein,
                planFat = plan.fat,
                planCarbs = plan.carbs,
                planTdee = plan.tdee,
                planNote = plan.note,
                planDate = todayStr,
                planObserved = plan.observed,
                planTargetFat = target,
                lastCheckInDate = todayStr
            )
        }
        return entity
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
