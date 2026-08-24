package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nutrilens.app.data.FavoriteEntity
import com.nutrilens.app.data.FavoriteRepository
import com.nutrilens.app.data.MealEntity
import com.nutrilens.app.data.MealItemEntity
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.MealWithImages
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.WaterRepository
import com.nutrilens.app.data.WeightRepository
import com.nutrilens.app.insights.InsightContext
import com.nutrilens.app.insights.buildInsights
import com.nutrilens.app.insights.calcStreak
import com.nutrilens.app.insights.effectiveMacroGoals
import com.nutrilens.app.insights.getDayTotals
import com.nutrilens.app.insights.greeting
import com.nutrilens.app.insights.nextMealFocus
import com.nutrilens.app.insights.waterNormaMl
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val mealRepository = MealRepository(
        database.mealDao(),
        database.waterDao(),
        database.weightDao(),
        database.workoutDao()
    )
    private val settingsRepository = SettingsRepository(database.settingsDao())
    private val waterRepository = WaterRepository(database.waterDao())
    private val weightRepository = WeightRepository(database.weightDao())
    private val favoriteRepository = FavoriteRepository(database.favoriteDao())

    val favorites: StateFlow<List<FavoriteEntity>> = favoriteRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val meals: StateFlow<List<MealWithImages>> = _selectedDate
        .flatMapLatest { date -> mealRepository.observeDayMeals(date.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<SettingsEntity> = settingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    private val _waterMl = MutableStateFlow(0)
    val waterMl: StateFlow<Int> = _waterMl.asStateFlow()

    private val _lastWeight = MutableStateFlow<Double?>(null)
    val lastWeight: StateFlow<Double?> = _lastWeight.asStateFlow()

    /** null = записей о весе пока нет, иначе дни с последнего взвешивания. */
    private val _daysSinceLastWeight = MutableStateFlow<Int?>(null)
    val daysSinceLastWeight: StateFlow<Int?> = _daysSinceLastWeight.asStateFlow()

    private val _workoutDone = MutableStateFlow(false)
    val workoutDone: StateFlow<Boolean> = _workoutDone.asStateFlow()

    /** Серия дней подряд с записями (как в веб-версии). */
    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak.asStateFlow()

    private val _hasAnyMeals = MutableStateFlow(false)
    val hasAnyMeals: StateFlow<Boolean> = _hasAnyMeals.asStateFlow()

    init {
        viewModelScope.launch {
            _selectedDate.collectLatest { date ->
                val day = date.toString()
                _waterMl.value = mealRepository.getWaterMl(day)
                _workoutDone.value = mealRepository.getWorkoutDone(day)
                _lastWeight.value = mealRepository.getLatestWeight()
                _daysSinceLastWeight.value = weightRepository.daysSinceLastWeight()
                val dates = mealRepository.allMealDates().toSet()
                _streak.value = calcStreak(dates, date)
                _hasAnyMeals.value = dates.isNotEmpty()
            }
        }
    }

    fun toggleWorkout() {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val next = !_workoutDone.value
            mealRepository.setWorkoutDone(today, next)
            _workoutDone.value = next
        }
    }

    fun selectDay(date: LocalDate) {
        _selectedDate.value = date
    }

    fun shiftDay(offset: Long) {
        _selectedDate.value = _selectedDate.value.plusDays(offset)
    }

    fun goToday() {
        _selectedDate.value = LocalDate.now()
    }

    fun addWater() {
        viewModelScope.launch {
            waterRepository.addWater(LocalDate.now().toString(), 250)
            _waterMl.value = mealRepository.getWaterMl(_selectedDate.value.toString())
        }
    }

    fun updateWeight(weight: Double) {
        viewModelScope.launch {
            weightRepository.addWeight(LocalDate.now().toString(), weight)
            refreshWeightState()
        }
    }

    fun deleteMeal(meal: MealWithImages) {
        viewModelScope.launch {
            mealRepository.deleteMeal(meal.meal, meal.images)
        }
    }

    /** Восстановление после удаления (кнопка «Вернуть» в снекбаре). */
    fun restoreMeal(meal: MealWithImages, items: List<MealItemEntity>) {
        viewModelScope.launch {
            mealRepository.addMeal(meal.meal, meal.images, items)
        }
    }

    fun addFavorite(meal: MealEntity) {
        viewModelScope.launch {
            favoriteRepository.add(meal.name, meal.calories, meal.protein, meal.fat, meal.carbs)
        }
    }

    fun updateMeal(updated: MealEntity) {
        viewModelScope.launch {
            mealRepository.updateMeal(updated)
        }
    }

    suspend fun itemsForMeal(mealId: String): List<MealItemEntity> =
        mealRepository.itemsForMeal(mealId)

    private suspend fun refreshWeightState() {
        _lastWeight.value = mealRepository.getLatestWeight()
        _daysSinceLastWeight.value = weightRepository.daysSinceLastWeight()
    }
}

@Composable
fun DashboardScreen(
    initialDate: String? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: DashboardViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val meals by viewModel.meals.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val waterMl by viewModel.waterMl.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()
    val daysSince by viewModel.daysSinceLastWeight.collectAsStateWithLifecycle()
    val workoutDone by viewModel.workoutDone.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val hasAnyMeals by viewModel.hasAnyMeals.collectAsStateWithLifecycle()
    val view = LocalView.current

    LaunchedEffect(Unit) {
        val date = initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        viewModel.selectDay(date)
    }

    var detailsMeal by remember { mutableStateOf<MealWithImages?>(null) }
    var detailItems by remember { mutableStateOf<List<MealItemEntity>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<MealWithImages?>(null) }
    var editTarget by remember { mutableStateOf<MealWithImages?>(null) }
    var showWeightDialog by remember { mutableStateOf(false) }

    fun isFavorite(meal: MealEntity): Boolean =
        favorites.any { it.name == meal.name && kotlin.math.abs(it.calories - meal.calories) < 0.5 }

    LaunchedEffect(detailsMeal?.meal?.id) {
        detailsMeal?.let { detailItems = viewModel.itemsForMeal(it.meal.id) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DaySelectorRow(
                selected = selectedDate,
                streak = streak,
                onShift = viewModel::shiftDay,
                onToday = viewModel::goToday
            )
        }
        item { CaloriesHero(meals = meals, settings = settings) }
        item { MacrosRow(meals = meals, settings = settings, lastWeight = lastWeight) }
        item {
            WaterCard(
                waterMl = waterMl,
                lastWeight = lastWeight,
                workoutDone = workoutDone,
                onAddWater = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.addWater()
                }
            )
        }
        item {
            InsightsPanel(
                meals = meals,
                settings = settings,
                waterMl = waterMl,
                lastWeight = lastWeight,
                workoutDone = workoutDone,
                streak = streak,
                hasAnyMeals = hasAnyMeals
            )
        }
        item {
            WeightCard(
                lastWeight = lastWeight,
                daysSince = daysSince,
                onWeighIn = { showWeightDialog = true }
            )
        }
        if (meals.isEmpty()) {
            item { EmptyState() }
        } else {
            item { MealsHeader(count = meals.size) }
            items(meals, key = { it.meal.id }) { meal ->
                MealCard(
                    meal = meal,
                    showFavorite = !isFavorite(meal.meal),
                    onFavorite = { viewModel.addFavorite(meal.meal) },
                    onClick = {
                        detailsMeal = meal
                        detailItems = emptyList()
                    },
                    onDelete = { deleteTarget = meal }
                )
            }
        }
    }

    detailsMeal?.let { meal ->
        MealDetailsDialog(
            meal = meal,
            items = detailItems,
            onClose = { detailsMeal = null },
            onEdit = {
                editTarget = meal
                detailsMeal = null
            }
        )
    }

    editTarget?.let { meal ->
        MealEditDialog(
            meal = meal.meal,
            onSave = { updated ->
                viewModel.updateMeal(updated)
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    deleteTarget?.let { meal ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить запись?") },
            text = { Text("«${meal.meal.name}» будет удалён вместе с фотографиями.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val items = viewModel.itemsForMeal(meal.meal.id)
                            viewModel.deleteMeal(meal)
                            val result = snackbarHostState.showSnackbar(
                                message = "Запись удалена",
                                actionLabel = "Вернуть"
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restoreMeal(meal, items)
                            }
                        }
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (showWeightDialog) {
        WeightDialog(
            onConfirm = { weight -> viewModel.updateWeight(weight) },
            onDismiss = { showWeightDialog = false }
        )
    }
}

// ---------- Шапка: выбор дня ----------

private val MONTHS_RU = arrayOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря"
)

private fun dateLabelRu(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "сегодня"
        today.minusDays(1) -> "вчера"
        else -> "${date.dayOfMonth} ${MONTHS_RU[date.monthValue - 1]}"
    }
}

@Composable
private fun DaySelectorRow(
    selected: LocalDate,
    streak: Int,
    onShift: (Long) -> Unit,
    onToday: () -> Unit
) {
    val g = greeting(LocalTime.now().hour)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${g.emoji} ${g.text}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = dateLabelRu(selected).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (streak > 0) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text(
                    text = "🔥 $streak",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        if (selected != LocalDate.now()) {
            Surface(
                onClick = onToday,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "Сегодня",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = { onShift(-1) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Предыдущий день",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { onShift(1) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Следующий день",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Герой-карточка: калорийное кольцо ----------

@Composable
private fun CaloriesHero(meals: List<MealWithImages>, settings: SettingsEntity) {
    val eaten = meals.sumOf { it.meal.calories }
    val goal = settings.dailyGoal
    val target = if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(targetValue = target, label = "ring")
    val isOver = goal > 0 && eaten > goal
    val arcColor = if (isOver) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // Тап по карточке переключает центр кольца: съедено ↔ остаток/перебор.
    var showRemaining by remember { mutableStateOf(false) }

    Surface(
        onClick = { showRemaining = !showRemaining },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    val stroke = 14.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2f
                    val topLeft = Offset(
                        (size.width - radius * 2) / 2f,
                        (size.height - radius * 2) / 2f
                    )
                    val arcSize = Size(radius * 2, radius * 2)
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    if (fraction > 0f) {
                        drawArc(
                            color = arcColor,
                            startAngle = -90f,
                            sweepAngle = 360f * fraction,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showRemaining) {
                        val delta = (goal - eaten).roundToInt()
                        Text(
                            text = "${kotlin.math.abs(delta)}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (delta < 0) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (delta < 0) "перебор ккал" else "осталось ккал",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "${eaten.roundToInt()}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "из ${goal.roundToInt()} ккал",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (isOver) {
                Text(
                    text = "Перебор на ${(eaten - goal).roundToInt()} ккал — ничего страшного 🌿",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "Осталось ${(goal - eaten).roundToInt()} ккал",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- Макросы ----------

private val WATER_COLOR = Color(0xFF4FA3D8)

@Composable
private fun MacrosRow(
    meals: List<MealWithImages>,
    settings: SettingsEntity,
    lastWeight: Double?
) {
    val protein = meals.sumOf { it.meal.protein }
    val fat = meals.sumOf { it.meal.fat }
    val carbs = meals.sumOf { it.meal.carbs }

    // Цели как в веб-версии: свои из настроек или расчёт от веса/калорий.
    val goals = effectiveMacroGoals(
        settings.proteinGoal, settings.fatGoal, settings.carbsGoal,
        settings.dailyGoal, lastWeight
    )
    val palette = macroPalette()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MacroCard(
            label = "Белки",
            eaten = protein,
            goal = goals.protein,
            color = palette.protein,
            modifier = Modifier.weight(1f)
        )
        MacroCard(
            label = "Жиры",
            eaten = fat,
            goal = goals.fat,
            color = palette.fat,
            modifier = Modifier.weight(1f)
        )
        MacroCard(
            label = "Углев.",
            eaten = carbs,
            goal = goals.carbs,
            color = palette.carbs,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MacroCard(
    label: String,
    eaten: Double,
    goal: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val fraction = if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${eaten.roundToInt()} г",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "цель ${goal.roundToInt()} г",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Вода ----------

@Composable
private fun WaterCard(
    waterMl: Int,
    lastWeight: Double?,
    workoutDone: Boolean,
    onAddWater: () -> Unit
) {
    val normMl = waterNormaMl(lastWeight, workoutDone)
    val fraction = if (normMl > 0) (waterMl.toFloat() / normMl).coerceIn(0f, 1f) else 0f
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Вода 💧",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WATER_COLOR)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$waterMl мл из ~$normMl мл",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = onAddWater,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "+250 мл",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ---------- Панель инсайтов (порт веб-«Ассистента») ----------

@Composable
private fun InsightsPanel(
    meals: List<MealWithImages>,
    settings: SettingsEntity,
    waterMl: Int,
    lastWeight: Double?,
    workoutDone: Boolean,
    streak: Int,
    hasAnyMeals: Boolean
) {
    val totals = getDayTotals(meals.map { it.meal })
    val goals = effectiveMacroGoals(
        settings.proteinGoal, settings.fatGoal, settings.carbsGoal,
        settings.dailyGoal, lastWeight
    )
    val insights = buildInsights(
        InsightContext(
            hour = LocalTime.now().hour,
            totals = totals,
            dailyGoal = settings.dailyGoal,
            macroGoals = goals,
            waterMl = waterMl,
            waterNormMl = waterNormaMl(lastWeight, workoutDone),
            streak = streak,
            workoutDone = workoutDone,
            hasAnyMealsEver = hasAnyMeals
        )
    ).take(3)
    if (insights.isEmpty()) return

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Ассистент ✨",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            insights.forEach { insight ->
                Row(modifier = Modifier.padding(vertical = 5.dp)) {
                    Text(
                        text = insight.emoji,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = insight.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            val focus = nextMealFocus(totals, goals)
            if (focus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                val (focusTitle, focusText) = focus.split("\n", limit = 2)
                    .let { it[0] to it.getOrElse(1) { "" } }
                Text(
                    text = "🎯 $focusTitle",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (focusText.isNotBlank()) {
                    Text(
                        text = focusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------- Вес ----------

@Composable
private fun WeightCard(
    lastWeight: Double?,
    daysSince: Int?,
    onWeighIn: () -> Unit
) {
    val needsWeighIn = daysSince == null || daysSince >= 7
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (needsWeighIn) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        onClick = onWeighIn
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚖️", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (needsWeighIn) {
                    Text(
                        text = "Пора взвеситься!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = if (daysSince == null) {
                            "Записей о весе пока нет"
                        } else {
                            "Последний раз: ${dateLabelRu(LocalDate.now().minusDays(daysSince.toLong()))}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                    )
                } else {
                    Text(
                        text = "Вес: ${lastWeight?.let { "%.1f".format(it) } ?: "—"} кг",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Нажмите, чтобы обновить",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------- Список приёмов пищи ----------

@Composable
private fun MealsHeader(count: Int) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = "Приёмы пищи",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Записей: $count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MealCard(
    meal: MealWithImages,
    showFavorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MealThumbnail(meal)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meal.meal.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meal.meal.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Б ${meal.meal.protein.roundToInt()} · Ж ${meal.meal.fat.roundToInt()} · " +
                        "У ${meal.meal.carbs.roundToInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${meal.meal.calories.roundToInt()}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ккал",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConfidenceBadge(score = meal.meal.confidenceScore)
            }
            Spacer(Modifier.width(4.dp))
            Column {
                if (showFavorite) {
                    IconButton(onClick = onFavorite) {
                        Icon(
                            imageVector = Icons.Filled.StarBorder,
                            contentDescription = "В избранное",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MealThumbnail(meal: MealWithImages) {
    val photo = meal.images.firstOrNull { it.kind == "THUMB" }
        ?: meal.images.firstOrNull { it.kind == "FULL" }
    if (photo != null) {
        AsyncImage(
            model = File(photo.path),
            contentDescription = meal.meal.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ConfidenceBadge(score: Double) {
    val rounded = score.roundToInt()
    if (rounded <= 0) return
    val color = when {
        rounded >= 7 -> Color(0xFF2E7D4F)
        rounded >= 4 -> Color(0xFFEF6C00)
        else -> Color(0xFFD32F2F)
    }
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$rounded",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------- Диалог деталей ----------

@Composable
private fun MealDetailsDialog(
    meal: MealWithImages,
    items: List<MealItemEntity>,
    onClose: () -> Unit,
    onEdit: () -> Unit
) {
    val m = meal.meal
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "${m.time} · ${m.calories.roundToInt()} ккал",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Б ${m.protein.roundToInt()} · Ж ${m.fat.roundToInt()} · У ${m.carbs.roundToInt()} г",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (items.isNotEmpty()) {
                    Text("Продукты", style = MaterialTheme.typography.titleSmall)
                    items.forEach { item ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "${item.name} — ${item.weightG.roundToInt()} г, " +
                                    "${item.calories.roundToInt()} ккал",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (item.breakdown.isNotBlank()) {
                                Text(
                                    text = item.breakdown,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (m.aiThoughts.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Мысли ИИ", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = m.aiThoughts,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Закрыть") }
        },
        dismissButton = {
            TextButton(onClick = onEdit) { Text("Изменить") }
        }
    )
}

// ---------- Диалог правки приёма пищи ----------

@Composable
private fun MealEditDialog(
    meal: MealEntity,
    onSave: (MealEntity) -> Unit,
    onDismiss: () -> Unit
) {
    fun fmt(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    var name by remember(meal.id) { mutableStateOf(meal.name) }
    var calories by remember(meal.id) { mutableStateOf(fmt(meal.calories)) }
    var protein by remember(meal.id) { mutableStateOf(fmt(meal.protein)) }
    var fat by remember(meal.id) { mutableStateOf(fmt(meal.fat)) }
    var carbs by remember(meal.id) { mutableStateOf(fmt(meal.carbs)) }

    val caloriesValue = calories.trim().replace(",", ".").toDoubleOrNull()
    val proteinValue = protein.trim().replace(",", ".").toDoubleOrNull()
    val fatValue = fat.trim().replace(",", ".").toDoubleOrNull()
    val carbsValue = carbs.trim().replace(",", ".").toDoubleOrNull()
    val canSave = name.isNotBlank() && caloriesValue != null &&
        proteinValue != null && fatValue != null && carbsValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить запись") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = calories,
                    onValueChange = { calories = it },
                    label = { Text("Калории, ккал") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = protein,
                        onValueChange = { protein = it },
                        label = { Text("Б") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fat,
                        onValueChange = { fat = it },
                        label = { Text("Ж") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = carbs,
                        onValueChange = { carbs = it },
                        label = { Text("У") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    if (caloriesValue != null && proteinValue != null &&
                        fatValue != null && carbsValue != null
                    ) {
                        onSave(
                            meal.copy(
                                name = name.trim(),
                                calories = caloriesValue,
                                protein = proteinValue,
                                fat = fatValue,
                                carbs = carbsValue
                            )
                        )
                    }
                }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ---------- Диалог веса ----------

@Composable
private fun WeightDialog(onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val parsed = input.trim().replace(",", ".").toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Записать вес") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Вес, кг") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    parsed?.let(onConfirm)
                    onDismiss()
                }
            ) { Text("Сохранить") }
            },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ---------- Заглушка пустого дня ----------

@Composable
private fun EmptyState() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Restaurant,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Записей пока нет",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Нажмите «+» внизу, чтобы сфотографировать первый приём пищи 🍽️",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
