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
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.hasFatBurnPlan
import com.nutrilens.app.ui.theme.macroPalette
import java.time.LocalDate
import kotlin.math.roundToInt

/*
 * Режим «Сжигание жира»: бейдж в шапке «Сегодня», компактная карточка плана на
 * «Сегодня» и подробная — в Профиле. Цифры плана — из settings (меняются только
 * при пересчёте раз в неделю), темп и «чего не хватает» — из живого превью.
 */

/** Бейдж шапки: «🔥 Сжигание жира · 24% → 13%»; null — режим выключен. */
fun fatBurnBadgeText(settings: SettingsEntity, fatPercent: Double?): String? {
    val target = settings.targetFatPercent ?: return null
    val current = fatPercent?.takeIf { it > 0 }
    return if (current != null) "🔥 Сжигание жира · ${current.roundToInt()}% → ${target.roundToInt()}%"
    else "🔥 Сжигание жира · сделай первый замер"
}

/** «через 3 дн.» / «сегодня»; null — плана ещё нет. */
private fun nextRecalcText(settings: SettingsEntity): String? {
    val date = settings.planDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val days = FatBurnPlanner.daysUntilNext(date, LocalDate.now())
    return if (days <= 0) "сегодня" else "через $days ${plural(days, "день", "дня", "дней")}"
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
                title = "План на неделю",
                icon = Icons.Rounded.LocalFireDepartment,
                tint = MaterialTheme.colorScheme.primary,
                subtitle = nextRecalcText(settings)?.let { "Считается сам · пересчёт $it" } ?: "Считается сам"
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
    if (settings.targetFatPercent == null) return
    val primary = MaterialTheme.colorScheme.primary

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = "План на неделю",
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
                FatBurnPlanDetails(settings, preview)
            }
        }
    }
}

@Composable
private fun FatBurnPlanDetails(settings: SettingsEntity, preview: FatBurnPlanner.Plan?) {
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
            Text(
                "Темп: цель −${pct(p.targetWeeklyPercent)}% веса в неделю · " +
                    (p.actualWeeklyPercent?.let { a ->
                        if (a >= 0) "сейчас −${pct(a)}%" else "сейчас +${pct(-a)}%"
                    } ?: "факт появится, когда накопятся данные"),
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
        nextRecalcText(settings)?.let {
            Text(
                "Следующий пересчёт — $it",
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
            "План сам подстраивается по весу и записям еды. Точнее всего — после ~2 недель записей и 4+ взвешиваний.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
