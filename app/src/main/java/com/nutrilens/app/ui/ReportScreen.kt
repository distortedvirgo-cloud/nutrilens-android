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
fun ReportScreen(viewModel: ReportViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiText by viewModel.aiText.collectAsStateWithLifecycle()
    val aiError by viewModel.aiError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Отчёт",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Как прошёл ваш период",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        PeriodSwitcher(
            onPeriod = { viewModel.setPeriod(it) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        when {
            !state.loaded -> {
                Spacer(Modifier.height(80.dp))
                Text(
                    "Собираем данные…",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.days.all { it.kcal == 0.0 } -> EmptyReport()
            else -> {
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
                PillButton(
                    text = if (aiLoading) "ИИ изучает статистику…" else "✨ Оценка от ИИ",
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
                aiText?.let { text ->
                    Spacer(Modifier.height(12.dp))
                    FreshCard(Modifier.fillMaxWidth()) {
                        MarkdownText(text, Modifier.padding(16.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Детерминированный вывод по периоду (как нарратив в веб-версии). */
@Composable
private fun NarrativeCard(state: ReportState) {
    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Итоги периода",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Дней в цели: ${state.adherencePct}% (${state.days.count { it.kcal > 0 }} активных дней)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.overDays > 0) {
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
                        "Последние 3 дня: ${state.recentAvg.roundToInt()} ккал — держитесь цели 🎯"
                    delta > 0 ->
                        "Последние 3 дня: ${state.recentAvg.roundToInt()} ккал — на ${delta.roundToInt()} ккал выше цели"
                    else ->
                        "Последние 3 дня: ${state.recentAvg.roundToInt()} ккал — на ${-delta.roundToInt()} ккал ниже цели"
                }
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
private fun PeriodSwitcher(onPeriod: (Int) -> Unit, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(7) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            listOf(7 to "Неделя", 30 to "Месяц", 365 to "Год").forEach { (days, label) ->
                val active = selected == days
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            selected = days
                            onPeriod(days)
                        }
                        .background(
                            color = if (active) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryGrid(state: ReportState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard(
            value = "${state.avgKcal.roundToInt()}",
            label = "ккал в среднем",
            accent = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            value = "${state.avgProtein.roundToInt()} г",
            label = "белка в среднем",
            accent = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard(
            value = "${state.streak}",
            label = "дней подряд 🔥",
            accent = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            value = "${state.overDays}",
            label = "дней выше цели",
            accent = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    value: String,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
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
 * Статусы баров как в веб-версии: в цели — зелёный, до +200 ккал — янтарный, выше — терракотовый.
 */
@Composable
private fun CalorieCard(
    state: ReportState,
    days: List<DayStat>,
    title: String = "Калории по дням",
    labelMode: Int = 0
) {
    val warningColor = Color(0xFFD08700)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Пунктир — цель ${state.goal.roundToInt()} ккал",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            val primary = MaterialTheme.colorScheme.primary
            val over = MaterialTheme.colorScheme.tertiary
            val goalColor = MaterialTheme.colorScheme.outline
            val track = MaterialTheme.colorScheme.surfaceVariant

            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val n = days.size
                if (n == 0) return@Canvas
                val slot = size.width / n
                val maxVal = (maxOf(state.goal, days.maxOf { it.kcal }) * 1.15).coerceAtLeast(1.0)
                val hFor = { v: Double -> (v / maxVal * size.height).toFloat() }

                // Линия цели.
                val goalY = size.height - hFor(state.goal)
                drawLine(
                    color = goalColor,
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )

                days.forEachIndexed { i, day ->
                    val barW = slot * 0.56f
                    val x = i * slot + (slot - barW) / 2
                    val h = hFor(day.kcal).coerceAtLeast(if (day.kcal > 0) 6f else 0f)
                    // Подложка.
                    drawRoundRect(
                        color = track,
                        topLeft = Offset(x, 0f),
                        size = Size(barW, size.height),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    if (h > 0f) {
                        val barColor = when {
                            day.kcal > state.goal + 200 -> over
                            day.kcal > state.goal -> warningColor
                            else -> primary
                        }
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, size.height - h),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun WeightCard(weights: List<WeightEntity>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                        shape = RoundedCornerShape(10.dp),
                        color = deltaColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "${sign}${"%.1f".format(delta)} кг",
                            style = MaterialTheme.typography.labelLarge,
                            color = deltaColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            if (weights.size < 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Запишите вес хотя бы дважды, чтобы увидеть динамику ⚖️",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            } else {
                Spacer(Modifier.height(12.dp))
                WeightChart(weights)
            }
        }
    }
}

@Composable
private fun WeightChart(weights: List<WeightEntity>) {
    val line = MaterialTheme.colorScheme.secondary
    val dot = MaterialTheme.colorScheme.primary
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                val minW = weights.minOf { it.weight }
                val maxW = weights.maxOf { it.weight }
                val span = (maxW - minW).coerceAtLeast(1.0)
                val pad = 12f
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
                drawPath(path, line, style = Stroke(width = 5f, cap = StrokeCap.Round))
                weights.forEachIndexed { i, entry ->
                    drawCircle(dot, radius = 7f, center = Offset(xFor(i), yFor(entry.weight)))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "min %.1f".format(weights.minOf { it.weight }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "max %.1f".format(weights.maxOf { it.weight }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
}

@Composable
private fun WaterCard(avgMl: Int, normMl: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Вода",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            val fraction = if (normMl > 0) (avgMl.toFloat() / normMl).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(12.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(0xFF4FA3D8),
                            RoundedCornerShape(6.dp)
                        )
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

@Composable
private fun EmptyReport() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📊", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Пока нет данных за период",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Добавьте пару приёмов пищи — и здесь появится статистика",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
