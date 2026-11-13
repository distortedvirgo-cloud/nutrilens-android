package com.nutrilens.app.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Программа режима «Сжигание жира» (3.24): этапы с обязательными перерывами.
 * Чистый Kotlin без Android, БД и UI — вся логика этапов здесь, чтобы её было
 * легко проверить отдельно и держать одинаковой с веб-версией.
 *
 * Почему этапы: долгий непрерывный дефицит тяжело держать и голове, и телу —
 * растёт голод, падает расход, мышцам хуже. Поэтому программа — это блоки
 * дефицита по 8 недель, между ними обязательные 2 недели на уровне расхода,
 * а после цели — 4 недели удержания, чтобы закрепить результат.
 *
 * Этап меняется только на недельном чек-ине (или когда истекли 14 дней перерыва —
 * при следующем открытии приложения): человек видит, почему всё поменялось.
 */
object FatBurnProgram {
    /** Длина блока дефицита, недель. Последний блок может быть короче, но не меньше 3 недель. */
    const val BLOCK_WEEKS = 8
    const val MIN_BLOCK_WEEKS = 3

    /** Перерыв на уровне расхода между блоками — обязательный, 14 дней. */
    const val BREAK_DAYS = 14
    const val BREAK_WEEKS = 2

    /** Удержание после цели, недель. */
    const val MAINTENANCE_WEEKS = 4

    /** Цель считается достигнутой, когда тренд жира ≤ цель + 0,5 п.п. (лента ошибается сильнее). */
    const val TARGET_TOLERANCE = 0.5

    /** Вес стоит 3 недели подряд — перерыв начинается досрочно. */
    const val PLATEAU_WEEKS = 3

    /**
     * Чек-ин раз в неделю попадает на любой день: блок «по времени» закрываем,
     * если до планового конца осталось 3 дня и меньше, — иначе он тянулся бы лишнюю неделю.
     */
    const val END_SLACK_DAYS = 3

    /** Диапазон прогноза: от 0,9 до 1,3 оценки — тело не линейка. */
    const val RANGE_LOW = 0.9
    const val RANGE_HIGH = 1.3

    /** Нижняя граница цели: ниже — уже риск для здоровья, а не рельеф. */
    const val MIN_TARGET_MALE = 10
    const val MIN_TARGET_FEMALE = 18

    /** Ниже этих значений цель допустима, но показываем спокойное предупреждение. */
    const val WARN_TARGET_MALE = 12
    const val WARN_TARGET_FEMALE = 20

    private const val MAX_WEEKS = 156

    enum class PhaseType { BLOCK, BREAK, MAINTENANCE }

    /**
     * Почему этап закончился: TIME — истёк срок, TARGET — промежуточная цель досрочно,
     * PLATEAU — вес стоял 3 недели, FINAL — достигнута итоговая цель, DONE — удержание пройдено.
     */
    enum class EndReason { TIME, TARGET, PLATEAU, FINAL, DONE, STRENGTH }

    data class Phase(
        val type: PhaseType,
        /** Номер блока (1, 2, 3…); у перерыва — номер блока, после которого он идёт; у удержания — 1. */
        val index: Int,
        val plannedWeeks: Int,
        /** Цель блока (у последнего — итоговая); у перерыва — null. */
        val targetFat: Double? = null,
        /** null — этап ещё не начался. */
        val startDate: LocalDate? = null,
        val endedDate: LocalDate? = null,
        val endReason: EndReason? = null,
        /** Тренд жира на старте этапа — для бейджа «21% → 17%»; null — замеров не было. */
        val startFat: Double? = null
    )

    data class Program(
        val startDate: LocalDate,
        val startFat: Double,
        val startWeight: Double,
        val target: Double,
        val phases: List<Phase>,
        /** Индекс текущего этапа в [phases]. */
        val current: Int,
        /** Пауза: с какого дня; null — программа идёт. */
        val pausedSince: LocalDate? = null,
        /** Дней паузы внутри текущего этапа — они не идут в срок этапа. */
        val pausedDays: Int = 0,
        /** Удержание пройдено — показываем итог и выбор «остаться / выключить». */
        val completed: Boolean = false,
        /** После итога выбрал «Остаться на удержании». */
        val stayOnMaintenance: Boolean = false,
        /** Программа собрана автоматически для тех, кто был в режиме до 3.24. */
        val migrated: Boolean = false,
        /**
         * Разовая карточка на «Сегодня» до «Понятно»: «Режим стал программой» после перехода
         * со старой версии или смена этапа при открытии приложения (перерыв закончился). null — нет.
         */
        val notice: Notice? = null
    ) {
        val currentPhase: Phase? get() = phases.getOrNull(current)
        val paused: Boolean get() = pausedSince != null
        val blockCount: Int get() = phases.count { it.type == PhaseType.BLOCK }

        /** Дефицит и оранжевая тема — только в активном блоке: не пауза, не перерыв, не удержание. */
        val inActiveBlock: Boolean
            get() = !paused && !completed && currentPhase?.type == PhaseType.BLOCK

        /** Итог показан и человек ещё не выбрал, что дальше. */
        val awaitingChoice: Boolean get() = completed && !stayOnMaintenance

        /** Сейчас едим на уровне расхода: перерыв, удержание, пауза или программа завершена. */
        val atMaintenanceCalories: Boolean get() = !inActiveBlock
    }

    data class Notice(val title: String, val text: String)

    /** Что случилось на чек-ине (или при открытии приложения). */
    enum class Event { BLOCK_TIME, BLOCK_TARGET, PLATEAU, FINAL_TARGET, BREAK_DONE, MAINTENANCE_DONE, STRENGTH }

    data class Transition(
        val program: Program,
        /** null — этап не поменялся. */
        val event: Event? = null,
        val from: Phase? = null,
        val to: Phase? = null
    ) {
        val changed: Boolean get() = event != null
    }

    // ---------- Создание ----------

    /**
     * Сколько недель дефицита до цели при темпе планировщика (0,6% веса в неделю,
     * ближе к цели 0,4%). Считаем по неделям: уходит жир, сухая масса та же. 0 — цель уже есть.
     */
    fun estimateWeeks(weightKg: Double, fatPercent: Double, target: Double, male: Boolean): Int {
        if (weightKg <= 0.0 || fatPercent <= target) return 0
        var weight = weightKg
        var fatKg = weightKg * fatPercent / 100
        val lean = weightKg - fatKg
        var weeks = 0
        while (weeks < MAX_WEEKS) {
            val pct = fatKg / weight * 100
            if (pct <= target) break
            fatKg -= weight * FatBurnPlanner.weeklyRate(pct, target, male)
            weight = lean + fatKg
            weeks++
        }
        return weeks
    }

    /**
     * Блоки и перерывы от [fromFat] до [target]: блоки по 8 недель (последний короче,
     * но не меньше 3), промежуточные цели — поровну, с шагом 0,5%: 25 → 21 → 17 → 13.
     * Между блоками — перерыв 2 недели. Удержание в список не входит.
     */
    fun planBlocks(
        fromFat: Double,
        target: Double,
        weightKg: Double,
        male: Boolean,
        firstBlockIndex: Int = 1
    ): List<Phase> {
        if (fromFat - target <= TARGET_TOLERANCE) return emptyList()
        val weeks = estimateWeeks(weightKg, fromFat, target, male).coerceAtLeast(MIN_BLOCK_WEEKS)
        val n = ceil(weeks / BLOCK_WEEKS.toDouble()).toInt().coerceAtLeast(1)
        val out = mutableListOf<Phase>()
        var prevTarget = fromFat
        for (i in 1..n) {
            val length = if (i < n) BLOCK_WEEKS else (weeks - BLOCK_WEEKS * (n - 1)).coerceIn(MIN_BLOCK_WEEKS, BLOCK_WEEKS)
            val raw = fromFat - (fromFat - target) * i / n
            // Промежуточная цель — шаг 0,5%, строго между прошлой и итоговой.
            val blockTarget = if (i == n) target
            else roundHalf(raw).coerceIn(target + TARGET_TOLERANCE, (prevTarget - TARGET_TOLERANCE).coerceAtLeast(target + TARGET_TOLERANCE))
            val blockIndex = firstBlockIndex + i - 1
            out += Phase(PhaseType.BLOCK, blockIndex, length, blockTarget)
            if (i < n) out += Phase(PhaseType.BREAK, blockIndex, BREAK_WEEKS)
            prevTarget = blockTarget
        }
        return out
    }

    /** Новая программа: первый этап начинается [startDate]. Уже у цели — сразу удержание. */
    fun create(startDate: LocalDate, startFat: Double, target: Double, weightKg: Double, male: Boolean): Program {
        val blocks = planBlocks(startFat, target, weightKg, male)
        val phases = (blocks + Phase(PhaseType.MAINTENANCE, 1, MAINTENANCE_WEEKS, target)).toMutableList()
        phases[0] = phases[0].copy(startDate = startDate, startFat = startFat)
        return Program(
            startDate = startDate,
            startFat = startFat,
            startWeight = weightKg,
            target = target,
            phases = phases,
            current = 0
        )
    }

    /**
     * Сменили итоговую цель посреди программы: пройденные этапы остаются, текущий блок
     * и всё после собираются заново от процента на старте блока (в перерыве — от текущего).
     * В удержании и после итога меняется только цель.
     */
    fun retarget(p: Program, newTarget: Double, currentFat: Double?, weightKg: Double, male: Boolean): Program {
        val phase = p.currentPhase ?: return p.copy(target = newTarget)
        if (phase.type == PhaseType.MAINTENANCE || p.completed) {
            return p.copy(
                target = newTarget,
                phases = p.phases.map { if (it.type == PhaseType.MAINTENANCE) it.copy(targetFat = newTarget) else it }
            )
        }
        val done = p.phases.subList(0, p.current)
        val firstIndex = (done.filter { it.type == PhaseType.BLOCK }.maxOfOrNull { it.index } ?: 0) + 1
        val maintenance = Phase(PhaseType.MAINTENANCE, 1, MAINTENANCE_WEEKS, newTarget)
        val phases: List<Phase> = if (phase.type == PhaseType.BLOCK) {
            val from = phase.startFat ?: currentFat ?: p.startFat
            val fresh = planBlocks(from, newTarget, weightKg, male, firstIndex)
            // Текущий блок сохраняет дату старта и прожитые дни, меняются длина и цель.
            val head = fresh.firstOrNull()?.let {
                phase.copy(plannedWeeks = it.plannedWeeks, targetFat = it.targetFat)
            } ?: phase.copy(targetFat = newTarget)
            done + head + fresh.drop(1) + maintenance
        } else {
            done + phase + planBlocks(currentFat ?: p.startFat, newTarget, weightKg, male, firstIndex) + maintenance
        }
        return p.copy(target = newTarget, phases = phases)
    }

    // ---------- Время в этапе ----------

    /** Дней в текущем этапе без дней паузы. */
    fun daysInPhase(p: Program, today: LocalDate): Int {
        val start = p.currentPhase?.startDate ?: return 0
        val raw = ChronoUnit.DAYS.between(start, today).toInt()
        val since = p.pausedSince
        val pausedNow = if (since != null) ChronoUnit.DAYS.between(since, today).toInt().coerceAtLeast(0) else 0
        return (raw - p.pausedDays - pausedNow).coerceAtLeast(0)
    }

    /** Неделя этапа, 1..plannedWeeks (этап мог чуть затянуться до чек-ина — неделю не перерастаем). */
    fun weekOfPhase(p: Program, today: LocalDate): Int {
        val planned = p.currentPhase?.plannedWeeks ?: 1
        return (daysInPhase(p, today) / 7 + 1).coerceIn(1, planned.coerceAtLeast(1))
    }

    /** Сколько дней перерыва осталось (0 — перерыв окончен). */
    fun breakDaysLeft(p: Program, today: LocalDate): Int = (BREAK_DAYS - daysInPhase(p, today)).coerceAtLeast(0)

    /** Номер блока «2 из 3» — по порядку среди блоков. */
    fun blockOrdinal(p: Program, phase: Phase): Int =
        p.phases.filter { it.type == PhaseType.BLOCK }.indexOfFirst { it.index == phase.index } + 1

    // ---------- Переходы ----------

    /**
     * Решение чек-ина по этапу. [trendFat] — сглаженный тренд процента жира (null — замеров нет),
     * [flatWeeks] — недель подряд «вес стоит» при полном дневнике (из FatBurnPlanner.checkIn).
     * Порядок важен: итоговая цель → промежуточная → плато → срок.
     */
    fun onCheckIn(
        p: Program,
        today: LocalDate,
        trendFat: Double?,
        flatWeeks: Int,
        weightKg: Double,
        male: Boolean,
        /** Сила падает (красный сигнал) второй чек-ин подряд — мышцам нужен перерыв раньше (3.27). */
        strengthDrop: Boolean = false
    ): Transition {
        if (p.paused || p.completed) return Transition(p)
        val phase = p.currentPhase ?: return Transition(p)
        val days = daysInPhase(p, today)
        return when (phase.type) {
            PhaseType.BLOCK -> {
                val blockTarget = phase.targetFat
                when {
                    trendFat != null && trendFat <= p.target + TARGET_TOLERANCE ->
                        toMaintenance(p, today, EndReason.FINAL, trendFat)
                    trendFat != null && blockTarget != null && trendFat <= blockTarget ->
                        toBreak(p, today, EndReason.TARGET, Event.BLOCK_TARGET, trendFat, weightKg, male)
                    flatWeeks >= PLATEAU_WEEKS ->
                        toBreak(p, today, EndReason.PLATEAU, Event.PLATEAU, trendFat, weightKg, male)
                    strengthDrop ->
                        toBreak(p, today, EndReason.STRENGTH, Event.STRENGTH, trendFat, weightKg, male)
                    days >= phase.plannedWeeks * 7 - END_SLACK_DAYS ->
                        toBreak(p, today, EndReason.TIME, Event.BLOCK_TIME, trendFat, weightKg, male)
                    else -> Transition(p)
                }
            }
            PhaseType.BREAK -> when {
                // Цель пришла и в перерыве — сразу удержание, новый блок уже не нужен.
                trendFat != null && trendFat <= p.target + TARGET_TOLERANCE -> toMaintenance(p, today, EndReason.FINAL, trendFat)
                days >= BREAK_DAYS -> afterBreak(p, today, trendFat)
                else -> Transition(p)
            }
            PhaseType.MAINTENANCE ->
                if (days >= MAINTENANCE_WEEKS * 7 - END_SLACK_DAYS) finish(p, today) else Transition(p)
        }
    }

    /** При открытии приложения: перерыв закончился ровно через 14 дней — следующий блок, не дожидаясь чек-ина. */
    fun onAppOpen(p: Program, today: LocalDate, trendFat: Double?, weightKg: Double, male: Boolean): Transition {
        if (p.paused || p.completed) return Transition(p)
        val phase = p.currentPhase ?: return Transition(p)
        if (phase.type != PhaseType.BREAK) return Transition(p)
        return if (daysInPhase(p, today) >= BREAK_DAYS) afterBreak(p, today, trendFat) else Transition(p)
    }

    /**
     * Блок закончился — перерыв. Блок был последним, а до цели не дошли: вставляем
     * перерыв и ещё один блок до цели (длина — по текущему проценту, 3–8 недель).
     */
    private fun toBreak(
        p: Program,
        today: LocalDate,
        reason: EndReason,
        event: Event,
        trendFat: Double?,
        weightKg: Double,
        male: Boolean
    ): Transition {
        val phase = p.currentPhase ?: return Transition(p)
        val ended = phase.copy(endedDate = today, endReason = reason)
        val phases = p.phases.toMutableList()
        phases[p.current] = ended
        val next = phases.getOrNull(p.current + 1)
        if (next == null || next.type != PhaseType.BREAK) {
            val from = trendFat ?: phase.targetFat ?: p.target
            val weeks = estimateWeeks(weightKg, from, p.target, male).coerceIn(MIN_BLOCK_WEEKS, BLOCK_WEEKS)
            val lastIndex = phases.filter { it.type == PhaseType.BLOCK }.maxOfOrNull { it.index } ?: phase.index
            phases.add(p.current + 1, Phase(PhaseType.BREAK, phase.index, BREAK_WEEKS))
            phases.add(p.current + 2, Phase(PhaseType.BLOCK, lastIndex + 1, weeks, p.target))
        }
        val started = phases[p.current + 1].copy(startDate = today, startFat = trendFat)
        phases[p.current + 1] = started
        val program = p.copy(phases = phases, current = p.current + 1, pausedDays = 0)
        return Transition(program, event, ended, started)
    }

    /** Итоговая цель достигнута: будущие блоки уже не нужны — сразу удержание. */
    private fun toMaintenance(p: Program, today: LocalDate, reason: EndReason, trendFat: Double? = null): Transition {
        val phase = p.currentPhase ?: return Transition(p)
        val ended = phase.copy(endedDate = today, endReason = reason)
        val kept = p.phases.subList(0, p.current).toMutableList()
        kept += ended
        val maintenance = Phase(PhaseType.MAINTENANCE, 1, MAINTENANCE_WEEKS, p.target, startDate = today, startFat = trendFat)
        kept += maintenance
        val program = p.copy(phases = kept, current = kept.size - 1, pausedDays = 0)
        return Transition(program, Event.FINAL_TARGET, ended, maintenance)
    }

    /** Перерыв окончен: следующий блок со свежим дефицитом (блоков нет — удержание). */
    private fun afterBreak(p: Program, today: LocalDate, trendFat: Double?): Transition {
        val phase = p.currentPhase ?: return Transition(p)
        val next = p.phases.getOrNull(p.current + 1)
        if (next == null || next.type != PhaseType.BLOCK) {
            return toMaintenance(p, today, EndReason.TIME, trendFat).copy(event = Event.BREAK_DONE)
        }
        val ended = phase.copy(endedDate = today, endReason = EndReason.TIME)
        val started = next.copy(startDate = today, startFat = trendFat)
        val phases = p.phases.toMutableList()
        phases[p.current] = ended
        phases[p.current + 1] = started
        val program = p.copy(phases = phases, current = p.current + 1, pausedDays = 0)
        return Transition(program, Event.BREAK_DONE, ended, started)
    }

    /** Удержание пройдено — программа завершена, дальше выбор человека. */
    private fun finish(p: Program, today: LocalDate): Transition {
        val phase = p.currentPhase ?: return Transition(p)
        val ended = phase.copy(endedDate = today, endReason = EndReason.DONE)
        val phases = p.phases.toMutableList()
        phases[p.current] = ended
        return Transition(p.copy(phases = phases, completed = true), Event.MAINTENANCE_DONE, ended, null)
    }

    // ---------- Пауза ----------

    fun pause(p: Program, today: LocalDate): Program = if (p.paused) p else p.copy(pausedSince = today)

    /** Продолжить: дни паузы не идут в срок этапа. */
    fun resume(p: Program, today: LocalDate): Program {
        val since = p.pausedSince ?: return p
        val days = ChronoUnit.DAYS.between(since, today).toInt().coerceAtLeast(0)
        return p.copy(pausedSince = null, pausedDays = p.pausedDays + days)
    }

    /** «Остаться на удержании» после итога: удержание продолжается без срока. */
    fun stayOnMaintenance(p: Program): Program = p.copy(stayOnMaintenance = true)

    // ---------- План на уровне расхода ----------

    data class Macros(val calories: Double, val protein: Double, val fat: Double, val carbs: Double)

    /**
     * Перерыв, удержание и пауза: калории = расход (по данным, если их хватает, иначе по формуле),
     * белок — то же правило, что в дефиците, жиры 0,8 г/кг (не меньше 45), остальное — углеводы.
     */
    fun maintenanceMacros(tdee: Double, protein: Double, weightKg: Double, floor: Double = 0.0): Macros {
        val calories = round10(tdee).coerceAtLeast(round10(floor))
        val fat = round5(0.8 * weightKg).coerceAtLeast(45.0)
        val carbs = round5((calories - protein * 4 - fat * 9) / 4).coerceAtLeast(50.0)
        return Macros(calories, protein, fat, carbs)
    }

    // ---------- Прогноз и таймлайн ----------

    data class Forecast(val weeksLow: Int, val weeksHigh: Int, val earliest: LocalDate, val latest: LocalDate)

    /**
     * Когда придём к цели: оставшиеся недели плана (блоки и перерывы, без удержания)
     * × 0,9…1,3 — жир уходит неровно, честнее вилка. null — уже удержание или программа завершена.
     */
    fun forecast(p: Program, today: LocalDate): Forecast? {
        if (p.completed) return null
        val phase = p.currentPhase ?: return null
        if (phase.type == PhaseType.MAINTENANCE) return null
        val days = daysInPhase(p, today)
        var weeks = if (phase.type == PhaseType.BREAK) (BREAK_DAYS - days).coerceAtLeast(0) / 7.0
        else (phase.plannedWeeks - days / 7.0).coerceAtLeast(1.0)
        for (f in p.phases.drop(p.current + 1)) if (f.type != PhaseType.MAINTENANCE) weeks += f.plannedWeeks
        val low = (weeks * RANGE_LOW).roundToInt().coerceAtLeast(1)
        val high = (weeks * RANGE_HIGH).roundToInt().coerceAtLeast(low)
        return Forecast(low, high, today.plusWeeks(low.toLong()), today.plusWeeks(high.toLong()))
    }

    /** «март – апрель 2027», «март 2027», «декабрь 2026 – февраль 2027». */
    fun rangeText(f: Forecast): String {
        val a = f.earliest
        val b = f.latest
        return when {
            a.year == b.year && a.monthValue == b.monthValue -> "${MONTHS[a.monthValue - 1]} ${a.year}"
            a.year == b.year -> "${MONTHS[a.monthValue - 1]} – ${MONTHS[b.monthValue - 1]} ${b.year}"
            else -> "${MONTHS[a.monthValue - 1]} ${a.year} – ${MONTHS[b.monthValue - 1]} ${b.year}"
        }
    }

    enum class SegmentState { DONE, CURRENT, FUTURE }

    data class Segment(val phase: Phase, val start: LocalDate, val end: LocalDate, val state: SegmentState) {
        val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt().coerceAtLeast(1)
    }

    /**
     * Этапы на шкале времени: прошлые — по фактическим датам, текущий — по плану
     * (с учётом паузы), будущие — друг за другом по плановой длине.
     */
    fun timeline(p: Program, today: LocalDate): List<Segment> {
        val out = mutableListOf<Segment>()
        var cursor = p.startDate
        p.phases.forEachIndexed { i, phase ->
            val plannedDays = if (phase.type == PhaseType.BREAK) BREAK_DAYS else phase.plannedWeeks * 7
            val start = phase.startDate ?: cursor
            val ended = phase.endedDate
            val state = when {
                ended != null || i < p.current -> SegmentState.DONE
                i == p.current -> SegmentState.CURRENT
                else -> SegmentState.FUTURE
            }
            val end = when {
                ended != null -> ended
                state == SegmentState.CURRENT -> {
                    val shift = p.pausedDays + (p.pausedSince?.let { ChronoUnit.DAYS.between(it, today).toInt().coerceAtLeast(0) } ?: 0)
                    val planned = start.plusDays((plannedDays + shift).toLong())
                    // Этап затянулся до чек-ина — шкала тянется до сегодня.
                    if (planned.isBefore(today)) today else planned
                }
                else -> start.plusDays(plannedDays.toLong())
            }
            out += Segment(phase, start, if (end.isAfter(start)) end else start.plusDays(1), state)
            cursor = out.last().end
        }
        return out
    }

    /** Где «сегодня» на шкале, 0..1. */
    fun todayFraction(segments: List<Segment>, today: LocalDate): Float {
        val first = segments.firstOrNull() ?: return 0f
        val last = segments.last()
        val total = ChronoUnit.DAYS.between(first.start, last.end).toDouble()
        if (total <= 0) return 0f
        return (ChronoUnit.DAYS.between(first.start, today) / total).toFloat().coerceIn(0f, 1f)
    }

    // ---------- Тексты ----------

    /** Бейдж на «Сегодня»: «🔥 Блок 2 из 3 · неделя 5 · 21% → 17%», «Перерыв · ещё 9 дней»… */
    fun badgeText(p: Program, today: LocalDate, currentFat: Double?): String {
        if (p.paused) return "Пауза"
        if (p.completed) return if (p.stayOnMaintenance) "Удержание" else "Программа завершена"
        val phase = p.currentPhase ?: return "🔥 Сжигание жира"
        return when (phase.type) {
            PhaseType.BLOCK -> {
                val week = weekOfPhase(p, today)
                val from = phase.startFat ?: currentFat ?: p.startFat
                val to = phase.targetFat ?: p.target
                "🔥 Блок ${blockOrdinal(p, phase)} из ${p.blockCount} · неделя $week · ${from.roundToInt()}% → ${fmt(to)}%"
            }
            PhaseType.BREAK -> {
                val left = breakDaysLeft(p, today).coerceAtLeast(1)
                "Перерыв · ещё $left ${plural(left, "день", "дня", "дней")}"
            }
            PhaseType.MAINTENANCE -> "Удержание · неделя ${weekOfPhase(p, today)}"
        }
    }

    /** Одна строка «что делать в этом этапе» для карточки на «Сегодня». */
    fun phaseHint(p: Program, deficitKcal: Int?, protein: Int?, calories: Int?): String {
        val proteinText = protein?.let { ", белок $it г" } ?: ""
        if (p.paused) return "Программа на паузе: ешь на уровне расхода, чек-инов нет. Вернёшься — продолжим с того же места"
        if (p.completed) return "Ешь на уровне расхода$proteinText — держим результат"
        val phase = p.currentPhase ?: return ""
        return when (phase.type) {
            PhaseType.BLOCK ->
                if (deficitKcal != null && deficitKcal > 0) "Дефицит $deficitKcal ккал$proteinText"
                else "Ешь по плану$proteinText"
            PhaseType.BREAK -> "Ешь на уровне расхода — это часть плана, не срыв" +
                (calories?.let { " (≈ $it ккал)" } ?: "")
            PhaseType.MAINTENANCE -> "Удержание: ешь на уровне расхода$proteinText — закрепляем результат"
        }
    }

    /** Название этапа: «Блок 2», «Перерыв», «Удержание». */
    fun phaseTitle(p: Program, phase: Phase): String = when (phase.type) {
        PhaseType.BLOCK -> "Блок ${blockOrdinal(p, phase)}"
        PhaseType.BREAK -> "Перерыв"
        PhaseType.MAINTENANCE -> "Удержание"
    }

    /** Заметный текст смены этапа на экране итогов чек-ина; null — этап не менялся. */
    fun transitionText(t: Transition, before: Program): String? {
        val event = t.event ?: return null
        val from = t.from
        val blockName = if (from != null && from.type == PhaseType.BLOCK) "Блок ${blockOrdinal(before, from)}" else "Блок"
        return when (event) {
            Event.BLOCK_TIME -> {
                val target = from?.targetFat
                val added = t.program.blockCount > before.blockCount
                "$blockName завершён → 2 недели перерыва" +
                    (target?.let { ". Цель блока ${fmt(it)}% пока не догнали — ничего страшного: перерыв всё равно нужен, после него продолжим" } ?: "") +
                    (if (added) ". Добавил ещё один блок до цели ${fmt(before.target)}%" else "")
            }
            Event.BLOCK_TARGET -> "$blockName завершён раньше срока — цель ${fmt(from?.targetFat ?: before.target)}% есть → 2 недели перерыва"
            Event.PLATEAU -> "Вес стоит 3 недели → перерыв начинается сейчас: 2 недели на уровне расхода, потом свежий дефицит"
            Event.FINAL_TARGET -> "Цель ${fmt(before.target)}% достигнута → 4 недели удержания"
            Event.BREAK_DONE -> {
                val to = t.to
                if (to != null && to.type == PhaseType.BLOCK) "Перерыв окончен → Блок ${blockOrdinal(t.program, to)}: свежий дефицит"
                else "Перерыв окончен → удержание: цель уже достигнута"
            }
            Event.MAINTENANCE_DONE -> "Удержание пройдено — программа завершена"
            Event.STRENGTH -> "Сила падает вторую неделю → перерыв начинается сейчас: 2 недели на уровне расхода, чтобы сберечь мышцы"
        }
    }

    /** Одна строка для ИИ-помощника: где человек в программе, чтобы советы не спорили с этапом. */
    fun coachLine(p: Program, today: LocalDate): String {
        val phase = p.currentPhase ?: return ""
        val head = "Программа с ${p.startDate}: старт ${fmt(p.startFat)}% → цель ${fmt(p.target)}%, " +
            "блоки дефицита до 8 недель с обязательными перерывами 2 недели на уровне расхода."
        val now = when {
            p.paused -> " Сейчас пауза с ${p.pausedSince} — питание на уровне расхода, чек-инов нет."
            p.completed -> " Программа завершена, сейчас удержание на уровне расхода."
            phase.type == PhaseType.BLOCK ->
                " Сейчас ${phaseTitle(p, phase).lowercase()} из ${p.blockCount}, неделя ${weekOfPhase(p, today)} из ${phase.plannedWeeks}, цель блока ${fmt(phase.targetFat ?: p.target)}%."
            phase.type == PhaseType.BREAK ->
                " Сейчас перерыв, осталось ${breakDaysLeft(p, today)} дн.: есть на уровне расхода — часть плана, не срыв."
            else -> " Сейчас удержание, неделя ${weekOfPhase(p, today)} из ${phase.plannedWeeks}: на уровне расхода, белок прежний."
        }
        return head + now
    }

    // ---------- Внутреннее ----------

    private val MONTHS = arrayOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    )

    fun roundHalf(v: Double): Double = (v * 2).roundToInt() / 2.0

    private fun round10(v: Double): Double = ((v / 10).roundToInt() * 10).toDouble()

    private fun round5(v: Double): Double = ((v / 5).roundToInt() * 5).toDouble()

    /** «21», «20,5» — без лишнего нуля, с запятой. */
    fun fmt(v: Double): String {
        val r = (v * 10).roundToInt() / 10.0
        return if (r % 1.0 == 0.0) r.toInt().toString() else r.toString().replace('.', ',')
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && (m100 < 12 || m100 > 14) -> few
            else -> many
        }
    }
}
