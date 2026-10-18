package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nutrilens.app.ui.theme.LocalReducedMotion
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.data.AnalysisJobEntity
import com.nutrilens.app.data.AnalysisJobRepository
import com.nutrilens.app.data.FavoriteEntity
import com.nutrilens.app.data.FavoriteRepository
import com.nutrilens.app.data.MealEntity
import com.nutrilens.app.data.MealItemEntity
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.MealWithImages
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.RefinementJobEntity
import com.nutrilens.app.data.RefinementJobRepository
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
import java.time.format.DateTimeFormatter
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
    private val analysisJobRepository = AnalysisJobRepository(database.analysisJobDao())
    private val refinementJobRepository = RefinementJobRepository(database.refinementJobDao())

    init {
        // Виджет в лаунчере обновляется только по явному запросу — освежаем при открытии.
        com.nutrilens.app.widget.WidgetUpdater.refresh(application)
    }

    val favorites: StateFlow<List<FavoriteEntity>> = favoriteRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Блюда, которые ИИ сейчас анализирует в фоне (QUEUED/RUNNING). */
    val activeJobs: StateFlow<List<AnalysisJobEntity>> = analysisJobRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Уточнения («Поправить») в работе — индикатор прямо на карточке блюда. */
    val refiningJobs: StateFlow<List<RefinementJobEntity>> = refinementJobRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val failedRefinements: StateFlow<List<RefinementJobEntity>> =
        refinementJobRepository.observeFailed()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Повтор неудавшегося уточнения: в очередь и заново в WorkManager. */
    fun retryRefinement(job: RefinementJobEntity) {
        viewModelScope.launch {
            refinementJobRepository.requeueForRetry(job.id)
            AnalysisScheduler.retryRefinement(getApplication(), job.id)
        }
    }

    /** Скрыть неудавшееся уточнение из списка (без повтора). */
    fun dismissRefinement(job: RefinementJobEntity) {
        viewModelScope.launch { refinementJobRepository.deleteJob(job.id) }
    }

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
            com.nutrilens.app.widget.WidgetUpdater.refresh(getApplication())
            com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(getApplication())
        }
    }

    /** Восстановление после удаления (кнопка «Вернуть» в снекбаре). */
    fun restoreMeal(meal: MealWithImages, items: List<MealItemEntity>) {
        viewModelScope.launch {
            mealRepository.addMeal(meal.meal, meal.images, items)
            com.nutrilens.app.widget.WidgetUpdater.refresh(getApplication())
            com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(getApplication())
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
            com.nutrilens.app.widget.WidgetUpdater.refresh(getApplication())
            com.nutrilens.app.leaderboard.LeaderboardSync.pushAsync(getApplication())
        }
    }

    /** Уточнение уже добавленного блюда: ИИ пересчитает его с учётом правки. */
    suspend fun correctMeal(meal: MealEntity, correction: String) {
        AnalysisScheduler.scheduleRefinement(getApplication(), meal.id, correction)
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
    onOpenLeaderboard: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val meals by viewModel.meals.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val activeJobs by viewModel.activeJobs.collectAsStateWithLifecycle()
    val refiningJobs by viewModel.refiningJobs.collectAsStateWithLifecycle()
    val failedRefinements by viewModel.failedRefinements.collectAsStateWithLifecycle()
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

    var detailsMealId by remember { mutableStateOf<String?>(null) }
    var detailItems by remember { mutableStateOf<List<MealItemEntity>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<MealWithImages?>(null) }
    var editTarget by remember { mutableStateOf<MealWithImages?>(null) }
    var correctTarget by remember { mutableStateOf<MealWithImages?>(null) }
    var showWeightDialog by remember { mutableStateOf(false) }

    fun isFavorite(meal: MealEntity): Boolean =
        favorites.any { it.name == meal.name && kotlin.math.abs(it.calories - meal.calories) < 0.5 }

    // Диалог деталей держит блюдо ЖИВЫМ из потока: когда фоновое уточнение обновит
    // запись в БД, цифры в открытом диалоге обновятся сами, без переоткрытия.
    val detailsMeal = detailsMealId?.let { id -> meals.firstOrNull { it.meal.id == id } }
    val refiningMealIds = refiningJobs.map { it.mealId }.toSet()
    LaunchedEffect(detailsMeal?.meal?.id, detailsMeal?.meal?.calories) {
        detailsMeal?.let { detailItems = viewModel.itemsForMeal(it.meal.id) }
    }

    // Первое появление экрана — «лесенкой»; при прокрутке назад карточки
    // уже не анимируются заново.
    var introDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(900)
        introDone = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Верх страницы — только дата: без логотипа, названия и приветствия.
            Box(Modifier.introIn(0, introDone)) {
            DaySelectorRow(
                selected = selectedDate,
                streak = streak,
                lastWeight = lastWeight,
                daysSince = daysSince,
                onShift = viewModel::shiftDay,
                onToday = viewModel::goToday,
                onWeighIn = { showWeightDialog = true }
            )
            }
        }
        item {
            Box(Modifier.introIn(1, introDone)) {
                CaloriesHero(meals = meals, settings = settings, onLeaderboard = onOpenLeaderboard)
            }
        }
        if (activeJobs.isNotEmpty()) {
            item {
                ProcessingCard(
                    job = activeJobs.first(),
                    extraCount = activeJobs.size - 1
                )
            }
        }
        if (refiningJobs.isNotEmpty()) {
            item { RefinementProcessingCard(job = refiningJobs.first(), extraCount = refiningJobs.size - 1) }
        }
        if (failedRefinements.isNotEmpty()) {
            item {
                FailedRefinementsSection(
                    jobs = failedRefinements,
                    onRetry = viewModel::retryRefinement,
                    onDismiss = viewModel::dismissRefinement
                )
            }
        }
        item {
            Box(Modifier.introIn(2, introDone)) {
                MacrosRow(meals = meals, settings = settings, lastWeight = lastWeight)
            }
        }
        item {
            Box(Modifier.introIn(3, introDone)) {
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

        if (meals.isEmpty()) {
            item { EmptyState() }
        } else {
            item { MealsHeader(count = meals.size) }
            items(meals, key = { it.meal.id }) { meal ->
                MealCard(
                    meal = meal,
                    showFavorite = !isFavorite(meal.meal),
                    refining = meal.meal.id in refiningMealIds,
                    onFavorite = { viewModel.addFavorite(meal.meal) },
                    onClick = {
                        detailsMealId = meal.meal.id
                        detailItems = emptyList()
                    },
                    onDelete = { deleteTarget = meal },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    detailsMeal?.let { meal ->
        MealDetailsDialog(
            meal = meal,
            items = detailItems,
            refining = meal.meal.id in refiningMealIds,
            onClose = { detailsMealId = null },
            onEdit = {
                editTarget = meal
                detailsMealId = null
            },
            onCorrect = {
                correctTarget = meal
                detailsMealId = null
            }
        )
    }

    correctTarget?.let { target ->
        MealCorrectionDialog(
            meal = target.meal,
            onSubmit = { correction ->
                correctTarget = null
                scope.launch {
                    try {
                        viewModel.correctMeal(target.meal, correction)
                        snackbarHostState.showSnackbar("Уточнение отправлено — придёт уведомление")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(
                            e.message ?: "Не удалось отправить уточнение"
                        )
                    }
                }
            },
            onDismiss = { correctTarget = null }
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
    lastWeight: Double?,
    daysSince: Int?,
    onShift: (Long) -> Unit,
    onToday: () -> Unit,
    onWeighIn: () -> Unit
) {
    val isToday = selected == LocalDate.now()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                TinyLabel(
                    text = selected.format(
                        DateTimeFormatter.ofPattern("EEEE", java.util.Locale("ru", "RU"))
                    )
                )
                Spacer(Modifier.height(2.dp))
                // Смена дня — заголовок «уезжает» в сторону листания.
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(Motion.Medium, easing = Motion.Emphasized)) { dir * it / 3 } +
                            fadeIn(tween(Motion.Medium))) togetherWith
                            (slideOutHorizontally(tween(Motion.Short, easing = Motion.Exit)) { -dir * it / 3 } +
                                fadeOut(tween(Motion.Short)))
                    },
                    label = "dayTitle"
                ) { date ->
                    Text(
                        text = dateLabelRu(date).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            DayPill(selected = selected, onShift = onShift)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (streak > 0) {
                HeaderChip(
                    icon = Icons.Rounded.LocalFireDepartment,
                    iconTint = NutriTheme.colors.warnTo,
                    text = "$streak ${daysWord(streak)}",
                    contentDescription = "Серия: $streak ${daysWord(streak)} подряд"
                )
            }
            AnimatedVisibility(
                visible = !isToday,
                enter = fadeIn(tween(Motion.Medium)) + scaleIn(Motion.press(), initialScale = 0.8f),
                exit = fadeOut(tween(Motion.Short)) + scaleOut(tween(Motion.Short), targetScale = 0.8f)
            ) {
                HeaderChip(
                    icon = Icons.Rounded.Today,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    text = "Сегодня",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onToday
                )
            }
            // Кнопка веса всегда на виду; мягко «дышит», если 7+ дней без записи.
            WeightChip(
                lastWeight = lastWeight,
                daysSince = daysSince,
                onClick = onWeighIn
            )
        }
    }
}

private fun daysWord(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
}

/** Компактный чип шапки: иконка + текст, 36dp высотой, нажимаемый по желанию. */
@Composable
private fun HeaderChip(
    icon: ImageVector,
    iconTint: Color,
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(999.dp)
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .padding(start = 10.dp, end = 14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1
            )
        }
    }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
    } else Modifier
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = shape,
            color = container,
            border = BorderStroke(1.dp, NutriTheme.colors.hairline),
            modifier = modifier
                .then(semanticsModifier)
                .pressScale(interaction, 0.94f)
                .softShadow(shape, 4),
            content = content
        )
    } else {
        Surface(
            shape = shape,
            color = container,
            border = BorderStroke(1.dp, NutriTheme.colors.hairline),
            modifier = modifier
                .then(semanticsModifier)
                .softShadow(shape, 4),
            content = content
        )
    }
}

/** Переключатель дня: пилюля ← дата →, дата меняется с вертикальным «перелистыванием». */
@Composable
private fun DayPill(selected: LocalDate, onShift: (Long) -> Unit) {
    val pillShape = RoundedCornerShape(999.dp)
    Surface(
        shape = pillShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, NutriTheme.colors.hairline),
        modifier = Modifier.softShadow(pillShape, 6)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(3.dp)) {
            DayArrow(onClick = { onShift(-1) }, forward = false)
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInVertically(tween(Motion.Medium, easing = Motion.Emphasized)) { dir * it } +
                        fadeIn(tween(Motion.Medium))) togetherWith
                        (slideOutVertically(tween(Motion.Short, easing = Motion.Exit)) { -dir * it } +
                            fadeOut(tween(Motion.Short)))
                },
                label = "dayShort"
            ) { date ->
                Text(
                    text = dayShort(date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.width(58.dp),
                    textAlign = TextAlign.Center
                )
            }
            DayArrow(onClick = { onShift(1) }, forward = true)
        }
    }
}

/** Короткая дата дня: «24 авг». */
private fun dayShort(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("d MMM", java.util.Locale("ru", "RU"))).trimEnd('.')

/** Круглая кнопка дня: 44dp, тональная, упругое нажатие. */
@Composable
private fun DayArrow(onClick: () -> Unit, forward: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        interactionSource = interaction,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(44.dp)
            .pressScale(interaction, 0.86f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (forward) Icons.AutoMirrored.Rounded.KeyboardArrowRight
                else Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = if (forward) "Следующий день" else "Предыдущий день",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Кнопка веса (всегда на виду): мягко «дышит» тёплым тоном, когда 7+ дней без записи. */
@Composable
private fun WeightChip(
    lastWeight: Double?,
    daysSince: Int?,
    onClick: () -> Unit
) {
    val overdue = daysSince != null && daysSince >= 7
    val needsWeighIn = daysSince == null || overdue
    val label = if (daysSince != null && lastWeight != null) {
        "%.1f".format(lastWeight) + " кг"
    } else {
        "Взвеситься"
    }
    if (needsWeighIn && !LocalReducedMotion.current) {
        val t = rememberInfiniteTransition(label = "wgtPulse")
        val glow by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = Motion.Standard), RepeatMode.Reverse),
            label = "wgtGlow"
        )
        Box(
            Modifier.graphicsLayer {
                val s = 1f + 0.03f * glow
                scaleX = s
                scaleY = s
            }
        ) {
            HeaderChip(
                icon = Icons.Rounded.MonitorWeight,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                text = label,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                contentDescription = "Записать вес",
                onClick = onClick
            )
        }
    } else {
        HeaderChip(
            icon = Icons.Rounded.MonitorWeight,
            iconTint = if (needsWeighIn) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.secondary,
            text = label,
            container = if (needsWeighIn) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surface,
            textColor = if (needsWeighIn) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurface,
            contentDescription = if (lastWeight != null) "Вес $label, записать новый" else "Записать вес",
            onClick = onClick
        )
    }
}

// ---------- Герой-карточка: калорийное кольцо ----------

// ---------- Блюдо в обработке ----------

/**
 * Карточка на главной: ИИ сейчас анализирует фото в фоне (фото из очереди,
 * «сканирующая» лента и пульсирующая точка). Исчезает сама, когда воркер
 * записал результат в дневник.
 */
@Composable
private fun ProcessingCard(job: AnalysisJobEntity, extraCount: Int) {
    val thumbPath = firstPhotoThumb(job.photoPaths)
    val pulse = rememberInfiniteTransition(label = "processing")
    val dotScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse),
        label = "dotScale"
    )
    val sweepY by pulse.animateFloat(
        initialValue = -18f,
        targetValue = 58f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweepY"
    )
    val title = job.note.ifBlank { "Анализ фотографии" }
    val status = if (job.status == "RUNNING") "в обработке" else "в очереди"
    FreshCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (thumbPath != null) {
                    AsyncImage(
                        model = File(thumbPath),
                        contentDescription = "Фото блюда",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.RestaurantMenu,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).size(24.dp)
                    )
                }
                // «Лента сканирования» — бегущая полоса поверх фото.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .graphicsLayer { translationY = sweepY }
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, NutriTheme.colors.brandFrom.copy(alpha = 0.5f), Color.Transparent)
                            )
                        )
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "ИИ считает калории и БЖУ — придёт уведомление",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    Modifier
                        .size(9.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                        }
                        .shadow(4.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (extraCount > 0) "$status · ещё $extraCount" else status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Живая индикация уточнения («Поправить»): пока задача в очереди/в работе,
 * на дашборде видно, что правка обрабатывается, — раньше не было никакого статуса.
 */
@Composable
private fun RefinementProcessingCard(job: RefinementJobEntity, extraCount: Int) {
    val pulse = rememberInfiniteTransition(label = "refining")
    val dotScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse),
        label = "dotScale"
    )
    val status = if (job.status == "RUNNING") "в обработке" else "в очереди"
    FreshCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Уточняем блюдо",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    job.correction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    Modifier
                        .size(9.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                        }
                        .shadow(4.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (extraCount > 0) "$status · ещё $extraCount" else status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Неудавшиеся уточнения: причина, повтор и скрытие (как у сбоев анализа на экране добавления). */
@Composable
private fun FailedRefinementsSection(
    jobs: List<RefinementJobEntity>,
    onRetry: (RefinementJobEntity) -> Unit,
    onDismiss: (RefinementJobEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        jobs.forEach { job ->
            FreshCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(
                        icon = Icons.Rounded.ErrorOutline,
                        tint = MaterialTheme.colorScheme.error,
                        size = 36
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Уточнение не удалось",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            job.error ?: "Попробуйте ещё раз",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onRetry(job) }) { Text("Повторить") }
                    TextButton(onClick = { onDismiss(job) }) { Text("Скрыть") }
                }
            }
        }
    }
}

/** Миниатюра первой фотографии из photoPaths (JSON-массив строк "full|thumb"). */
private fun firstPhotoThumb(photoPathsJson: String): String? {
    return try {
        val array = JSONArray(photoPathsJson)
        val element = array.opt(0) ?: return null
        when (element) {
            is JSONObject -> {
                val thumb = element.optString("thumb")
                val full = element.optString("full")
                thumb.ifBlank { full.ifBlank { null } }
            }
            else -> {
                val parts = element.toString().split("|", limit = 2)
                parts.getOrNull(1)?.let { if (it.isNotBlank()) it else parts[0] }
            }
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun CaloriesHero(meals: List<MealWithImages>, settings: SettingsEntity, onLeaderboard: () -> Unit = {}) {
    val eaten = meals.sumOf { it.meal.calories }
    val goal = settings.dailyGoal
    val isOver = goal > 0 && eaten > goal
    val c = NutriTheme.colors
    val reduced = LocalReducedMotion.current

    // Кольцо «доливается» мягкой кривой, цифры в центре бегут вместе с ним.
    val fraction by animateFloatAsState(
        targetValue = if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(Motion.ExtraLong + 300, easing = Motion.Emphasized),
        label = "ring"
    )
    val eatenShown by animateFloatAsState(
        targetValue = eaten.toFloat(),
        animationSpec = tween(Motion.ExtraLong, easing = Motion.Emphasized),
        label = "eatenCount"
    )
    // Тап по карточке переключает центр кольца: съедено ↔ остаток/перебор.
    var showRemaining by remember { mutableStateOf(false) }

    val done = fraction >= 0.999f && !isOver
    val breathe = if (done && !reduced) {
        val infinite = rememberInfiniteTransition(label = "breathe")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = Motion.Standard), RepeatMode.Reverse),
            label = "arcGlow"
        ).value
    } else 0f

    val arcFrom = if (isOver) c.warnFrom else c.brandFrom
    val arcTo = if (isOver) c.warnTo else c.brandTo
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            showRemaining = !showRemaining
        },
        interactionSource = interaction,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, c.hairline),
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.985f)
            .softShadow(shape, 18)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Цветная подсветка за кольцом: карточка «светится» цветом прогресса.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(arcFrom.copy(alpha = if (c.isDark) 0.14f else 0.09f), Color.Transparent)
                        )
                    )
            )
            // Кубок — к лидерборду, в углу карточки (иконка вместо эмодзи, 48dp зона).
            Surface(
                onClick = onLeaderboard,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    IconBadge(
                        icon = Icons.Rounded.EmojiEvents,
                        tint = c.warnTo,
                        size = 36,
                        contentDescription = "Лидерборд"
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(196.dp)) {
                        val stroke = 16.dp.toPx()
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
                            // Градиент по ходу дуги: светлее в начале, насыщеннее к концу.
                            rotate(-90f) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        0f to arcFrom,
                                        fraction.coerceIn(0.01f, 0.99f) to arcTo,
                                        1f to arcTo,
                                        center = center
                                    ),
                                    startAngle = 0f,
                                    sweepAngle = 360f * fraction,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                                )
                                // Круглый «колпачок» начала дуги в цвет старта градиента.
                                drawCircle(arcFrom, radius = stroke / 2f, center = Offset(center.x + radius, center.y))
                            }
                            // Светящаяся «головка» на конце дуги.
                            val angle = Math.toRadians((360.0 * fraction) - 90.0)
                            val tip = Offset(
                                center.x + radius * kotlin.math.cos(angle).toFloat(),
                                center.y + radius * kotlin.math.sin(angle).toFloat()
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(arcTo.copy(alpha = 0.45f + 0.3f * breathe), Color.Transparent),
                                    center = tip,
                                    radius = stroke * (1.4f + 0.4f * breathe)
                                ),
                                radius = stroke * (1.4f + 0.4f * breathe),
                                center = tip
                            )
                            drawCircle(Color.White.copy(alpha = 0.9f), radius = stroke * 0.2f, center = tip)
                        }
                    }
                    AnimatedContent(
                        targetState = showRemaining,
                        transitionSpec = {
                            (fadeIn(tween(Motion.Medium)) + scaleIn(tween(Motion.Medium, easing = Motion.Emphasized), initialScale = 0.9f)) togetherWith
                                (fadeOut(tween(Motion.Short)) + scaleOut(tween(Motion.Short), targetScale = 1.05f))
                        },
                        label = "heroCenter"
                    ) { remaining ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (remaining) {
                                val delta = (goal - eatenShown).roundToInt()
                                DisplayNumber(
                                    text = "${kotlin.math.abs(delta)}",
                                    size = 46,
                                    color = if (delta < 0) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (isOver) "перебор, ккал" else "осталось, ккал",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                DisplayNumber(
                                    text = "${eatenShown.roundToInt()}",
                                    size = 46,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "из ${goal.roundToInt()} ккал",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isOver) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (isOver) {
                            "Перебор на ${(eaten - goal).roundToInt()} ккал — ничего страшного"
                        } else if (done) {
                            "Цель дня достигнута"
                        } else {
                            "Осталось ${(goal - eaten).roundToInt()} ккал · ${(fraction * 100).roundToInt()}%"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isOver) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

// ---------- Макросы ----------

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
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MacroCard("Белки", protein, goals.protein, palette.protein, Modifier.weight(1f))
        MacroCard("Жиры", fat, goals.fat, palette.fat, Modifier.weight(1f))
        MacroCard("Углеводы", carbs, goals.carbs, palette.carbs, Modifier.weight(1f))
    }
}

/** Мини-кольцо макроса: цифры «бегут», дуга доливается мягкой кривой. */
@Composable
private fun MacroCard(
    label: String,
    eaten: Double,
    goal: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val fraction by animateFloatAsState(
        if (goal > 0) (eaten / goal).toFloat().coerceIn(0f, 1f) else 0f,
        tween(Motion.ExtraLong, easing = Motion.Emphasized),
        label = "macroFraction"
    )
    val shown by animateFloatAsState(
        eaten.toFloat(), tween(Motion.ExtraLong, easing = Motion.Emphasized), label = "macroCount"
    )
    val track = color.copy(alpha = if (NutriTheme.colors.isDark) 0.2f else 0.14f)
    FreshCard(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: ${eaten.roundToInt()} из ${goal.roundToInt()} грамм"
        },
        radius = 24
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(58.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 6.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    if (fraction > 0f) {
                        drawArc(
                            color = color, startAngle = -90f, sweepAngle = 360f * fraction,
                            useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Text(
                    text = "${shown.roundToInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "из ${goal.roundToInt()} г",
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
    val c = NutriTheme.colors
    val normMl = waterNormaMl(lastWeight, workoutDone)
    val fraction by animateFloatAsState(
        if (normMl > 0) (waterMl.toFloat() / normMl).coerceIn(0f, 1f) else 0f,
        Motion.settle(),
        label = "waterFill"
    )
    val shownMl by animateFloatAsState(
        waterMl.toFloat(), tween(Motion.Long, easing = Motion.Emphasized), label = "waterCount"
    )
    FreshCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon = Icons.Rounded.WaterDrop, tint = c.water, size = 44)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${shownMl.roundToInt()}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = " / $normMl мл",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
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
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(c.waterGradient)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            val waterInteraction = remember { MutableInteractionSource() }
            val pillShape = RoundedCornerShape(999.dp)
            Surface(
                onClick = onAddWater,
                interactionSource = waterInteraction,
                shape = pillShape,
                color = Color.Transparent,
                modifier = Modifier
                    .pressScale(waterInteraction, 0.9f)
                    .shadow(
                        10.dp, pillShape,
                        ambientColor = c.water.copy(alpha = 0.45f),
                        spotColor = c.water.copy(alpha = 0.45f)
                    )
                    .semantics { contentDescription = "Добавить 250 мл воды" }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(c.waterGradient, pillShape)
                        .height(44.dp)
                        .padding(horizontal = 14.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "250",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
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

    FreshCard(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = Icons.Rounded.AutoAwesome,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 32
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Ассистент",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(12.dp))
            insights.forEachIndexed { i, insight ->
                if (i > 0) {
                    Box(
                        Modifier
                            .padding(start = 46.dp, top = 10.dp, bottom = 10.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(NutriTheme.colors.hairline)
                    )
                }
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Text(text = insight.emoji, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
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
                Spacer(Modifier.height(14.dp))
                val (focusTitle, focusText) = focus.split("\n", limit = 2)
                    .let { it[0] to it.getOrElse(1) { "" } }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Rounded.TrackChanges,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = focusTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (focusText.isNotBlank()) {
                                Text(
                                    text = focusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Список приёмов пищи ----------

@Composable
private fun MealsHeader(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Text(
            text = "Приёмы пищи",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MealCard(
    meal: MealWithImages,
    showFavorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    refining: Boolean = false
) {
    val palette = macroPalette()
    FreshCard(modifier = modifier.fillMaxWidth(), radius = 24, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MealThumbnail(meal)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meal.meal.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meal.meal.time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    HealthBadge(score = healthScoreOf(meal.meal))
                }
                Spacer(Modifier.height(6.dp))
                if (refining) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Уточняем правку…",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MacroDot("Б", meal.meal.protein, palette.protein)
                        Spacer(Modifier.width(10.dp))
                        MacroDot("Ж", meal.meal.fat, palette.fat)
                        Spacer(Modifier.width(10.dp))
                        MacroDot("У", meal.meal.carbs, palette.carbs)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${meal.meal.calories.roundToInt()}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "ккал",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                if (showFavorite) {
                    IconButton(onClick = onFavorite) {
                        Icon(
                            imageVector = Icons.Rounded.StarOutline,
                            contentDescription = "В избранное",
                            tint = NutriTheme.colors.warnTo,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** Макрос в строке блюда: цветная точка + «Б 24». Цвет — не единственный носитель смысла. */
@Composable
private fun MacroDot(letter: String, grams: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$letter ${grams.roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MealThumbnail(meal: MealWithImages) {
    val photo = meal.images.firstOrNull { it.kind == "THUMB" }
        ?: meal.images.firstOrNull { it.kind == "FULL" }
    val shape = RoundedCornerShape(18.dp)
    if (photo != null) {
        AsyncImage(
            model = File(photo.path),
            contentDescription = meal.meal.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(62.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    } else {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.RestaurantMenu,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * Оценка полезности блюда для пользователя (0–100). Если ИИ вернул
 * health_score — его и показываем; старые записи без неё получают
 * справедливый детерминированный прогноз по БЖУ (не хуже случайного нуля).
 */
private fun healthScoreOf(meal: MealEntity): Int {
    meal.healthScore?.let { return it.coerceIn(0, 100) }
    val total = meal.calories.takeIf { it > 0 } ?: return 50
    val proteinShare = (meal.protein * 4) / total
    val fatShare = (meal.fat * 9) / total
    val carbShare = (meal.carbs * 4) / total
    var score = 55.0
    when {
        proteinShare in 0.18..0.42 -> score += 15
        proteinShare in 0.10..0.18 -> score += 5
        proteinShare < 0.10 || proteinShare > 0.50 -> score -= 10
    }
    when {
        fatShare > 0.45 -> score -= 20
        fatShare > 0.35 -> score -= 8
        fatShare < 0.25 -> score += 5
    }
    if (carbShare > 0.70) score -= 8
    return score.roundToInt().coerceIn(5, 95)
}

@Composable
private fun healthBandColor(score: Int): Color = when {
    score >= 75 -> NutriTheme.colors.success
    score >= 50 -> NutriTheme.colors.waterDeep
    score >= 25 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun healthBandLabel(score: Int): String = when {
    score >= 75 -> "Сбалансированный состав"
    score >= 50 -> "Нейтральный состав"
    score >= 25 -> "Плотное блюдо"
    else -> "Тяжёлое блюдо"
}

/** Компактная оценка состава: тонированная пилюля «● 82». */
@Composable
private fun HealthBadge(score: Int) {
    val color = healthBandColor(score)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = if (NutriTheme.colors.isDark) 0.2f else 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Оценка состава $score из 100"
            }
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$score",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

// ---------- Детали блюда (нижний лист) ----------

/** Оценка состава: счёт 0–100 с цветом зоны, подписью и нейтральной заметкой ИИ. */
@Composable
private fun MealHealthSection(score: Int, note: String?) {
    val color = healthBandColor(score)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = color.copy(alpha = if (NutriTheme.colors.isDark) 0.16f else 0.09f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$score",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = color
                )
                Text(
                    text = "/100",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = healthBandLabel(score),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Детали блюда — нижний лист вместо диалога: крупное фото, КБЖУ плашками,
 * продукты, мысли ИИ и действия внизу. Лист тянется жестом и закрывается свайпом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealDetailsDialog(
    meal: MealWithImages,
    items: List<MealItemEntity>,
    refining: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onCorrect: () -> Unit
) {
    val m = meal.meal
    val palette = macroPalette()
    // Уточнение доступно только для сегодняшних и вчерашних приёмов пищи:
    // позже правки перестают быть точными (а фото могут быть уже не актуальны).
    val canCorrect = (m.date == LocalDate.now().toString() ||
        m.date == LocalDate.now().minusDays(1).toString()) && !refining
    val photo = meal.images.firstOrNull { it.kind == "FULL" }
        ?: meal.images.firstOrNull { it.kind == "THUMB" }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            if (photo != null) {
                AsyncImage(
                    model = File(photo.path),
                    contentDescription = m.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = m.name,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = m.time,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NutrientTile("ккал", m.calories, MaterialTheme.colorScheme.primary, Modifier.weight(1.2f))
                NutrientTile("белки", m.protein, palette.protein, Modifier.weight(1f))
                NutrientTile("жиры", m.fat, palette.fat, Modifier.weight(1f))
                NutrientTile("углев.", m.carbs, palette.carbs, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            MealHealthSection(score = healthScoreOf(m), note = m.healthNote)
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionTitle("Продукты")
                items.forEachIndexed { i, item ->
                    if (i > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .height(1.dp)
                                .background(NutriTheme.colors.hairline)
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (item.breakdown.isNotBlank()) {
                                Text(
                                    text = item.breakdown,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${item.calories.roundToInt()} ккал",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${item.weightG.roundToInt()} г",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (m.aiThoughts.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                SectionTitle("Мысли ИИ")
                Text(
                    text = m.aiThoughts,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SoftButton(text = "Изменить", onClick = onEdit, modifier = Modifier.weight(1f))
                when {
                    canCorrect -> GlowButton(
                        text = "Поправить",
                        onClick = onCorrect,
                        icon = Icons.Rounded.AutoFixHigh,
                        height = 52,
                        modifier = Modifier.weight(1f)
                    )
                    refining -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Уточняем…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Плашка нутриента в листе деталей: крупное число, подпись, цветной акцент. */
@Composable
private fun NutrientTile(label: String, value: Double, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(color.copy(alpha = if (NutriTheme.colors.isDark) 0.16f else 0.09f))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${value.roundToInt()}",
            style = MaterialTheme.typography.titleLarge,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- Диалог уточнения блюда («Поправить») ----------

/**
 * Пользователь пишет правку («вес не 170 г, а 125 г») — ИИ пересматривает
 * фото/описание блюда и вносит правки в уже сохранённую запись.
 */
@Composable
private fun MealCorrectionDialog(
    meal: MealEntity,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поправить блюдо") },
        text = {
            Column {
                Text(
                    text = "«${meal.name}» — напишите, что исправить. " +
                        "ИИ пересмотрит фото и пересчитает КБЖУ.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Например: вес не 170 г, а 125 г") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("Отправить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
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
    val reduced = LocalReducedMotion.current
    val floatY = if (!reduced) {
        val t = rememberInfiniteTransition(label = "emptyFloat")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = Motion.Standard), RepeatMode.Reverse),
            label = "emptyY"
        )
    } else null
    FreshCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .graphicsLayer { translationY = -6.dp.toPx() * (floatY?.value ?: 0f) }
                    .clip(RoundedCornerShape(26.dp))
                    .background(NutriTheme.colors.brandGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = NutriTheme.colors.onBrand
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Записей пока нет",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Нажмите «+» внизу и сфотографируйте блюдо — ИИ посчитает калории и БЖУ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Ступенчатое появление только во время первого показа экрана. */
@Composable
private fun Modifier.introIn(index: Int, introDone: Boolean): Modifier =
    if (introDone) this else this.staggeredIn(index)
