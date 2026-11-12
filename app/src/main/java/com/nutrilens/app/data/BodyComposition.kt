package com.nutrilens.app.data

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Состав тела: процент жира по обхватам (метод ВМС США), жировая и сухая масса,
 * вес при цели и срок. Чистый Kotlin без Android — всё считается локально.
 * Домашние методы дают ±3–4%, поэтому в UI важнее тренд, чем одно число.
 */
object BodyComposition {
    /** Диапазон цели в профиле: ниже 8% — уже не «рельеф», а риск для здоровья. */
    const val MIN_TARGET = 8
    const val MAX_TARGET = 35
    const val DEFAULT_TARGET = 15.0

    /** Безопасный темп сушки без потери мышц: ~0,6% веса тела в неделю. */
    private const val WEEKLY_LOSS_SHARE = 0.006

    /**
     * Процент жира по методу ВМС США (всё в см, log10). Невозможные обхваты
     * (талия не больше шеи и т.п.) → null, итог зажат в 3..60.
     */
    fun navyFatPercent(male: Boolean, heightCm: Double?, waistCm: Double?, neckCm: Double?, hipsCm: Double?): Double? {
        val h = heightCm?.takeIf { it in 100.0..250.0 } ?: return null
        val waist = waistCm?.takeIf { it in 40.0..250.0 } ?: return null
        val neck = neckCm?.takeIf { it in 20.0..80.0 } ?: return null
        val raw = if (male) {
            if (waist <= neck) return null
            495 / (1.0324 - 0.19077 * log10(waist - neck) + 0.15456 * log10(h)) - 450
        } else {
            val hips = hipsCm?.takeIf { it in 50.0..250.0 } ?: return null
            if (waist + hips <= neck) return null
            495 / (1.29579 - 0.35004 * log10(waist + hips - neck) + 0.22100 * log10(h)) - 450
        }
        if (raw.isNaN() || raw.isInfinite()) return null
        return raw.coerceIn(3.0, 60.0)
    }

    /** Итоговый процент замера: свой (весы, калипер, DEXA) важнее формулы. */
    fun resolveFatPercent(
        male: Boolean,
        heightCm: Double?,
        waistCm: Double?,
        neckCm: Double?,
        hipsCm: Double?,
        manualFatPercent: Double?
    ): Double? = manualFatPercent?.takeIf { it in 3.0..60.0 }
        ?: navyFatPercent(male, heightCm, waistCm, neckCm, hipsCm)

    fun leanMass(weightKg: Double, fatPercent: Double): Double = weightKg * (1 - fatPercent / 100)

    fun fatMass(weightKg: Double, fatPercent: Double): Double = weightKg - leanMass(weightKg, fatPercent)

    /** Вес при цели, если мышцы сохранены: сухая масса та же, жир — целевой процент. */
    fun targetWeight(weightKg: Double, fatPercent: Double, targetPercent: Double): Double =
        leanMass(weightKg, fatPercent) / (1 - targetPercent / 100)

    /** Сколько жира сбросить до цели, кг (0 — цель уже достигнута). */
    fun fatToLose(weightKg: Double, fatPercent: Double, targetPercent: Double): Double =
        (weightKg - targetWeight(weightKg, fatPercent, targetPercent)).coerceAtLeast(0.0)

    /** Недель до цели при темпе 0,6% веса в неделю; null — цель достигнута. */
    fun etaWeeks(weightKg: Double, fatToLoseKg: Double): Int? {
        if (fatToLoseKg <= 0.05 || weightKg <= 0) return null
        return ceil(fatToLoseKg / (weightKg * WEEKLY_LOSS_SHARE)).toInt().coerceAtLeast(1)
    }

    /** «≈ 9 недель» до трёх месяцев, дальше — «≈ 5 месяцев». */
    fun etaText(weeks: Int): String =
        if (weeks <= 12) "≈ $weeks ${plural(weeks, "неделя", "недели", "недель")}"
        else {
            val months = (weeks / 4.345).roundToInt().coerceAtLeast(1)
            "≈ $months ${plural(months, "месяц", "месяца", "месяцев")}"
        }

    /**
     * Белок при цели по жиру: 2,6 г на кг сухой массы (шаг 5 г) — сохраняет мышцы
     * в дефиците. Замера ещё нет — 2,0 г на кг веса. Нет и веса — null.
     */
    fun proteinTarget(weightKg: Double?, fatPercent: Double?): Double? {
        val w = weightKg?.takeIf { it > 0 } ?: return null
        val grams = if (fatPercent != null && fatPercent > 0) leanMass(w, fatPercent) * 2.6 else w * 2.0
        return ((grams / 5).roundToInt() * 5).toDouble()
    }

    /** Доля пути от стартового процента к целевому (0..1). */
    fun goalProgress(startPercent: Double, currentPercent: Double, targetPercent: Double): Float {
        val total = startPercent - targetPercent
        if (total <= 0) return if (currentPercent <= targetPercent) 1f else 0f
        return ((startPercent - currentPercent) / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Пол из профиля: явное поле, а для старых профилей — из текста «О себе»
     * (онбординг пишет «Я мужчина»/«Я женщина»). Не ясно — считаем мужчиной.
     */
    fun isMale(settings: SettingsEntity): Boolean = when (settings.sex) {
        "female" -> false
        "male" -> true
        else -> !settings.userContext.contains("женщин", ignoreCase = true)
    }

    /** Рост: поле профиля или «рост 180 см» из текста «О себе». */
    fun heightCm(settings: SettingsEntity): Double? =
        settings.heightCm?.takeIf { it in 100.0..250.0 }
            ?: Regex("рост\\D{0,3}(\\d{3})", RegexOption.IGNORE_CASE).find(settings.userContext)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 100.0..250.0 }

    /** Строка для контекста ИИ-коуча; пусто, если цель по жиру выключена. */
    fun coachContext(settings: SettingsEntity, weightKg: Double?, fatPercent: Double?): String {
        val target = settings.targetFatPercent ?: return ""
        return buildString {
            append(" Цель — процент жира ${fmt1(target)}% с сохранением мышц.")
            if (fatPercent != null) {
                append(" Сейчас жира ${fmt1(fatPercent)}%")
                if (weightKg != null) append(", сухая масса ${fmt1(leanMass(weightKg, fatPercent))} кг")
                append(".")
            }
            val planKcal = settings.planCalories
            val planProtein = settings.planProtein
            if (planKcal != null && planKcal > 0 && planProtein != null) {
                // Режим «Сжигание жира»: план пересчитывается сам — коуч не должен советовать другую норму.
                append(" Режим сжигания жира: план ${planKcal.roundToInt()} ккал, белок ${planProtein.roundToInt()} г")
                settings.planFat?.let { append(", жиры ${it.roundToInt()} г") }
                settings.planCarbs?.let { append(", углеводы ${it.roundToInt()} г") }
                append(" — считается автоматически по весу и еде, раз в неделю.")
            } else {
                proteinTarget(weightKg, fatPercent)?.let { append(" Цель по белку ${it.roundToInt()} г в день.") }
            }
        }
    }

    fun fmt1(v: Double): String {
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
