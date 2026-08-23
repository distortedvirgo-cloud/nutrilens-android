package com.nutrilens.app.ui

import android.app.Application
import android.view.HapticFeedbackConstants
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.HabitsRepository
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.WaterRepository
import com.nutrilens.app.insights.Insight
import com.nutrilens.app.insights.InsightContext
import com.nutrilens.app.insights.buildInsights
import com.nutrilens.app.insights.calcStreak
import com.nutrilens.app.insights.effectiveMacroGoals
import com.nutrilens.app.insights.getDayTotals
import com.nutrilens.app.insights.greeting
import com.nutrilens.app.insights.nextMealFocus
import com.nutrilens.app.insights.waterNormaMl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** Постоянный список привычек дня (как в веб-версии). */
private val HABITS = listOf(
    "water" to "Выпить норму воды",
    "protein" to "Белок в каждом приёме",
    "vegs" to "Овощи или фрукты",
    "move" to "30 минут активности",
    "no_late" to "Не есть за 3 ч до сна"
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val mealRepository = MealRepository(
        database.mealDao(),
        database.waterDao(),
        database.weightDao(),
        database.workoutDao()
    )
    private val settingsRepository = SettingsRepository(database.settingsDao())
    private val waterRepository = WaterRepository(database.waterDao())
    private val habitsRepository = HabitsRepository(database.habitLogDao())

    private val today = LocalDate.now().toString()

    val settings: StateFlow<SettingsEntity> = settingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    val meals = mealRepository.observeDayMeals(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _waterMl = MutableStateFlow(0)
    val waterMl: StateFlow<Int> = _waterMl.asStateFlow()

    private val _workoutDone = MutableStateFlow(false)
    val workoutDone: StateFlow<Boolean> = _workoutDone.asStateFlow()

    private val _habitIds = MutableStateFlow<Set<String>>(emptySet())
    val habitIds: StateFlow<Set<String>> = _habitIds.asStateFlow()

    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak.asStateFlow()

    private val _hasAnyMeals = MutableStateFlow(false)
    val hasAnyMeals: StateFlow<Boolean> = _hasAnyMeals.asStateFlow()

    private val _lastWeight = MutableStateFlow<Double?>(null)
    val lastWeight: StateFlow<Double?> = _lastWeight.asStateFlow()

    init {
        viewModelScope.launch {
            _waterMl.value = mealRepository.getWaterMl(today)
            _workoutDone.value = mealRepository.getWorkoutDone(today)
            _streak.value = calcStreak(mealRepository.allMealDates().toSet(), LocalDate.now())
            _hasAnyMeals.value = mealRepository.allMealDates().isNotEmpty()
            _lastWeight.value = mealRepository.getLatestWeight()
        }
        habitsRepository.observeDate(today)
            .onEach { logs -> _habitIds.value = logs.map { it.habitId }.toSet() }
            .launchIn(viewModelScope)
    }

    fun addWater(deltaMl: Int) {
        viewModelScope.launch {
            waterRepository.addWater(today, deltaMl)
            _waterMl.value = mealRepository.getWaterMl(today)
        }
    }

    fun toggleWorkout() {
        viewModelScope.launch {
            val next = !_workoutDone.value
            mealRepository.setWorkoutDone(today, next)
            _workoutDone.value = next
        }
    }

    fun toggleHabit(habitId: String) {
        viewModelScope.launch { habitsRepository.toggle(today, habitId) }
    }
}

@Composable
fun AssistantScreen(
    onNavigate: (String) -> Unit,
    viewModel: AssistantViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val meals by viewModel.meals.collectAsStateWithLifecycle()
    val waterMl by viewModel.waterMl.collectAsStateWithLifecycle()
    val workoutDone by viewModel.workoutDone.collectAsStateWithLifecycle()
    val habitIds by viewModel.habitIds.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val hasAnyMeals by viewModel.hasAnyMeals.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()

    val totals = remember(meals) { getDayTotals(meals.map { it.meal }) }
    val hour = remember { LocalTime.now().hour }
    val greet = remember(hour) { greeting(hour) }
    val macroGoals = remember(settings, lastWeight) {
        effectiveMacroGoals(
            settings.proteinGoal, settings.fatGoal, settings.carbsGoal,
            settings.dailyGoal, lastWeight
        )
    }
    val waterNorm = remember(lastWeight, workoutDone) { waterNormaMl(lastWeight, workoutDone) }
    val insights = remember(totals, settings, macroGoals, waterMl, waterNorm, streak, workoutDone, hasAnyMeals) {
        buildInsights(
            InsightContext(
                hour = hour,
                totals = totals,
                dailyGoal = settings.dailyGoal,
                macroGoals = macroGoals,
                waterMl = waterMl,
                waterNormMl = waterNorm,
                streak = streak,
                workoutDone = workoutDone,
                hasAnyMealsEver = hasAnyMeals
            )
        )
    }
    val nextFocus = remember(totals, macroGoals) { nextMealFocus(totals, macroGoals) }

    val view = LocalView.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 110.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${greet.emoji} ${greet.text}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Ваш персональный ИИ-ассистент",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (streak > 0) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "🔥 $streak",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        BalanceCard(totals, settings.dailyGoal, macroGoals)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("🍽️", "Приём пищи", Modifier.weight(1f)) { onNavigate("add") }
            QuickAction("💧", "+250 мл", Modifier.weight(1f)) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.addWater(250)
            }
            QuickAction("💡", "Идеи", Modifier.weight(1f)) { onNavigate("ideas") }
            QuickAction("💬", "Диетолог", Modifier.weight(1f)) { onNavigate("chat") }
        }

        if (nextFocus.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            val parts = nextFocus.split("\n", limit = 2)
            FreshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "🎯 ${parts.first()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (parts.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = parts[1],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Подсказки на сейчас")
        insights.forEach { insight ->
            InsightCard(insight = insight, waterLowAction = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.addWater(250)
            }, onNavigate = onNavigate)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        FreshCard(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleWorkout() }
                    .padding(16.dp)
            ) {
                Text("💪", fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Тренировка сегодня",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Добавляет +500 мл к норме воды",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = workoutDone, onCheckedChange = { viewModel.toggleWorkout() })
            }
        }

        Spacer(Modifier.height(16.dp))
        HabitsCard(habitIds = habitIds, onToggle = { id ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel.toggleHabit(id)
        })

        Spacer(Modifier.height(16.dp))
        SectionTitle("Инструменты")
        ToolsGrid(onNavigate)
    }
}

@Composable
private fun BalanceCard(
    totals: com.nutrilens.app.insights.DayTotals,
    dailyGoal: Double,
    macroGoals: com.nutrilens.app.insights.MacroGoals
) {
    val remaining = (dailyGoal - totals.calories).toInt()
    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Баланс дня",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${totals.calories.roundToInt()}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "из ${dailyGoal.roundToInt()} ккал",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Text(
                if (remaining >= 0) "Осталось $remaining ккал" else "Перебор ${-remaining} ккал",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (remaining >= 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(12.dp))
            val palette = com.nutrilens.app.ui.theme.macroPalette()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MacroMini("Б", totals.protein, macroGoals.protein, palette.protein, Modifier.weight(1f))
                MacroMini("Ж", totals.fat, macroGoals.fat, palette.fat, Modifier.weight(1f))
                MacroMini("У", totals.carbs, macroGoals.carbs, palette.carbs, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MacroMini(
    label: String,
    value: Double,
    goal: Double,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            "$label ${value.roundToInt()}/${goal.roundToInt()} г",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (goal > 0) (value / goal).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.15f)
        )
    }
}

@Composable
private fun QuickAction(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FreshCard(modifier.clickable(onClick = onClick)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InsightCard(
    insight: Insight,
    waterLowAction: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val action: Pair<String, () -> Unit>? = when (insight.id) {
        "empty", "gap" -> "Добавить" to { onNavigate("add") }
        "over", "protein-low", "evening", "workout" -> "Идеи" to { onNavigate("ideas") }
        "water-low" -> "+250 мл" to waterLowAction
        else -> null
    }
    FreshCard(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 6.dp)
        ) {
            Text(insight.emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    insight.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    insight.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (action != null) {
                TextButton(onClick = action.second) {
                    Text(action.first, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HabitsCard(habitIds: Set<String>, onToggle: (String) -> Unit) {
    val done = HABITS.count { it.first in habitIds }
    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Привычки дня",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$done/${HABITS.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { done / HABITS.size.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(8.dp))
            HABITS.forEach { (id, label) ->
                val checked = id in habitIds
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(id) }
                        .padding(vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .then(
                                if (checked) {
                                    Modifier
                                        .padding(0.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (checked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (checked) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (checked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolsGrid(onNavigate: (String) -> Unit) {
    val tools = listOf(
        Triple("💬", "Диетолог", "chat"),
        Triple("💡", "Идеи еды", "ideas"),
        Triple("🧊", "Холодильник", "fridge"),
        Triple("🍽️", "Ресторан", "menu"),
        Triple("🛒", "Покупки", "grocery"),
        Triple("💧", "Вода", "waterTool"),
        Triple("🧠", "Привычки", "habitTool"),
        Triple("⚙️", "Настройки", "settings")
    )
    var index = 0
    while (index < tools.size) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCell(tools[index], Modifier.weight(1f), onNavigate)
            if (index + 1 < tools.size) {
                ToolCell(tools[index + 1], Modifier.weight(1f), onNavigate)
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        if (index + 2 < tools.size) Spacer(Modifier.height(8.dp))
        index += 2
    }
}

@Composable
private fun ToolCell(
    tool: Triple<String, String, String>,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    FreshCard(modifier.clickable { onNavigate(tool.third) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Text(tool.first, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                tool.second,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
