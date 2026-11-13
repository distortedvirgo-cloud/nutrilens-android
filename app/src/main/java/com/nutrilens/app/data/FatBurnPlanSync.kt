package com.nutrilens.app.data

import android.content.Context
import android.util.Log
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.FatBurnProgram
import com.nutrilens.app.insights.Trend
import com.nutrilens.app.insights.effectiveDailyGoal
import com.nutrilens.app.insights.effectiveMacroGoals
import com.nutrilens.app.training.StrengthAnalyzer
import com.nutrilens.app.training.StrengthCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Связка FatBurnPlanner с БД: собирает вес, еду и замеры, решает, пора ли
 * пересчитать, и сохраняет план в settings. Сам алгоритм — в FatBurnPlanner.
 * Вызывается при старте приложения, открытии «Сегодня», смене режима/цели и
 * новом весе; лишние вызовы безопасны — без нужды план не меняется.
 *
 * С 3.22 план сам по таймеру не пересчитывается: только при включении режима
 * (плана нет), первом весе/замере, смене цели % и на недельном чек-ине
 * ([completeCheckIn]) — человек видит, почему цифры поменялись.
 *
 * С 3.24 режим — программа с этапами (FatBurnProgram): в блоке план — дефицит,
 * в перерыве, удержании и на паузе — калории на уровне расхода. Сохранённый план
 * в settings — всегда тот, по которому человек живёт сегодня, поэтому оценка дня,
 * виджеты и лидерборд сами берут калории перерыва.
 */
object FatBurnPlanSync {
    private const val TAG = "FatBurnPlanSync"
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU"))

    private fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

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
        var settings = repo.get()
        val target = settings.targetFatPercent ?: return false
        val today = LocalDate.now()
        var changed = false
        val male = BodyComposition.isMale(settings)

        // 1. Режим включён до 3.24 — программы нет: собираем её без мастера запуска.
        var program = settings.fatBurnProgramState
        if (program == null) {
            program = migrateProgram(db, settings, today)
            if (program != null) {
                val created = program
                repo.update { it.withProgram(created) }
                settings = repo.get()
                changed = true
            }
        }

        // 2. Сменили итоговую цель в Профиле — будущие этапы пересобираем от текущей точки.
        val weight = db.weightDao().latest()?.weight?.takeIf { it > 0 }
        if (program != null && kotlin.math.abs(program.target - target) > 0.01) {
            val fat = fatTrendNow(db.bodyMeasurementDao().all())
            val retargeted = FatBurnProgram.retarget(program, target, fat, weight ?: program.startWeight, male)
            program = retargeted
            repo.update { it.withProgram(retargeted) }
            settings = repo.get()
            changed = true
        }

        // 3. Прошли 14 дней перерыва — следующий блок, не дожидаясь чек-ина.
        if (program != null) {
            val fat = fatTrendNow(db.bodyMeasurementDao().all())
            val t = FatBurnProgram.onAppOpen(program, today, fat, weight ?: program.startWeight, male)
            if (t.changed) {
                val next = t.program
                val title = FatBurnProgram.transitionText(t, program) ?: "Новый этап программы"
                val noticed = next.copy(notice = FatBurnProgram.Notice(title, noticeText(next)))
                val input = buildInput(db, settings, target, today, usePrevious = false)
                val plan = input?.let { phasePlan(it, noticed, freshBlock = true) }
                repo.update { s0 -> (if (plan != null) s0.withPlan(plan, target) else s0).withProgram(noticed) }
                return true
            }
        }

        // 4. Плана нет или сменилась цель % — посчитать под текущий этап.
        // Тихого пересчёта раз в неделю больше нет — его заменил чек-ин.
        val due = settings.planCalories == null || settings.planTargetFat != target ||
            // Программа только что собрана сразу в удержании (цель уже есть) — калории расхода.
            (changed && program != null && !program.inActiveBlock)
        if (!due) return changed
        val input = buildInput(db, settings, target, today, usePrevious = true) ?: return changed
        val plan = phasePlan(input, program, freshBlock = false)
        repo.update { it.withPlan(plan, target) }
        return true
    }

    /** Пояснение к разовой карточке смены этапа на «Сегодня». */
    private fun noticeText(p: FatBurnProgram.Program): String {
        val phase = p.currentPhase ?: return ""
        return when (phase.type) {
            FatBurnProgram.PhaseType.BLOCK -> "Две недели на уровне расхода позади — теперь свежий дефицит" +
                (phase.targetFat?.let { ", цель блока ${FatBurnProgram.fmt(it)}%" } ?: "") + ". План пересчитан по твоему текущему расходу."
            FatBurnProgram.PhaseType.BREAK -> "Перерыв — обязательная часть плана: ешь на уровне расхода."
            FatBurnProgram.PhaseType.MAINTENANCE -> "Цель достигнута — теперь закрепляем результат на уровне расхода."
        }
    }

    /**
     * Программа для тех, кто был в режиме до 3.24: блок 1 начался, когда включили режим
     * (или с первого чек-ина / плана / замера), процент и вес — ближайшие к этой дате.
     * null — веса ещё нет: соберём, когда он появится.
     */
    private suspend fun migrateProgram(db: NutriLensDatabase, settings: SettingsEntity, today: LocalDate): FatBurnProgram.Program? {
        val target = settings.targetFatPercent ?: return null
        val measurements = db.bodyMeasurementDao().all()
        val checkIns = db.checkInDao().all()
        val firstCheckIn = checkIns.firstOrNull()
        val candidates = listOfNotNull(
            parseDate(settings.fatBurnStartDate),
            parseDate(settings.planDate),
            firstCheckIn?.let { c -> parseDate(c.date)?.minusDays(c.periodDays.toLong()) }
        )
        val start = (candidates.minByOrNull { it.toEpochDay() } ?: measurements.firstOrNull()?.let { parseDate(it.date) } ?: today)
            .let { if (it.isAfter(today)) today else it }
        val weights = db.weightDao().all()
        val w = weights.lastOrNull { it.date <= start.toString() } ?: weights.firstOrNull() ?: return null
        val male = BodyComposition.isMale(settings)
        val m = measurements.lastOrNull { it.date <= start.toString() } ?: measurements.firstOrNull()
        val fat = m?.fatPercent?.takeIf { it > 0 } ?: if (male) 25.0 else 32.0
        return FatBurnProgram.create(start, fat, target, w.weight, male).copy(
            migrated = true,
            notice = FatBurnProgram.Notice(
                "Режим стал программой: этапы и перерывы",
                "Теперь это путь из этапов: блоки дефицита до 8 недель и обязательные 2 недели перерыва " +
                    "на уровне расхода между ними. Твой первый блок идёт с ${start.format(DAY_MONTH)}. " +
                    "Этапы меняются на чек-ине — весь путь виден в «Прогрессе»."
            )
        )
    }

    /**
     * План под этап: в активном блоке — дефицит планировщика ([freshBlock] — новый блок,
     * свежий дефицит без ограничения ±150 от калорий перерыва), иначе — уровень расхода.
     */
    private fun phasePlan(input: FatBurnPlanner.Input, program: FatBurnProgram.Program?, freshBlock: Boolean): FatBurnPlanner.Plan {
        if (program == null || program.inActiveBlock) {
            if (!freshBlock) return FatBurnPlanner.compute(input)
            val fresh = FatBurnPlanner.compute(input.copy(previousCalories = null, previousDate = null))
            return fresh.copy(note = "Новый блок — свежий дефицит по твоему текущему расходу")
        }
        return maintenancePlan(FatBurnPlanner.compute(input), program, input.weightKg)
    }

    /** Калории на уровне расхода (расход уже посчитан планировщиком), белок прежний. */
    private fun maintenancePlan(base: FatBurnPlanner.Plan, program: FatBurnProgram.Program, weightKg: Double): FatBurnPlanner.Plan {
        val m = FatBurnProgram.maintenanceMacros(base.tdee, base.protein, weightKg, base.floor)
        val kcal = m.calories.roundToInt()
        val phase = program.currentPhase?.type
        val note = when {
            program.paused -> "Программа на паузе — калории на уровне расхода ≈ $kcal ккал"
            program.completed -> "Удержание — калории на уровне расхода ≈ $kcal ккал"
            phase == FatBurnProgram.PhaseType.BREAK -> "Перерыв — калории на уровне расхода ≈ $kcal ккал: это часть плана, не срыв"
            else -> "Удержание — калории на уровне расхода ≈ $kcal ккал, белок прежний"
        }
        return base.copy(calories = m.calories, protein = m.protein, fat = m.fat, carbs = m.carbs, note = note)
    }

    private fun SettingsEntity.withPlan(plan: FatBurnPlanner.Plan, target: Double): SettingsEntity = copy(
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

    // ---------- Запуск, пауза, итог ----------

    /**
     * Запуск программы из мастера: вес и замер (если ввели), цель, этапы и первый план.
     * false — без веса план не посчитать.
     */
    suspend fun startProgram(
        context: Context,
        target: Double,
        weightKg: Double?,
        measurement: BodyMeasurementEntity?,
        heightCm: Double?
    ): Boolean {
        val appContext = context.applicationContext
        val ok = mutex.withLock {
            withContext(Dispatchers.IO) {
                val db = NutriLensDatabase.getInstance(appContext)
                val repo = SettingsRepository(db.settingsDao())
                val today = LocalDate.now()
                val todayStr = today.toString()
                if (weightKg != null) db.weightDao().upsert(WeightEntity(todayStr, weightKg))
                if (heightCm != null) repo.update { it.copy(heightCm = heightCm) }
                if (measurement != null) db.bodyMeasurementDao().upsert(measurement)
                val weight = db.weightDao().latest()?.weight?.takeIf { it > 0 } ?: return@withContext false
                val before = repo.get()
                val male = BodyComposition.isMale(before)
                val fat = fatTrendNow(db.bodyMeasurementDao().all()) ?: if (male) 25.0 else 32.0
                val program = FatBurnProgram.create(today, fat, target, weight, male)
                repo.update {
                    it.copy(
                        targetFatPercent = target,
                        planCalories = null,
                        planDate = null,
                        // Отсюда считается первый чек-ин: не раньше чем через 5 дней данных.
                        fatBurnStartDate = todayStr,
                        lastCheckInDate = null
                    ).withProgram(program)
                }
                val settings = repo.get()
                val input = buildInput(db, settings, target, today, usePrevious = false) ?: return@withContext false
                val plan = phasePlan(input, program, freshBlock = false)
                repo.update { it.withPlan(plan, target) }
                true
            }
        }
        afterChange(appContext)
        return ok
    }

    /** Предпросмотр для последнего шага мастера: этапы, прогноз и план на сегодня. Ничего не сохраняет. */
    data class LaunchPreview(
        val program: FatBurnProgram.Program,
        val plan: FatBurnPlanner.Plan,
        val forecast: FatBurnProgram.Forecast?
    )

    suspend fun launchPreview(context: Context, target: Double, weightKg: Double?, fatPercent: Double?): LaunchPreview? =
        withContext(Dispatchers.IO) {
            try {
                val db = NutriLensDatabase.getInstance(context.applicationContext)
                val settings = SettingsRepository(db.settingsDao()).get()
                val today = LocalDate.now()
                val male = BodyComposition.isMale(settings)
                val base = buildInput(db, settings, target, today, usePrevious = false)
                val weight = weightKg ?: base?.weightKg ?: return@withContext null
                val fat = fatPercent ?: fatTrendNow(db.bodyMeasurementDao().all()) ?: if (male) 25.0 else 32.0
                val input = (base ?: FatBurnPlanner.Input(
                    male = male,
                    heightCm = BodyComposition.heightCm(settings),
                    weightKg = weight,
                    weights = emptyList(),
                    dailyCalories = emptyList(),
                    fatPercent = fat,
                    targetFatPercent = target,
                    activity = FatBurnPlanner.activityFromText(settings.userContext),
                    today = today
                )).copy(weightKg = weight, fatPercent = fat)
                val program = FatBurnProgram.create(today, fat, target, weight, male)
                LaunchPreview(program, phasePlan(input, program, freshBlock = false), FatBurnProgram.forecast(program, today))
            } catch (e: Exception) {
                null
            }
        }

    /** Приостановить: план — на уровне расхода, чек-инов и напоминаний нет. */
    suspend fun pauseProgram(context: Context) = mutateProgram(context, recalc = true, freshBlock = false) { p, today ->
        FatBurnProgram.pause(p, today)
    }

    /**
     * Продолжить с того же этапа: дни паузы в срок не идут. Чек-ин отсчитывается от сегодня —
     * неделя паузы не должна попасть в итоги. В блоке — свежий дефицит.
     */
    suspend fun resumeProgram(context: Context) = mutateProgram(
        context,
        recalc = true,
        freshBlock = true,
        extra = { it.copy(lastCheckInDate = LocalDate.now().toString()) }
    ) { p, today -> FatBurnProgram.resume(p, today) }

    /** «Остаться на удержании» после итога: калории расхода, чек-ины продолжаются. */
    suspend fun stayOnMaintenance(context: Context) = mutateProgram(
        context,
        recalc = true,
        freshBlock = false,
        extra = { it.copy(lastCheckInDate = LocalDate.now().toString()) }
    ) { p, _ -> FatBurnProgram.stayOnMaintenance(p) }

    /** Закрыть разовую карточку («Режим стал программой» или смена этапа). */
    suspend fun dismissNotice(context: Context) = mutateProgram(context, recalc = false, freshBlock = false) { p, _ ->
        p.copy(notice = null)
    }

    /** Выключить режим: программа заканчивается, снова работают ручные цели. */
    suspend fun stopProgram(context: Context) {
        val appContext = context.applicationContext
        mutex.withLock {
            withContext(Dispatchers.IO) {
                SettingsRepository(NutriLensDatabase.getInstance(appContext).settingsDao()).update {
                    it.copy(targetFatPercent = null).withProgram(null)
                }
            }
        }
        afterChange(appContext)
    }

    private suspend fun mutateProgram(
        context: Context,
        recalc: Boolean,
        freshBlock: Boolean,
        extra: (SettingsEntity) -> SettingsEntity = { it },
        transform: (FatBurnProgram.Program, LocalDate) -> FatBurnProgram.Program
    ) {
        val appContext = context.applicationContext
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val db = NutriLensDatabase.getInstance(appContext)
                val repo = SettingsRepository(db.settingsDao())
                val settings = repo.get()
                val target = settings.targetFatPercent ?: return@withContext
                val program = settings.fatBurnProgramState ?: return@withContext
                val today = LocalDate.now()
                val next = transform(program, today)
                val plan = if (recalc) {
                    buildInput(db, settings, target, today, usePrevious = true)?.let { phasePlan(it, next, freshBlock) }
                } else null
                repo.update { s0 ->
                    extra(if (plan != null) s0.withPlan(plan, target) else s0).withProgram(next)
                }
            }
        }
        afterChange(appContext)
    }

    private fun afterChange(appContext: Context) {
        DataEvents.notifyChanged()
        com.nutrilens.app.widget.WidgetUpdater.refresh(appContext)
        com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(appContext)
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
                runCheckIn(appContext, NutriLensDatabase.getInstance(appContext), weightKg, measurement, heightCm, hunger, energy, sleep)
            }
        }
        DataEvents.notifyChanged()
        com.nutrilens.app.widget.WidgetUpdater.refresh(appContext)
        com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(appContext)
        return result
    }

    private suspend fun runCheckIn(
        context: Context,
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

        // 5б. Сила по экспорту тренировок (3.27): худший из двух сигналов решает за мышцы.
        val previous = db.checkInDao().latest()
        val programNow = settings.fatBurnProgramState
        val strength = strengthOutcome(context, db, today, programNow)
        val muscleSignal = StrengthCheck.worst(review.signal.name.lowercase(), strength?.signal)
        val strengthWorse = strength?.signal != null && muscleSignal != review.signal.name.lowercase()
        val signalNote = if (strengthWorse) strength!!.note else review.signalNote

        // 6. Решение по плану.
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
                signal = FatBurnPlanner.MuscleSignal.valueOf(muscleSignal.uppercase()),
                hungerHighTwice = hunger == 5 && previous?.hunger == 5,
                previousFlatWeeks = previous?.flatWeeks ?: 0
            )
        )

        // 7. Этап программы (3.24): переходы — только здесь (и конец перерыва при открытии).
        // В перерыве, удержании и на паузе дефицитные правила не действуют — калории расхода.
        val programBefore = settings.fatBurnProgramState ?: migrateProgram(db, settings, today)
        var plan = decision.plan
        var flatWeeks = decision.flatWeeks
        var phaseChange: String? = null
        var nextAction = review.action
        var programAfter = programBefore
        if (programBefore != null) {
            val male = BodyComposition.isMale(settings)
            // Плато считаем только внутри блока: в перерыве вес и должен стоять.
            if (!programBefore.inActiveBlock) flatWeeks = 0
            val t = FatBurnProgram.onCheckIn(
                programBefore, today, fatTrendNow(measurements), flatWeeks, weightKg, male,
                strengthDrop = strength?.earlyBreak == true
            )
            val after = t.program
            programAfter = after
            phaseChange = FatBurnProgram.transitionText(t, programBefore)
            if (t.changed) flatWeeks = 0
            plan = when {
                after.inActiveBlock && t.changed -> FatBurnPlanner.compute(input.copy(previousCalories = null, previousDate = null))
                    .copy(note = "Новый блок — свежий дефицит по твоему текущему расходу")
                after.inActiveBlock -> decision.plan
                else -> maintenancePlan(decision.plan, after, weightKg)
            }
            if (!after.inActiveBlock) {
                nextAction = if (after.awaitingChoice) "Выбери на «Сегодня»: остаться на удержании или выключить режим"
                else "Ешь около ${plan.calories.roundToInt()} ккал и держи белок ${plan.protein.roundToInt()} г — это часть плана, не срыв"
            }
        }

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
            muscleSignal = muscleSignal,
            signalNote = signalNote,
            flatWeeks = flatWeeks,
            caloriesBefore = settings.planCalories ?: kcalBefore,
            caloriesAfter = plan.calories,
            proteinBefore = settings.planProtein ?: goalsBefore.protein,
            proteinAfter = plan.protein,
            fatBefore = settings.planFat ?: goalsBefore.fat,
            fatAfter = plan.fat,
            carbsBefore = settings.planCarbs ?: goalsBefore.carbs,
            carbsAfter = plan.carbs,
            note = plan.note,
            nextAction = nextAction,
            avgScore = review.avgScore,
            phaseChange = phaseChange,
            strengthIndex = strength?.indexPercent,
            strengthSignal = strength?.signal,
            strengthNote = strength?.note,
            strengthLifts = strength?.lifts?.takeIf { it.isNotEmpty() }?.let(StrengthCheck::encode),
            strengthTip = strength?.tip
        )
        db.checkInDao().upsert(entity)
        val savedProgram = programAfter
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
            ).let { s0 -> if (savedProgram != null) s0.withProgram(savedProgram) else s0 }
        }
        return entity
    }

    /**
     * Сила за период; null — тренировки ещё ни разу не загружали (карточки «Сила» нет).
     * Сравнение — с начала текущего блока; первая неделя перерыва — разгрузка, не оцениваем.
     */
    private suspend fun strengthOutcome(
        context: Context,
        db: NutriLensDatabase,
        today: LocalDate,
        program: FatBurnProgram.Program?
    ): StrengthCheck.Outcome? {
        val rows = TrainingStore.rows(context)
        if (rows.isEmpty()) return null
        val weights = db.weightDao().all().mapNotNull { w ->
            runCatching { LocalDate.parse(w.date) to w.weight }.getOrNull()
        }.sortedBy { it.first }
        // Вес на дату: последнее взвешивание не позже неё, иначе самое раннее.
        val bodyweightAt = { d: LocalDate -> (weights.lastOrNull { !it.first.isAfter(d) } ?: weights.firstOrNull())?.second }
        val phase = program?.currentPhase
        val blockStart = phase?.startDate?.takeIf { phase.type == FatBurnProgram.PhaseType.BLOCK }
        val deload = program != null && phase?.type == FatBurnProgram.PhaseType.BREAK &&
            FatBurnProgram.daysInPhase(program, today) < 7
        // Прошлые чек-ины, новые первыми; сегодняшний (если чек-ин переделывают) не в счёт.
        val history = db.checkInDao().all().filter { it.date < today.toString() }.sortedByDescending { it.date }
        val report = StrengthAnalyzer.analyze(
            rows = rows,
            today = today,
            blockStart = blockStart,
            bodyweightAt = bodyweightAt,
            previousIndices = history.map { it.strengthIndex },
            deload = deload
        )
        return StrengthCheck.outcome(report, history.firstOrNull()?.strengthSignal)
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
