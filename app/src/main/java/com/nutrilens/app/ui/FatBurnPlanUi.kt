package com.nutrilens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.BodyComposition
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.checkInsActive
import com.nutrilens.app.data.fatBurnProgramState
import com.nutrilens.app.data.nextCheckInDate
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.FatBurnProgram
import com.nutrilens.app.insights.Trend
import com.nutrilens.app.insights.hasFatBurnPlan
import com.nutrilens.app.ui.theme.macroPalette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Режим «Сжигание жира»: бейдж в шапке «Сегодня», компактная карточка плана на
 * «Сегодня» и подробная — в Профиле. Цифры плана — из settings (меняются только
 * на недельном чек-ине), темп — по сглаженному тренду веса, «чего не хватает» —
 * из живого превью.
 */

/**
 * Бейдж шапки: с 3.24 — этап программы («🔥 Блок 2 из 3 · неделя 5 · 21% → 17%»,
 * «Перерыв · ещё 9 дней», «Удержание · неделя 2», «Пауза»); программы ещё нет —
 * «🔥 Сжигание жира · 24% → 13%». null — режим выключен.
 */
fun fatBurnBadgeText(settings: SettingsEntity, fatPercent: Double?): String? {
    val target = settings.targetFatPercent ?: return null
    val current = fatPercent?.takeIf { it > 0 }
    val program = settings.fatBurnProgramState
    if (program != null) return FatBurnProgram.badgeText(program, LocalDate.now(), current)
    return if (current != null) "🔥 Сжигание жира · ${current.roundToInt()}% → ${target.roundToInt()}%"
    else "🔥 Сжигание жира · сделай первый замер"
}

/** «сегодня» / «в воскресенье» / «22 ноября»; null — режим выключен. */
fun nextCheckInText(settings: SettingsEntity): String? {
    val today = LocalDate.now()
    val date = settings.nextCheckInDate(today) ?: return null
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days <= 0 -> "сегодня"
        days == 1L -> "завтра"
        days < 7 -> weekdayAccusative(date.dayOfWeek)
        else -> date.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU")))
    }
}

/** «в понедельник», «во вторник», «в среду»… */
fun weekdayAccusative(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "в понедельник"
    DayOfWeek.TUESDAY -> "во вторник"
    DayOfWeek.WEDNESDAY -> "в среду"
    DayOfWeek.THURSDAY -> "в четверг"
    DayOfWeek.FRIDAY -> "в пятницу"
    DayOfWeek.SATURDAY -> "в субботу"
    DayOfWeek.SUNDAY -> "в воскресенье"
}

/** Заголовок плана по этапу: в перерыве и удержании это не «дефицит на неделю». */
private fun planTitle(settings: SettingsEntity): String {
    val program = settings.fatBurnProgramState ?: return "План на неделю"
    return when {
        program.paused -> "План на паузе"
        program.completed -> "План удержания"
        program.currentPhase?.type == FatBurnProgram.PhaseType.BREAK -> "План перерыва"
        program.currentPhase?.type == FatBurnProgram.PhaseType.MAINTENANCE -> "План удержания"
        else -> "План на неделю"
    }
}

/** «0,6» — доли процента с запятой. */
private fun pct(v: Double): String = BodyComposition.fmt1(v)

/** Компактная карточка на «Сегодня»: калории, БЖУ, заметка и срок пересчёта. Тап — подробности. */
@Composable
fun FatBurnPlanCompactCard(settings: SettingsEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (settings.targetFatPercent == null) return
    val palette = macroPalette()
    FreshCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeader(
                title = planTitle(settings),
                icon = Icons.Rounded.LocalFireDepartment,
                tint = MaterialTheme.colorScheme.primary,
                subtitle = if (settings.checkInsActive) {
                    nextCheckInText(settings)?.let { "Меняется на чек-ине · следующий $it" } ?: "Меняется на чек-ине"
                } else "Чек-ины сейчас не идут"
            )
            if (settings.hasFatBurnPlan) {
                Row(verticalAlignment = Alignment.Bottom) {
                    DisplayNumber("${(settings.planCalories ?: 0.0).roundToInt()}", size = 28, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ккал в день",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MacroInline("Б", settings.planProtein, palette.protein)
                    MacroInline("Ж", settings.planFat, palette.fat)
                    MacroInline("У", settings.planCarbs, palette.carbs)
                }
                if (settings.planNote.isNotBlank()) {
                    Text(
                        settings.planNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "Запиши вес — посчитаю калории и БЖУ под твою цель.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MacroInline(label: String, grams: Double?, color: androidx.compose.ui.graphics.Color) {
    Text(
        "$label ${(grams ?: 0.0).roundToInt()} г",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

/**
 * Подробная карточка плана для Профиля: калории и БЖУ, расход (по данным или по
 * формуле), целевой и фактический темп, заметка о последнем изменении, срок
 * пересчёта и чего не хватает для точности.
 */
@Composable
fun FatBurnPlanSection(modifier: Modifier = Modifier, viewModel: BodyFatViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val preview by viewModel.planPreview.collectAsStateWithLifecycle()
    val weightTrend by viewModel.weightTrend.collectAsStateWithLifecycle()
    if (settings.targetFatPercent == null) return
    val primary = MaterialTheme.colorScheme.primary

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = planTitle(settings),
                icon = Icons.Rounded.LocalFireDepartment,
                tint = primary,
                subtitle = "Подстраивается под твой вес и еду"
            )
            if (!settings.hasFatBurnPlan) {
                Text(
                    "Запиши вес на «Сегодня» — и я посчитаю калории и БЖУ под твою цель.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FatBurnPlanDetails(settings, preview, Trend.weeklyLossPercent(weightTrend))
            }
        }
    }
}

@Composable
private fun FatBurnPlanDetails(settings: SettingsEntity, preview: FatBurnPlanner.Plan?, trendWeekly: Double?) {
    val palette = macroPalette()
    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayNumber("${(settings.planCalories ?: 0.0).roundToInt()}", size = 36, color = primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "ккал в день",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalTile("${(settings.planProtein ?: 0.0).roundToInt()} г", "белки", palette.protein, Modifier.weight(1f))
            TonalTile("${(settings.planFat ?: 0.0).roundToInt()} г", "жиры", palette.fat, Modifier.weight(1f))
            TonalTile("${(settings.planCarbs ?: 0.0).roundToInt()} г", "углеводы", palette.carbs, Modifier.weight(1f))
        }
        settings.planTdee?.let { tdee ->
            Text(
                "Твой расход ≈ ${tdee.roundToInt()} ккал (" +
                    (if (settings.planObserved) "по твоим данным" else "по формуле") + ")",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        preview?.let { p ->
            // Фактический темп — по сглаженному тренду, как везде в приложении.
            Text(
                "Темп: цель −${pct(p.targetWeeklyPercent)}% веса в неделю · " +
                    (trendWeekly?.let { a ->
                        if (a >= 0) "по тренду −${pct(a)}%" else "по тренду +${pct(-a)}%"
                    } ?: "факт появится, когда накопятся взвешивания"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (settings.planNote.isNotBlank()) {
            TonalSection(color = primary) {
                Text(
                    settings.planNote,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        nextCheckInText(settings)?.takeIf { settings.checkInsActive }?.let {
            Text(
                "Следующий чек-ин — $it. План меняется только там, чтобы ты видел почему",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        preview?.data?.missingText?.let { missing ->
            Text(
                "$missing — тогда расход будет считаться по твоим данным, а не по формуле.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (preview?.assumedFat == true) {
            Text(
                "Процент жира пока взят типичный — сделай замер, и белок посчитается точнее.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Раз в неделю на чек-ине план подстраивается по тренду веса и записям еды. Точнее всего — после ~2 недель записей и 4+ взвешиваний.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
