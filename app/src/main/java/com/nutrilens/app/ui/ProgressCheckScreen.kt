package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.BodyComposition
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.fatBurnProgramState
import com.nutrilens.app.data.fatTrendNow
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.ProgressVerdict
import com.nutrilens.app.insights.Trend
import com.nutrilens.app.ui.theme.NutriTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/*
 * «Как идёт прогресс» (3.30): отдельный экран — один вердикт («всё хорошо / есть что
 * подтянуть / что-то не так»), главное действие и шесть строк. Логика — ProgressVerdict.
 */

class ProgressCheckViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface State {
        data object Loading : State
        data object Off : State
        data class Ready(val result: ProgressVerdict.Result) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { compute() }
        }
    }

    private suspend fun compute(): State {
        val app = getApplication<Application>()
        val db = NutriLensDatabase.getInstance(app)
        val settings = SettingsRepository(db.settingsDao()).get()
        val target = settings.targetFatPercent ?: return State.Off
        val program = settings.fatBurnProgramState
        val today = LocalDate.now()
        val start = program?.startDate
        val male = BodyComposition.isMale(settings)
        val measurements = db.bodyMeasurementDao().all()
        val fat = fatTrendNow(measurements) ?: if (male) 25.0 else 32.0
        val targetRate = FatBurnPlanner.weeklyRate(fat, target, male, FatBurnPlanner.Pace.of(settings.fatBurnPace)) * 100
        val weeks = db.checkInDao().all()
            .filter { start == null || it.date >= start.toString() }
            .sortedByDescending { it.date }
            .mapNotNull { c ->
                val date = runCatching { LocalDate.parse(c.date) }.getOrNull() ?: return@mapNotNull null
                ProgressVerdict.Week(
                    date = date,
                    rate = c.decisionRate ?: c.weeklyRatePercent,
                    diaryDays = c.diaryDays,
                    proteinDays = c.proteinDays,
                    periodDays = c.periodDays,
                    workouts = c.workouts,
                    workoutGoal = c.workoutGoal,
                    strengthSignal = c.strengthSignal,
                    strengthIndex = c.strengthIndex
                )
            }
        val waist = measurements.mapNotNull { m ->
            val w = m.waistCm ?: return@mapNotNull null
            runCatching { LocalDate.parse(m.date) to w }.getOrNull()
        }
        val weightNow = Trend.smooth(
            db.weightDao().since(today.minusDays(90).toString()).mapNotNull { w ->
                runCatching { FatBurnPlanner.DayValue(LocalDate.parse(w.date), w.weight) }.getOrNull()
            }
        ).lastOrNull()?.trend
        val result = ProgressVerdict.evaluate(
            ProgressVerdict.Input(
                today = today,
                targetRate = targetRate,
                weeks = weeks,
                waist = waist,
                startDate = start,
                startWeight = program?.startWeight,
                weightNow = weightNow,
                atMaintenance = program?.atMaintenanceCalories == true
            )
        )
        return State.Ready(result)
    }
}

@Composable
private fun levelColor(level: ProgressVerdict.Level): Color {
    val c = NutriTheme.colors
    return when (level) {
        ProgressVerdict.Level.GOOD -> c.success
        ProgressVerdict.Level.WATCH -> c.warnTo
        ProgressVerdict.Level.BAD -> c.dangerTo
        ProgressVerdict.Level.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
}

private fun levelWord(level: ProgressVerdict.Level): String = when (level) {
    ProgressVerdict.Level.GOOD -> "хорошо"
    ProgressVerdict.Level.WATCH -> "внимание"
    ProgressVerdict.Level.BAD -> "плохо"
    ProgressVerdict.Level.UNKNOWN -> "нет данных"
}

@Composable
fun ProgressCheckScreen(onBack: () -> Unit, viewModel: ProgressCheckViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Screen)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(Space.Gap)
    ) {
        ScreenHeader(title = "Как идёт прогресс", subtitle = "Обновляется после каждого чек-ина", onBack = onBack)
        when (val s = state) {
            ProgressCheckViewModel.State.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ProgressCheckViewModel.State.Off -> FreshCard(Modifier.fillMaxWidth()) {
                Text(
                    "Экран работает в режиме «Сжигание жира» — включи его в Профиле.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(Space.Card)
                )
            }
            is ProgressCheckViewModel.State.Ready -> {
                val r = s.result
                val color = levelColor(r.level)
                TonalSection(color = color) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(color, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(r.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    }
                    r.focus?.let {
                        Spacer(Modifier.height(8.dp))
                        TinyLabel("Главное сейчас", color = color)
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                    r.sinceStart?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                FreshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.Card)) {
                        TinyLabel("Тело", color = scheme.primary)
                        r.items.take(3).forEachIndexed { i, item ->
                            if (i > 0) Hairline()
                            VerdictRow(item)
                        }
                        Spacer(Modifier.height(12.dp))
                        TinyLabel("Привычки", color = scheme.primary)
                        r.items.drop(3).forEachIndexed { i, item ->
                            if (i > 0) Hairline()
                            VerdictRow(item)
                        }
                    }
                }
                Text(
                    "Темп, талия и сила показывают результат, привычки объясняют, почему он такой. " +
                        "Темп и привычки — за последние 2 чек-ина, талия — за 3+ недели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VerdictRow(item: ProgressVerdict.Item) {
    val scheme = MaterialTheme.colorScheme
    val color = levelColor(item.level)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
                Text(item.value, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
            Text("${levelWord(item.level).replaceFirstChar { it.uppercase() }} · ${item.hint}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Вход на «Сегодня»: коротко, без расчётов — тап открывает экран. */
@Composable
fun ProgressCheckEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FreshCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(Space.Card)) {
            CardHeader(
                title = "Как идёт прогресс",
                icon = Icons.Rounded.Insights,
                tint = MaterialTheme.colorScheme.primary,
                subtitle = "Темп, талия, сила и привычки — хорошо или нет"
            )
        }
    }
}
