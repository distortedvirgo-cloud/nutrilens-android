package com.nutrilens.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette

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
    val c = NutriTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 120.dp)
    ) {
        ScreenHeader(title = greet.text, subtitle = "Ваш персональный ИИ-ассистент")

        BalanceCard(totals, settings.dailyGoal, macroGoals, streak, Modifier.staggeredIn(1))

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.staggeredIn(2)) {
            QuickAction(Icons.Rounded.AddAPhoto, "Приём пищи", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
                onNavigate("add")
            }
            QuickAction(Icons.Rounded.WaterDrop, "+250 мл", c.waterDeep, Modifier.weight(1f)) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.addWater(250)
            }
            QuickAction(Icons.Rounded.Lightbulb, "Идеи", c.warnTo, Modifier.weight(1f)) { onNavigate("ideas") }
            QuickAction(Icons.AutoMirrored.Rounded.Chat, "Диетолог", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) {
                onNavigate("chat")
            }
        }

        if (nextFocus.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            val parts = nextFocus.split("\n", limit = 2)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredIn(3)
                    .shadow(14.dp, RoundedCornerShape(24.dp), ambientColor = c.brandFrom.copy(alpha = 0.35f), spotColor = c.brandFrom.copy(alpha = 0.35f))
            ) {
                Row(
                    Modifier
                        .background(c.brandGradient)
                        .padding(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Rounded.TrackChanges,
                        contentDescription = null,
                        tint = c.onBrand,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TinyLabel("Следующий приём", color = c.onBrand.copy(alpha = 0.8f))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = parts.first(),
                            style = MaterialTheme.typography.titleMedium,
                            color = c.onBrand
                        )
                        if (parts.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = parts[1],
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.onBrand.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("Подсказки на сейчас")
        insights.forEachIndexed { i, insight ->
            InsightCard(
                insight = insight,
                waterLowAction = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.addWater(250)
                },
                onNavigate = onNavigate,
                modifier = Modifier.staggeredIn(4 + i)
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(6.dp))
        FreshCard(Modifier.fillMaxWidth(), onClick = { viewModel.toggleWorkout() }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                IconBadge(Icons.Rounded.FitnessCenter, MaterialTheme.colorScheme.secondary, size = 42)
                Spacer(Modifier.width(14.dp))
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
    }
}

@Composable
private fun BalanceCard(
    totals: com.nutrilens.app.insights.DayTotals,
    dailyGoal: Double,
    macroGoals: com.nutrilens.app.insights.MacroGoals,
    streak: Int,
    modifier: Modifier = Modifier
) {
    val remaining = (dailyGoal - totals.calories).toInt()
    val shown by animateFloatAsState(
        totals.calories.toFloat(), tween(Motion.ExtraLong, easing = Motion.Emphasized), label = "balanceCount"
    )
    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TinyLabel("Баланс дня", Modifier.weight(1f))
                if (streak > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.LocalFireDepartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$streak",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DisplayNumber("${shown.roundToInt()}", size = 36, color = MaterialTheme.colorScheme.onSurface)
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
                style = MaterialTheme.typography.labelLarge,
                color = if (remaining >= 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(Modifier.height(16.dp))
            val palette = macroPalette()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MacroMini("Белки", totals.protein, macroGoals.protein, palette.protein, Modifier.weight(1f))
                MacroMini("Жиры", totals.fat, macroGoals.fat, palette.fat, Modifier.weight(1f))
                MacroMini("Углев.", totals.carbs, macroGoals.carbs, palette.carbs, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MacroMini(
    label: String,
    value: Double,
    goal: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val fraction by animateFloatAsState(
        if (goal > 0) (value / goal).toFloat().coerceIn(0f, 1f) else 0f,
        tween(Motion.ExtraLong, easing = Motion.Emphasized),
        label = "macroMini"
    )
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "${value.roundToInt()}/${goal.roundToInt()} г",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.16f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FreshCard(modifier, radius = 22, onClick = onClick) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 4.dp)
        ) {
            IconBadge(icon, tint, size = 40)
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InsightCard(
    insight: Insight,
    waterLowAction: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val action: Pair<String, () -> Unit>? = when (insight.id) {
        "empty", "gap" -> "Добавить" to { onNavigate("add") }
        "over", "protein-low", "evening", "workout" -> "Идеи" to { onNavigate("ideas") }
        "water-low" -> "+250 мл" to waterLowAction
        else -> null
    }
    FreshCard(modifier.fillMaxWidth(), radius = 22) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Text(insight.emoji, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
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
                Spacer(Modifier.width(6.dp))
                Surface(
                    onClick = action.second,
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        action.first,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitsCard(habitIds: Set<String>, onToggle: (String) -> Unit) {
    val done = HABITS.count { it.first in habitIds }
    val progress by animateFloatAsState(
        done / HABITS.size.toFloat(), Motion.settle(), label = "habitsProgress"
    )
    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Привычки дня",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$done/${HABITS.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(NutriTheme.colors.brandGradient)
                )
            }
            Spacer(Modifier.height(8.dp))
            HABITS.forEach { (id, label) ->
                val checked = id in habitIds
                val fill by animateColorAsState(
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    tween(Motion.Medium),
                    label = "habitFill"
                )
                val checkScale by animateFloatAsState(
                    if (checked) 1f else 0f, Motion.press(), label = "habitCheck"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(id) }
                        )
                        .padding(horizontal = 4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(fill)
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer {
                                    scaleX = checkScale
                                    scaleY = checkScale
                                }
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (checked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (checked) TextDecoration.LineThrough else null
                    )
                }
            }
        }
    }
}
