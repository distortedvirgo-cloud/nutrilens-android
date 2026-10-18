package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.WeightEntity
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EggAlt
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalDining
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ---------- Модель данных отчёта ----------

data class DayStat(
    val date: LocalDate,
    val kcal: Double,
    val protein: Double,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
    val names: String = ""
)

data class ReportState(
    val period: Int = 7,
    val days: List<DayStat> = emptyList(),
    val monthly: List<DayStat> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
    val waterAvgMl: Int = 0,
    val waterNormMl: Int = 0,
    val goal: Double = 2000.0,
    val streak: Int = 0,
    val overDays: Int = 0,
    val avgKcal: Double = 0.0,
    val avgProtein: Double = 0.0,
    val adherencePct: Int = 0,
    val recentAvg: Double = 0.0,
    val recentData: String = "",
    val loaded: Boolean = false
)

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)
    private val settingsRepo = SettingsRepository(db.settingsDao())
    private val periodDays = MutableStateFlow(7)
    val period: StateFlow<Int> = periodDays.asStateFlow()

    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    private val _aiText = MutableStateFlow<String?>(null)
    val aiText: StateFlow<String?> = _aiText.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    init {
        viewModelScope.launch {
            combine(periodDays, settingsRepo.observe()) { p, s -> p to s }
                .mapLatest { (p, s) -> load(p, s.dailyGoal, s.proteinGoal) }
                .collect { _state.value = it }
        }
    }

    fun setPeriod(days: Int) {
        periodDays.value = days
    }

    /** «Оценка от ИИ»: statsInsight по последним активным дням. */
    fun loadAiAssessment() {
        if (_aiLoading.value) return
        viewModelScope.launch {
            val settings = settingsRepo.get()
            if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
                _aiError.value = "Сначала добавьте ключ Gemini в настройках"
                return@launch
            }
            val current = _state.value
            if (current.recentData.isBlank()) {
                _aiError.value = "Пока недостаточно данных для оценки"
                return@launch
            }
            _aiLoading.value = true
            _aiError.value = null
            try {
                _aiText.value = GeminiTools.statsInsight(settings,
                    settings.dailyGoal,
                    current.recentData
                )
            } catch (e: Exception) {
                _aiError.value = e.message ?: "Не удалось получить оценку"
            }
            _aiLoading.value = false
        }
    }

    private suspend fun load(period: Int, goal: Double, proteinGoal: Double?): ReportState {
        val today = LocalDate.now()
        val start = today.minusDays((period - 1).toLong())
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE

        val meals = db.mealDao().mealsBetween(start.format(fmt), today.format(fmt))
        val byDate = meals.groupBy { it.date }

        val days = (0 until period).map { offset ->
            val date = start.plusDays(offset.toLong())
            val list = byDate[date.format(fmt)].orEmpty()
            DayStat(
                date = date,
                kcal = list.sumOf { it.calories },
                protein = list.sumOf { it.protein },
                fat = list.sumOf { it.fat },
                carbs = list.sumOf { it.carbs },
                names = list.joinToString(", ") { it.name }
            )
        }

        val withMeals = days.filter { it.kcal > 0 }
        val overDays = withMeals.count { it.kcal > goal }

        // Год: 12 месячных корзин — средняя калорийность активного дня в месяце.
        val monthly = if (period >= 365) {
            val byMonth = withMeals.groupBy { YearMonth.from(it.date) }
            (11 downTo 0).map { back ->
                val ym = YearMonth.from(today).minusMonths(back.toLong())
                val list = byMonth[ym].orEmpty()
                DayStat(
                    date = ym.atDay(1),
                    kcal = if (list.isEmpty()) 0.0 else list.sumOf { it.kcal } / list.size,
                    protein = if (list.isEmpty()) 0.0 else list.sumOf { it.protein } / list.size,
                    fat = if (list.isEmpty()) 0.0 else list.sumOf { it.fat } / list.size,
                    carbs = if (list.isEmpty()) 0.0 else list.sumOf { it.carbs } / list.size
                )
            }
        } else {
            emptyList()
        }

        val adherence = if (withMeals.isEmpty()) 0
        else ((withMeals.size - overDays) * 100) / withMeals.size
        val recentThree = withMeals.takeLast(3)
        val recentAvg = if (recentThree.isEmpty()) 0.0
        else recentThree.sumOf { it.kcal } / recentThree.size
        val recentData = withMeals.takeLast(14).joinToString("\n") { d ->
            "${d.date}: ${d.kcal.roundToInt()} ккал " +
                "(Б:${d.protein.roundToInt()} Ж:${d.fat.roundToInt()} У:${d.carbs.roundToInt()}). " +
                "Ел: ${d.names.ifBlank { "—" }}"
        }

        // Стрик: подряд идущие дни с записями, от сегодня (или вчера, если сегодня ещё нет).
        val allDates = db.mealDao()
            .mealsBetween(today.minusDays(180).format(fmt), today.format(fmt))
            .map { it.date }
            .toSet()
        var cursor = if (allDates.contains(today.format(fmt))) today else today.minusDays(1)
        var streak = 0
        while (allDates.contains(cursor.format(fmt))) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val weights = db.weightDao().since(start.format(fmt))
        val water = db.waterDao().between(start.format(fmt), today.format(fmt))
        val waterAvg = if (water.isEmpty()) 0 else water.sumOf { it.ml } / water.size
        val lastWeight = db.weightDao().latest()?.weight
        val waterNorm = ((lastWeight ?: 75.0) * 35).roundToInt()

        return ReportState(
            period = period,
            days = days,
            monthly = monthly,
            weights = weights,
            waterAvgMl = waterAvg,
            waterNormMl = waterNorm,
            goal = goal,
            streak = streak,
            overDays = overDays,
            avgKcal = if (withMeals.isEmpty()) 0.0 else withMeals.sumOf { it.kcal } / withMeals.size,
            avgProtein = if (withMeals.isEmpty()) 0.0
            else withMeals.sumOf { it.protein } / withMeals.size,
            adherencePct = adherence,
            recentAvg = recentAvg,
            recentData = recentData,
            loaded = true
        )
    }
}

// ---------- Экран ----------

@Composable
fun ReportScreen(
    onOpenLeaderboard: () -> Unit = {},
    viewModel: ReportViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiText by viewModel.aiText.collectAsStateWithLifecycle()
    val aiError by viewModel.aiError.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 120.dp)
    ) {
        ScreenHeader(title = "Прогресс", subtitle = "Как проходит твой период")

        // Выбранный период живёт во ViewModel — переключатель не сбрасывается
        // при возврате на вкладку.
        SegmentedSwitcher(
            options = listOf(7 to "Неделя", 30 to "Месяц", 365 to "Год"),
            selected = period,
            onSelect = { viewModel.setPeriod(it) },
            modifier = Modifier
                .fillMaxWidth()
                .staggeredIn(1)
        )
        Spacer(Modifier.height(18.dp))

        AnimatedContent(
            targetState = when {
                !state.loaded -> 0
                state.days.all { it.kcal == 0.0 } -> 1
                else -> 2
            },
            transitionSpec = {
                fadeIn(tween(Motion.Medium, delayMillis = 60)) togetherWith fadeOut(tween(Motion.Short))
            },
            label = "reportState"
        ) { mode ->
            when (mode) {
                0 -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                }
                1 -> EmptyReport()
                else -> Column {
                    SummaryGrid(state)
                    Spacer(Modifier.height(16.dp))
                    if (state.period >= 365) {
                        CalorieCard(
                            state,
                            days = state.monthly,
                            title = "Средние калории по месяцам",
                            labelMode = 2
                        )
                    } else {
                        CalorieCard(state, days = state.days, labelMode = if (state.days.size <= 7) 0 else 1)
                    }
                    Spacer(Modifier.height(16.dp))
                    NarrativeCard(state)
                    Spacer(Modifier.height(16.dp))
                    WeightCard(state.weights)
                    Spacer(Modifier.height(16.dp))
                    WaterCard(state.waterAvgMl, state.waterNormMl)
                    Spacer(Modifier.height(16.dp))
                    LeaderboardEntryCard(onClick = onOpenLeaderboard)
                    Spacer(Modifier.height(20.dp))
                    GlowButton(
                        text = if (aiLoading) "ИИ изучает статистику…" else "Оценка от ИИ",
                        icon = Icons.Rounded.AutoAwesome,
                        onClick = viewModel::loadAiAssessment,
                        enabled = !aiLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    aiError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (aiLoading) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    AnimatedVisibility(
                        visible = aiText != null,
                        enter = fadeIn(tween(Motion.Long)) + expandVertically(tween(Motion.Long, easing = Motion.Emphasized))
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            FreshCard(Modifier.fillMaxWidth()) {
                                MarkdownText(aiText.orEmpty(), Modifier.padding(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Детерминированный вывод по периоду (как нарратив в веб-версии). */
@Composable
private fun NarrativeCard(state: ReportState) {
    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Итоги периода",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            // Доля дней в цели — горизонтальная шкала с анимированным заполнением.
            val adherence by animateFloatAsState(
                (state.adherencePct / 100f).coerceIn(0f, 1f),
                tween(Motion.ExtraLong, easing = Motion.Emphasized),
                label = "adherence"
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${state.adherencePct}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "дней в цели · ${state.days.count { it.kcal > 0 }} активных",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(adherence)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NutriTheme.colors.brandGradient)
                )
            }
            if (state.overDays > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Дней выше цели: ${state.overDays}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.recentAvg > 0) {
                val delta = state.recentAvg - state.goal
                val line = when {
                    kotlin.math.abs(delta) <= 60 ->
                        "Последние 3 дня: ${state.recentAvg.roundToInt()} ккал — держитесь цели"
                    delta > 0 ->
                        "Последние 3 дня: ${state.recentAvg.roundToInt()} ккал — на ${delta.roundToInt()} ккал выше цели"
                    else ->
                        "Последние 3 дня: ${state.recentAvg.roundToInt()} ккал — на ${-delta.roundToInt()} ккал ниже цели"
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryGrid(state: ReportState) {
    val c = NutriTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard(
            icon = Icons.Rounded.LocalDining,
            value = "${state.avgKcal.roundToInt()}",
            label = "ккал в среднем",
            accent = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).staggeredIn(2)
        )
        SummaryCard(
            icon = Icons.Rounded.EggAlt,
            value = "${state.avgProtein.roundToInt()} г",
            label = "белка в среднем",
            accent = macroPalette().protein,
            modifier = Modifier.weight(1f).staggeredIn(3)
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard(
            icon = Icons.Rounded.LocalFireDepartment,
            value = "${state.streak}",
            label = "дней подряд",
            accent = c.warnTo,
            modifier = Modifier.weight(1f).staggeredIn(4)
        )
        SummaryCard(
            icon = Icons.AutoMirrored.Rounded.TrendingUp,
            value = "${state.overDays}",
            label = "дней выше цели",
            accent = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f).staggeredIn(5)
        )
    }
}

@Composable
private fun SummaryCard(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    FreshCard(modifier, radius = 24) {
        Column(modifier = Modifier.padding(16.dp)) {
            IconBadge(icon = icon, tint = accent, size = 36)
            Spacer(Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * labelMode: 0 — дни недели, 1 — даты, 2 — месяцы.
 * Статусы баров как в веб-версии: в цели — изумруд, до +200 ккал — янтарный,
 * выше — терракотовый (раньше оба «перебора» рисовались одним цветом).
 * Бары «вырастают» снизу при каждой смене данных.
 */
@Composable
private fun CalorieCard(
    state: ReportState,
    days: List<DayStat>,
    title: String = "Калории по дням",
    labelMode: Int = 0
) {
    val c = NutriTheme.colors
    val grow = remember { Animatable(0f) }
    LaunchedEffect(days) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(Motion.ExtraLong, easing = Motion.Emphasized))
    }
    FreshCard(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(c.brandFrom, "в цели")
                Spacer(Modifier.width(12.dp))
                LegendDot(c.warnFrom, "до +200")
                Spacer(Modifier.width(12.dp))
                LegendDot(MaterialTheme.colorScheme.tertiary, "выше")
            }
            Spacer(Modifier.height(14.dp))

            val okFrom = c.brandFrom
            val okTo = c.brandTo
            val warn = c.warnFrom
            val over = MaterialTheme.colorScheme.tertiary
            val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
            val track = MaterialTheme.colorScheme.surfaceContainer

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .semantics {
                        contentDescription = "График калорий, цель ${state.goal.roundToInt()} ккал"
                    }
            ) {
                val n = days.size
                if (n == 0) return@Canvas
                val slot = size.width / n
                val maxVal = (maxOf(state.goal, days.maxOf { it.kcal }) * 1.15).coerceAtLeast(1.0)
                val hFor = { v: Double -> (v / maxVal * size.height).toFloat() }
                val radius = CornerRadius(8.dp.toPx(), 8.dp.toPx())

                days.forEachIndexed { i, day ->
                    val barW = (slot * 0.58f).coerceAtMost(28.dp.toPx())
                    val x = i * slot + (slot - barW) / 2
                    // Лёгкая «волна»: бары справа догоняют левые.
                    val local = ((grow.value * 1.3f) - i.toFloat() / n * 0.3f).coerceIn(0f, 1f)
                    val h = hFor(day.kcal).coerceAtLeast(if (day.kcal > 0) 6f else 0f) * local
                    drawRoundRect(
                        color = track,
                        topLeft = Offset(x, 0f),
                        size = Size(barW, size.height),
                        cornerRadius = radius
                    )
                    if (h > 0f) {
                        val top = size.height - h
                        val brush = when {
                            day.kcal > state.goal + 200 -> SolidColor(over)
                            day.kcal > state.goal -> SolidColor(warn)
                            else -> Brush.verticalGradient(listOf(okFrom, okTo), startY = top, endY = size.height)
                        }
                        drawRoundRect(
                            brush = brush,
                            topLeft = Offset(x, top),
                            size = Size(barW, h),
                            cornerRadius = radius
                        )
                    }
                }

                // Линия цели — поверх баров.
                val goalY = size.height - hFor(state.goal)
                drawLine(
                    color = goalColor.copy(alpha = 0.7f),
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                days.forEachIndexed { i, day ->
                    val label = when (labelMode) {
                        0 -> day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru"))
                        2 -> day.date.month.getDisplayName(TextStyle.SHORT, Locale("ru"))
                        else -> if (i % 5 == 0 || i == days.size - 1) {
                            "${day.date.dayOfMonth}.${day.date.monthValue}"
                        } else ""
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Пунктир — цель ${state.goal.roundToInt()} ккал",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Легенда графика: цвет + подпись (цвет — не единственный носитель смысла). */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeightCard(weights: List<WeightEntity>) {
    FreshCard(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Вес",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (weights.size >= 2) {
                    val delta = weights.last().weight - weights.first().weight
                    val sign = if (delta > 0) "+" else ""
                    val deltaColor = if (delta > 0) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = deltaColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "${sign}${"%.1f".format(delta)} кг",
                            style = MaterialTheme.typography.labelLarge,
                            color = deltaColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            if (weights.size < 2) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.MonitorWeight, MaterialTheme.colorScheme.secondary, size = 36)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Запишите вес хотя бы дважды, чтобы увидеть динамику",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
                WeightChart(weights)
            }
        }
    }
}

/** Линия веса «прорисовывается» слева направо, под ней — мягкая градиентная заливка. */
@Composable
private fun WeightChart(weights: List<WeightEntity>) {
    val line = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val draw = remember { Animatable(0f) }
    LaunchedEffect(weights) {
        draw.snapTo(0f)
        draw.animateTo(1f, tween(Motion.ExtraLong + 300, easing = Motion.Emphasized))
    }
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
            val minW = weights.minOf { it.weight }
            val maxW = weights.maxOf { it.weight }
            val span = (maxW - minW).coerceAtLeast(1.0)
            val pad = 10.dp.toPx()
            val xFor = { i: Int ->
                if (weights.size == 1) size.width / 2
                else pad + i * (size.width - pad * 2) / (weights.size - 1)
            }
            val yFor = { w: Double ->
                size.height - pad - ((w - minW) / span * (size.height - pad * 2)).toFloat()
            }
            val path = Path().apply {
                weights.forEachIndexed { i, entry ->
                    val p = Offset(xFor(i), yFor(entry.weight))
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(xFor(weights.size - 1), size.height)
                lineTo(xFor(0), size.height)
                close()
            }
            clipRect(right = size.width * draw.value) {
                drawPath(
                    area,
                    Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0f)))
                )
                drawPath(
                    path, line,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                weights.forEachIndexed { i, entry ->
                    val center = Offset(xFor(i), yFor(entry.weight))
                    drawCircle(line, radius = 5.dp.toPx(), center = center)
                    drawCircle(surface, radius = 2.5.dp.toPx(), center = center)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "мин %.1f".format(weights.minOf { it.weight }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "макс %.1f".format(weights.maxOf { it.weight }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WaterCard(avgMl: Int, normMl: Int) {
    val c = NutriTheme.colors
    val fraction by animateFloatAsState(
        if (normMl > 0) (avgMl.toFloat() / normMl).coerceIn(0f, 1f) else 0f,
        tween(Motion.ExtraLong, easing = Motion.Emphasized),
        label = "reportWater"
    )
    FreshCard(Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.WaterDrop, c.water, size = 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Вода",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(c.water.copy(alpha = if (c.isDark) 0.2f else 0.14f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(c.waterGradient)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "В среднем $avgMl мл из ~$normMl мл в день",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyReport() {
    FreshCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconBadge(Icons.Rounded.Insights, MaterialTheme.colorScheme.primary, size = 64)
            Spacer(Modifier.height(16.dp))
            Text(
                "Пока нет данных за период",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Добавьте пару приёмов пищи — и здесь появится статистика",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Вход в лидерборд из «Прогресса»: соревнование — часть прогресса. */
@Composable
private fun LeaderboardEntryCard(onClick: () -> Unit) {
    val c = NutriTheme.colors
    FreshCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            IconBadge(Icons.Rounded.EmojiEvents, c.warnTo, size = 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Лидерборд", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Кто точнее вписывается в план — день, неделя, месяц",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
