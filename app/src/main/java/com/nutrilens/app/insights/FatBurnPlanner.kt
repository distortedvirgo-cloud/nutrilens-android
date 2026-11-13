package com.nutrilens.app.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Адаптивный план режима «Сжигание жира»: калории и БЖУ считаются сами и
 * подстраиваются под реальные вес и еду. Чистый Kotlin без Android, БД и UI —
 * все константы здесь, чтобы алгоритм было легко подкрутить и проверить.
 *
 * Идея: формула (Кэтча — Макардла) даёт только стартовую оценку расхода.
 * Когда накопилось 2–3 недели записей, расход считается по факту: сколько ел
 * минус то, что ушло весом (1 кг ≈ 7700 ккал). Так план «видит» твой обмен,
 * а не среднего человека.
 */
object FatBurnPlanner {
    /**
     * Неделя между пересчётами: вес скачет от воды, дневные решения — шум.
     * С 3.22 план сам по таймеру не пересчитывается — только на недельном чек-ине
     * (см. [checkIn]), при включении режима и смене цели.
     */
    const val RECALC_DAYS = 7L

    /** Окно данных для фактического расхода. */
    const val WINDOW_DAYS = 21L
    const val MIN_LOGGED_DAYS = 10
    const val MIN_WEIGH_INS = 4
    const val MIN_SPAN_DAYS = 10L

    /** День с записями меньше 800 ккал — скорее недозаписан, в расчёт не идёт. */
    const val MIN_DAY_KCAL = 800.0
    const val KCAL_PER_KG = 7700.0

    /** После первого плана калории меняются не больше чем на ±150 за пересчёт. */
    const val MAX_STEP_KCAL = 150.0
    const val DEFAULT_ACTIVITY = 1.4

    // Темп: 0,6% веса в неделю, ближе к цели (или уже сухо) — бережнее, 0,4%.
    private const val RATE_NORMAL = 0.006
    private const val RATE_GENTLE = 0.004

    /** Значение на дату: вес (кг) или калории за день. */
    data class DayValue(val date: LocalDate, val value: Double)

    data class Input(
        val male: Boolean,
        /** Формуле Кэтча — Макардла рост не нужен; оставлен для будущих формул. */
        val heightCm: Double?,
        val weightKg: Double,
        val weights: List<DayValue>,
        val dailyCalories: List<DayValue>,
        /** Последний процент жира из замеров; null — замеров нет. */
        val fatPercent: Double?,
        val targetFatPercent: Double,
        val activity: Double = DEFAULT_ACTIVITY,
        /** Калории прошлого плана; null — это первый план. */
        val previousCalories: Double? = null,
        val previousDate: LocalDate? = null,
        val today: LocalDate
    )

    /** Хватает ли данных для фактического расхода и чего не хватает. */
    data class DataStatus(
        val loggedDays: Int,
        val weighIns: Int,
        val spanDays: Int,
        val enough: Boolean
    ) {
        /** Что ещё нужно, по-русски; null — данных хватает. */
        val missingText: String?
            get() {
                if (enough) return null
                val parts = mutableListOf<String>()
                val needDays = MIN_LOGGED_DAYS - loggedDays
                if (needDays > 0) parts += "ещё $needDays ${plural(needDays, "день", "дня", "дней")} с записями еды"
                val needWeighIns = MIN_WEIGH_INS - weighIns
                if (needWeighIns > 0) {
                    parts += "ещё $needWeighIns ${plural(needWeighIns, "взвешивание", "взвешивания", "взвешиваний")}"
                } else if (spanDays < MIN_SPAN_DAYS) {
                    parts += "взвешивания хотя бы за $MIN_SPAN_DAYS дней (сейчас $spanDays)"
                }
                return if (parts.isEmpty()) null else "Нужно " + parts.joinToString(" и ")
            }
    }

    data class Plan(
        val calories: Double,
        val protein: Double,
        val fat: Double,
        val carbs: Double,
        val tdee: Double,
        val bmr: Double,
        val baseTdee: Double,
        /** true — расход по твоим данным, false — по формуле. */
        val observed: Boolean,
        /** Целевой темп, % веса в неделю. */
        val targetWeeklyPercent: Double,
        /** Фактический темп потери, % веса в неделю (плюс — худеешь); null — мало данных. */
        val actualWeeklyPercent: Double?,
        val note: String,
        val date: LocalDate,
        val nextDate: LocalDate,
        val data: DataStatus,
        /** Процента жира ещё нет — взяли типичный (25% мужчинам, 32% женщинам). */
        val assumedFat: Boolean,
        /** Безопасный минимум калорий (обмен в покое, не ниже 1500/1200) — ниже план не опускается. */
        val floor: Double = 0.0
    )

    /** Пора ли пересчитать: плана нет, прошла неделя или дата из будущего (сменили часы). */
    fun isDue(planDate: LocalDate?, today: LocalDate): Boolean {
        if (planDate == null) return true
        val days = ChronoUnit.DAYS.between(planDate, today)
        return days >= RECALC_DAYS || days < 0
    }

    /** Через сколько дней следующий пересчёт (0 — уже сегодня). */
    fun daysUntilNext(planDate: LocalDate, today: LocalDate): Int =
        (RECALC_DAYS - ChronoUnit.DAYS.between(planDate, today)).coerceIn(0L, RECALC_DAYS).toInt()

    /**
     * Активность из текста «О себе»: онбординг пишет «активность низкая/средняя/высокая»
     * (те же множители, что в его норме). Не нашли — 1,4.
     */
    fun activityFromText(userContext: String): Double {
        val m = Regex("активность\\s+(низкая|средняя|высокая)", RegexOption.IGNORE_CASE).find(userContext)
            ?: return DEFAULT_ACTIVITY
        return when (m.groupValues[1].lowercase()) {
            "низкая" -> 1.3
            "средняя" -> 1.5
            "высокая" -> 1.7
            else -> DEFAULT_ACTIVITY
        }
    }

    /** Достаточно ли данных за окно — без расчёта плана (для подсказок в UI). */
    fun dataStatus(input: Input): DataStatus = window(input).status

    fun compute(input: Input): Plan {
        val w = input.weightKg
        val assumedFat = input.fatPercent == null
        val fatPct = input.fatPercent ?: if (input.male) 25.0 else 32.0

        // 1–2. Сухая масса и базовый расход по формуле.
        val lean = w * (1 - fatPct / 100)
        val bmr = 370 + 21.6 * lean
        val baseTdee = bmr * input.activity

        // 3. Фактический расход — только когда данных достаточно.
        val win = window(input)
        val slope = win.slopeKgPerDay
        val meanKcal = win.meanKcal
        val observed = win.status.enough && slope != null && meanKcal != null
        val tdee = if (observed && slope != null && meanKcal != null) {
            // Клэмп защищает от недозаписанной еды и скачков воды.
            (meanKcal - slope * KCAL_PER_KG).coerceIn(baseTdee * 0.75, baseTdee * 1.30)
        } else baseTdee

        // 4. Целевой темп и дефицит.
        val nearTarget = fatPct - input.targetFatPercent <= 3.0 || fatPct < (if (input.male) 15.0 else 23.0)
        val rate = if (nearTarget) RATE_GENTLE else RATE_NORMAL
        val deficit = (w * rate * KCAL_PER_KG / 7).coerceIn(tdee * 0.15, tdee * 0.25)

        // 5. Калории не ниже обмена в покое и бытового минимума.
        val floor = maxOf(bmr, if (input.male) 1500.0 else 1200.0)
        var calories = (tdee - deficit).coerceAtLeast(floor)

        // 6. Защита темпа: слишком быстро — теряем мышцы; стоит — чуть урезаем.
        val actualWeekly = if (win.status.enough && slope != null) -slope * 7 / w * 100 else null
        var guardNote: String? = null
        if (actualWeekly != null) {
            if (actualWeekly > 1.0) {
                calories += 100
                guardNote = "Вес уходит слишком быстро — добавил калорий, чтобы сберечь мышцы"
            } else if (actualWeekly < 0.2) {
                calories -= 50
                guardNote = "Вес стоит — немного снизил калории"
            }
        }

        // 7. Стабильность: после первого плана — не больше ±150 ккал за пересчёт.
        val prev = input.previousCalories?.takeIf { it > 0 }
        var limited = false
        // Первый план по данным не поднимаем резко выше того, что человек реально ел:
        // «быстрое похудение» чаще значит неполный дневник, а не огромный расход.
        if (prev == null && observed && meanKcal != null) calories = minOf(calories, meanKcal + MAX_STEP_KCAL)
        if (prev != null) {
            val bounded = calories.coerceIn(prev - MAX_STEP_KCAL, prev + MAX_STEP_KCAL)
            limited = abs(bounded - calories) > 1.0
            calories = bounded
        }
        // Нижняя граница важнее плавности: безопасность прежде всего.
        calories = round10(calories.coerceAtLeast(floor))

        // 8. БЖУ: белок от сухой массы держит мышцы, жиры — для гормонов, остальное — углеводы.
        val protein = round5(2.6 * lean)
        val fat = round5(0.8 * w).coerceAtLeast(45.0)
        val carbs = round5((calories - protein * 4 - fat * 9) / 4).coerceAtLeast(50.0)

        val note = guardNote ?: when {
            prev == null && observed -> "Первый план по твоим данным: расход ≈ ${tdee.roundToInt()} ккал"
            prev == null -> "Первый план по формуле — уточню, когда накопятся записи"
            abs(calories - prev) < 10 -> "Темп в норме — план без изменений"
            calories > prev -> "Добавил ${(calories - prev).roundToInt()} ккал по твоим данным" +
                if (limited) " (не больше $MAX_STEP_INT за раз)" else ""
            else -> "Снизил на ${(prev - calories).roundToInt()} ккал по твоим данным" +
                if (limited) " (не больше $MAX_STEP_INT за раз)" else ""
        }

        return Plan(
            calories = calories,
            protein = protein,
            fat = fat,
            carbs = carbs,
            tdee = round10(tdee),
            bmr = bmr,
            baseTdee = baseTdee,
            observed = observed,
            targetWeeklyPercent = rate * 100,
            actualWeeklyPercent = actualWeekly,
            note = note,
            date = input.today,
            nextDate = input.today.plusDays(RECALC_DAYS),
            data = win.status,
            assumedFat = assumedFat,
            floor = floor
        )
    }

    // ---------- Недельный чек-ин: единственное место, где план меняется ----------

    /** Шаг правки калорий на чек-ине: маленький, чтобы не качать вес водой. */
    const val CHECKIN_STEP_KCAL = 100.0

    /** Сигнал «мышцы под защитой»: силовые, белок и спокойный темп. */
    enum class MuscleSignal { GREEN, YELLOW, RED }

    data class CheckInContext(
        val previousProtein: Double?,
        val previousFat: Double?,
        val previousCarbs: Double?,
        /** Темп по сглаженному тренду, % веса в неделю (плюс — худеешь); запасной, если МНК не готов. */
        val trendWeeklyPercent: Double?,
        val diaryDays: Int,
        val diaryNeeded: Int,
        val periodDays: Int,
        val signal: MuscleSignal,
        /** Голод на 5 из 5 два чек-ина подряд. */
        val hungerHighTwice: Boolean,
        /** Сколько прошлых чек-инов подряд вес стоял при полном дневнике. */
        val previousFlatWeeks: Int
    )

    data class CheckInPlan(
        val plan: Plan,
        val changed: Boolean,
        /** Темп, по которому принималось решение (МНК, иначе тренд); null — данных мало. */
        val ratePercent: Double?,
        /** Недель подряд «вес стоит» с учётом этой. */
        val flatWeeks: Int
    )

    /**
     * Решение чек-ина. Темп считается тем же МНК, что и раньше (математику не трогаем),
     * а правила — по таблице: в норме — без изменений, быстро — +100, стоит две недели
     * при полном дневнике — −50…100, три недели — не режем дальше и советуем поддержку,
     * красный сигнал мышц или голод 5 дважды — +100, неполный дневник — без изменений.
     * Шаг ±150 и безопасный минимум сохраняются.
     */
    fun checkIn(input: Input, ctx: CheckInContext): CheckInPlan {
        val base = compute(input)
        val rate = base.actualWeeklyPercent ?: ctx.trendWeeklyPercent
        val prev = input.previousCalories?.takeIf { it > 0 }
            ?: return CheckInPlan(base, true, rate, 0) // плана ещё не было — первый план как обычно

        val diaryOk = ctx.diaryDays >= ctx.diaryNeeded
        val flatNow = rate != null && rate < 0.2
        val flatWeeks = if (diaryOk && flatNow) ctx.previousFlatWeeks + 1 else 0

        fun keep(note: String): CheckInPlan = CheckInPlan(
            base.copy(
                calories = prev,
                protein = ctx.previousProtein ?: base.protein,
                fat = ctx.previousFat ?: base.fat,
                carbs = ctx.previousCarbs ?: base.carbs,
                note = note
            ),
            changed = false,
            ratePercent = rate,
            flatWeeks = flatWeeks
        )

        fun shift(delta: Double, note: String): CheckInPlan {
            val bounded = (prev + delta).coerceIn(prev - MAX_STEP_KCAL, prev + MAX_STEP_KCAL)
            val calories = round10(bounded.coerceAtLeast(base.floor))
            val carbs = round5((calories - base.protein * 4 - base.fat * 9) / 4).coerceAtLeast(50.0)
            // Минимум важнее правила: если упёрлись в него — честно говорим.
            val floorNote = if (delta < 0 && calories > bounded + 1) ". Ниже не опускаю — это безопасный минимум" else ""
            return CheckInPlan(
                base.copy(calories = calories, carbs = carbs, note = note + floorNote),
                changed = abs(calories - prev) >= 10,
                ratePercent = rate,
                flatWeeks = flatWeeks
            )
        }

        // Неполный дневник — данных о еде нет, любое решение было бы гаданием.
        if (!diaryOk) {
            return keep(
                "Еда записана ${ctx.diaryDays} из ${ctx.periodDays} дней — план оставил прежним: " +
                    "по неполному дневнику не видно, как он работает"
            )
        }
        if (ctx.signal == MuscleSignal.RED) {
            return shift(CHECKIN_STEP_KCAL, "Мышцам не хватает поддержки — добавил ${CHECKIN_STEP_INT} ккал")
        }
        if (ctx.hungerHighTwice) {
            return shift(CHECKIN_STEP_KCAL, "Голод на максимуме вторую неделю — добавил ${CHECKIN_STEP_INT} ккал, так легче держаться")
        }
        if (rate == null) return keep("Пока мало взвешиваний, чтобы увидеть темп, — план без изменений")
        if (rate > 1.0) {
            return shift(CHECKIN_STEP_KCAL, "Вес уходит быстрее 1% в неделю — добавил ${CHECKIN_STEP_INT} ккал, чтобы сберечь мышцы")
        }
        if (flatNow) {
            return when {
                flatWeeks >= 3 -> keep(
                    "Вес стоит $flatWeeks-ю неделю — дальше не урезаю. Попробуй 1–2 недели поесть " +
                        "на поддержке (≈ ${round10(base.tdee).roundToInt()} ккал), потом продолжим"
                )
                flatWeeks == 2 -> {
                    // Вес совсем не уходит или растёт — шаг побольше, едва уходит — поменьше.
                    val cut = if (rate <= 0.0) 100 else 50
                    shift(-cut.toDouble(), "Вес стоит вторую неделю при полном дневнике — снизил на $cut ккал")
                }
                else -> keep("Вес пока стоит — подождём неделю: вода может прятать результат")
            }
        }
        if (rate < 0.4) return keep("Вес уходит спокойно — план без изменений")
        return keep("Темп в норме — план без изменений")
    }

    private const val CHECKIN_STEP_INT = 100

    private const val MAX_STEP_INT = 150

    private class Window(val status: DataStatus, val slopeKgPerDay: Double?, val meanKcal: Double?)

    /**
     * Данные за последние 21 день. Калории — без сегодняшнего дня (он ещё не закончен),
     * взвешивания — включая сегодня.
     */
    private fun window(input: Input): Window {
        val start = input.today.minusDays(WINDOW_DAYS)
        val counted = input.dailyCalories
            .filter { it.date >= start && it.date < input.today && it.value >= MIN_DAY_KCAL }
        val weighIns = input.weights
            .filter { it.date >= start && it.date <= input.today }
            .sortedBy { it.date }
        val span = if (weighIns.size >= 2) {
            ChronoUnit.DAYS.between(weighIns.first().date, weighIns.last().date).toInt()
        } else 0
        val enough = counted.size >= MIN_LOGGED_DAYS && weighIns.size >= MIN_WEIGH_INS && span >= MIN_SPAN_DAYS
        val status = DataStatus(counted.size, weighIns.size, span, enough)
        val slope = if (weighIns.size >= 2 && span > 0) slope(weighIns) else null
        val mean = if (counted.isNotEmpty()) counted.sumOf { it.value } / counted.size else null
        return Window(status, slope, mean)
    }

    /** Наклон МНК: кг в день по взвешиваниям. */
    private fun slope(points: List<DayValue>): Double? {
        val origin = points.first().date
        val xs = points.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
        val ys = points.map { it.value }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den > 0) num / den else null
    }

    private fun round10(v: Double): Double = ((v / 10).roundToInt() * 10).toDouble()

    private fun round5(v: Double): Double = ((v / 5).roundToInt() * 5).toDouble()

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
