package com.nutrilens.app.data

import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.FatBurnProgram
import com.nutrilens.app.insights.Trend
import java.time.LocalDate

/*
 * Программа с этапами (3.24) в settings: чтение/запись JSON-строки и признаки,
 * которые нужны теме, чек-ину и экранам. Логика этапов — insights/FatBurnProgram.
 */

/** Программа режима; null — режим выключен или программы ещё нет (её соберёт FatBurnPlanSync). */
val SettingsEntity.fatBurnProgramState: FatBurnProgram.Program?
    get() = if (targetFatPercent == null) null else FatBurnProgramCodec.decode(fatBurnProgram)

/** Записать программу (null — стереть). */
fun SettingsEntity.withProgram(program: FatBurnProgram.Program?): SettingsEntity =
    copy(fatBurnProgram = program?.let { FatBurnProgramCodec.encode(it) })

/**
 * Оранжевая тема — только в активном блоке дефицита. Перерыв, удержание, пауза и
 * завершённая программа — спокойный зелёный: это не «режим жёсткой сушки».
 * Программы ещё нет (режим из старой версии до первого пересчёта) — как раньше, оранжевый.
 */
val SettingsEntity.fatBurnOrange: Boolean
    get() = targetFatPercent != null && (fatBurnProgramState?.inActiveBlock ?: true)

/** Нижняя граница цели: 10% мужчинам, 18% женщинам. */
fun programMinTarget(male: Boolean): Int = if (male) FatBurnProgram.MIN_TARGET_MALE else FatBurnProgram.MIN_TARGET_FEMALE

/** С какого значения показываем предупреждение «очень низкая цель»: ниже 12% / 20%. */
fun programWarnTarget(male: Boolean): Int = if (male) FatBurnProgram.WARN_TARGET_MALE else FatBurnProgram.WARN_TARGET_FEMALE

/** Сглаженный тренд процента жира по всем замерам; null — замеров нет. */
fun fatTrendNow(measurements: List<BodyMeasurementEntity>): Double? =
    fatTrendPoints(measurements).lastOrNull()?.trend

fun fatTrendPoints(measurements: List<BodyMeasurementEntity>): List<Trend.Point> = Trend.smooth(
    measurements.mapNotNull { m ->
        runCatching { FatBurnPlanner.DayValue(LocalDate.parse(m.date), m.fatPercent) }.getOrNull()
    }
)
