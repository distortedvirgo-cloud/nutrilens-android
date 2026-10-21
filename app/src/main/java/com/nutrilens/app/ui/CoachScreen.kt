package com.nutrilens.app.ui

import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.EggAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

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

    /** Последние реплики диалога с диетологом — превью на экране «Коуч». */
    private val _chatPreview = MutableStateFlow<List<com.nutrilens.app.data.ChatMessage>>(emptyList())
    val chatPreview: StateFlow<List<com.nutrilens.app.data.ChatMessage>> = _chatPreview.asStateFlow()

    fun reloadChat() {
        viewModelScope.launch {
            _chatPreview.value = com.nutrilens.app.data.ChatStore.load(getApplication()).takeLast(2)
        }
    }

    init {
        viewModelScope.launch {
            _waterMl.value = mealRepository.getWaterMl(today)
            _workoutDone.value = mealRepository.getWorkoutDone(today)
            _streak.value = calcStreak(mealRepository.allMealDates().toSet(), LocalDate.now())
            _hasAnyMeals.value = mealRepository.allMealDates().isNotEmpty()
            _lastWeight.value = mealRepository.getLatestWeight()
        }
        reloadChat()
        viewModelScope.launch {
            com.nutrilens.app.data.DataEvents.changed.collect {
                _waterMl.value = mealRepository.getWaterMl(today)
                _workoutDone.value = mealRepository.getWorkoutDone(today)
                _lastWeight.value = mealRepository.getLatestWeight()
            }
        }
        habitsRepository.observeDate(today)
            .onEach { logs -> _habitIds.value = logs.map { it.habitId }.toSet() }
            .launchIn(viewModelScope)
    }

    fun addWater(deltaMl: Int) {
        viewModelScope.launch {
            _waterMl.value = com.nutrilens.app.data.QuickActions.addWater(getApplication(), deltaMl)
        }
    }

    fun toggleWorkout() {
        viewModelScope.launch {
            _workoutDone.value = com.nutrilens.app.data.QuickActions.toggleWorkout(getApplication())
        }
    }

    fun toggleHabit(habitId: String) {
        viewModelScope.launch { habitsRepository.toggle(today, habitId) }
    }
}

/** Инструменты ИИ: иконка, подпись, маршрут. */
private data class CoachTool(val icon: ImageVector, val label: String, val route: String, val tone: Int)

private val COACH_TOOLS = listOf(
    CoachTool(Icons.Rounded.Lightbulb, "Идеи еды", "ideas", 0),
    CoachTool(Icons.Rounded.Kitchen, "Холодильник", "fridge", 1),
    CoachTool(Icons.AutoMirrored.Rounded.MenuBook, "Меню кафе", "menu", 2),
    CoachTool(Icons.Rounded.ShoppingCart, "Покупки", "grocery", 3),
    CoachTool(Icons.Rounded.Psychology, "Привычки", "habitTool", 4),
    CoachTool(Icons.Rounded.WaterDrop, "Вода", "waterTool", 5)
)

/**
 * «Коуч» — всё про ИИ в одном месте: план на ближайший приём, диалог с
 * диетологом, инструменты (идеи, холодильник, меню, покупки, привычки, вода),
 * подсказки на сейчас и привычки дня. Заменяет прежние «Ассистент» и «Ещё».
 */
@Composable
fun CoachScreen(
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
    val chatPreview by viewModel.chatPreview.collectAsStateWithLifecycle()

    // Вернулись из чата — освежаем превью диалога.
    LaunchedEffect(Unit) { viewModel.reloadChat() }

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
    val remaining = (settings.dailyGoal - totals.calories).roundToInt()

    val view = LocalView.current
    val c = NutriTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 120.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 12.dp, bottom = 18.dp)
                .staggeredIn(0)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(c.brandFrom, MaterialTheme.colorScheme.secondary)))
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Коуч", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "${greet.text} · знает твой дневник и цели",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // План на ближайший приём пищи.
        FreshCard(Modifier.fillMaxWidth().staggeredIn(1)) {
            Column(Modifier.padding(18.dp)) {
                TinyLabel(if (remaining >= 0) "Осталось $remaining ккал" else "Перебор ${-remaining} ккал")
                Spacer(Modifier.height(6.dp))
                val parts = nextFocus.split("\n", limit = 2)
                Text(
                    parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Запиши первый приём — подскажу, что дальше",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (parts.size > 1 && parts[1].isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(parts[1], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NutriChip("Идеи еды", onClick = { onNavigate("ideas") }, primary = true)
                    NutriChip("Что в холодильнике?", onClick = { onNavigate("fridge") })
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        ChatEntryCard(
            preview = chatPreview,
            onOpen = { onNavigate("chat") },
            onAsk = { q -> onNavigate("chat?q=" + android.net.Uri.encode(q)) },
            modifier = Modifier.staggeredIn(2)
        )

        Spacer(Modifier.height(22.dp))
        TinyLabel("Инструменты", Modifier.padding(start = 4.dp, bottom = 10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.staggeredIn(3)
        ) {
            items(COACH_TOOLS) { tool ->
                ToolTile(tool, onClick = { onNavigate(tool.route) })
            }
        }

        if (insights.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            TinyLabel("Подсказки на сейчас", Modifier.padding(start = 4.dp, bottom = 10.dp))
            insights.take(4).forEachIndexed { i, insight ->
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
        }

        Spacer(Modifier.height(12.dp))
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
                NutriSwitch(checked = workoutDone, onCheckedChange = { viewModel.toggleWorkout() })
            }
        }

        Spacer(Modifier.height(16.dp))
        HabitsCard(habitIds = habitIds, onToggle = { id ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            viewModel.toggleHabit(id)
        })
    }
}

/** Плитка инструмента в карусели. */
@Composable
private fun ToolTile(tool: CoachTool, onClick: () -> Unit) {
    val c = NutriTheme.colors
    val palette = macroPalette()
    val tint = when (tool.tone) {
        0 -> c.warnTo
        1 -> MaterialTheme.colorScheme.secondary
        2 -> palette.carbs
        3 -> MaterialTheme.colorScheme.primary
        4 -> palette.protein
        else -> c.water
    }
    FreshCard(Modifier.width(112.dp), onClick = onClick) {
        Column(Modifier.padding(14.dp)) {
            IconBadge(tool.icon, tint, size = 40)
            Spacer(Modifier.height(12.dp))
            Text(
                tool.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/**
 * Вход в диалог с диетологом: последние реплики и строка вопроса. Вопрос
 * отправляется сразу — чат открывается уже с ним.
 */
@Composable
private fun ChatEntryCard(
    preview: List<com.nutrilens.app.data.ChatMessage>,
    onOpen: () -> Unit,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = NutriTheme.colors
    var question by remember { mutableStateOf("") }
    FreshCard(modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TinyLabel("Диалог с диетологом", Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Открыть чат",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (preview.isEmpty()) {
                Text(
                    "Спроси что угодно о питании — диетолог видит твой дневник за последние дни.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                preview.forEach { msg ->
                    val user = msg.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                        Text(
                            msg.text.ifBlank { "Фото" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (user) c.onBrand else MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(Radius.Small))
                                .background(
                                    if (user) c.brandGradient
                                    else SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 4.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = question,
                    onValueChange = { question = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (question.isNotBlank()) { onAsk(question.trim()); question = "" } }
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (question.isEmpty()) {
                                Text(
                                    "Спроси о питании…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Вопрос диетологу" }
                )
                IconButton(
                    onClick = { if (question.isNotBlank()) { onAsk(question.trim()); question = "" } else onOpen() },
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(c.brandGradient)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить", tint = c.onBrand, modifier = Modifier.size(20.dp))
                }
            }
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
    val (icon, tint) = insightVisual(insight.id)
    FreshCard(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
        ) {
            IconBadge(icon, tint, size = 40)
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
                Spacer(Modifier.width(8.dp))
                NutriChip(action.first, onClick = action.second, primary = true)
            }
        }
    }
}

/** Иконка и цвет подсказки — те же семантические цвета, что во всём приложении. */
@Composable
private fun insightVisual(id: String): Pair<ImageVector, Color> {
    val c = NutriTheme.colors
    val palette = macroPalette()
    return when (id) {
        "empty" -> Icons.Rounded.AddAPhoto to MaterialTheme.colorScheme.primary
        "over" -> Icons.Rounded.WarningAmber to MaterialTheme.colorScheme.tertiary
        "protein-low", "protein-ok" -> Icons.Rounded.EggAlt to palette.protein
        "water-low", "water-ok" -> Icons.Rounded.WaterDrop to c.water
        "gap" -> Icons.Rounded.Schedule to c.warnTo
        "evening" -> Icons.Rounded.DinnerDining to palette.carbs
        "nearly" -> Icons.Rounded.TrackChanges to MaterialTheme.colorScheme.primary
        "workout" -> Icons.Rounded.FitnessCenter to MaterialTheme.colorScheme.secondary
        "streak" -> Icons.Rounded.LocalFireDepartment to c.warnTo
        else -> Icons.Rounded.Lightbulb to MaterialTheme.colorScheme.primary
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
            ProgressTrack(fraction = progress)
            Spacer(Modifier.height(8.dp))
            HABITS.forEach { (id, label) ->
                val checked = id in habitIds
                CheckRow(checked = checked, onToggle = { onToggle(id) }) {
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
