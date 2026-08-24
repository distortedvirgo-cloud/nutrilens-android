package com.nutrilens.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nutrilens.app.ai.GeminiApi
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.ai.MealAnalysisResult
import com.nutrilens.app.ai.buildRecentMealsContext
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.data.FavoriteEntity
import com.nutrilens.app.data.FavoriteRepository
import com.nutrilens.app.data.MealEntity
import com.nutrilens.app.data.MealImageEntity
import com.nutrilens.app.data.MealItemEntity
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

// ---------- Состояние экрана "Добавить еду" ----------

sealed interface AddMealPhase {
    data object Idle : AddMealPhase
    data object Sent : AddMealPhase
}

class AddMealViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val mealRepository = MealRepository(
        database.mealDao(),
        database.waterDao(),
        database.weightDao(),
        database.workoutDao()
    )
    private val settingsRepository = SettingsRepository(database.settingsDao())
    private val favoriteRepository = FavoriteRepository(database.favoriteDao())

    val favorites: StateFlow<List<FavoriteEntity>> = favoriteRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    private val _photos = MutableStateFlow<List<Uri>>(emptyList())
    val photos: StateFlow<List<Uri>> = _photos.asStateFlow()

    private val _phase = MutableStateFlow<AddMealPhase>(AddMealPhase.Idle)
    val phase: StateFlow<AddMealPhase> = _phase.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // Редактируемые поля результата анализа перед сохранением.
    private val _resultName = MutableStateFlow("")
    val resultName: StateFlow<String> = _resultName.asStateFlow()
    private val _resultCalories = MutableStateFlow("")
    val resultCalories: StateFlow<String> = _resultCalories.asStateFlow()
    private val _resultProtein = MutableStateFlow("")
    val resultProtein: StateFlow<String> = _resultProtein.asStateFlow()
    private val _resultFat = MutableStateFlow("")
    val resultFat: StateFlow<String> = _resultFat.asStateFlow()
    private val _resultCarbs = MutableStateFlow("")
    val resultCarbs: StateFlow<String> = _resultCarbs.asStateFlow()


    fun setNote(value: String) {
        _note.value = value
    }

    fun addPhoto(context: Context, uri: Uri) {
        if (_photos.value.size >= 10) return
        viewModelScope.launch(Dispatchers.IO) {
            val local = try {
                if (uri.scheme == "file") uri else copyToLocal(context, uri)
            } catch (e: Exception) {
                _error.value = "Не удалось открыть файл: $uri"
                return@launch
            }
            if (local !in _photos.value && _photos.value.size < 10) {
                _photos.value = _photos.value + local
            }
        }
    }

    /**
     * Копирует выбранное фото в filesDir сразу при выборе: разрешения picker-URI
     * могут истечь к моменту анализа, а локальный файл читается всегда. Кэш не
     * используем — система может его вычистить до запуска фонового анализа.
     */
    private fun copyToLocal(context: Context, uri: Uri): Uri {
        val dir = File(context.filesDir, "photos_picked").apply { mkdirs() }
        val dest = File(dir, "picked_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
                check(dest.length() > 0) { "пустой файл: $uri" }
            }
        } ?: throw IllegalArgumentException("нет доступа к $uri")
        return Uri.fromFile(dest)
    }

    fun removePhoto(uri: Uri) {
        _photos.value = _photos.value - uri
    }

    fun clearError() {
        _error.value = null
    }

    /** Фоновый анализ через AnalysisScheduler. Экран показывает состояние «отправлено». */
    fun enqueueBackground(context: Context, onDone: () -> Unit) {
        if (_photos.value.isEmpty()) return
        viewModelScope.launch {
            _error.value = null
            try {
                AnalysisScheduler.enqueueBackground(context, _note.value, _photos.value)
                _messages.emit("✅ Анализ запущен в фоне — придёт уведомление")
                _phase.value = AddMealPhase.Sent
            } catch (e: Exception) {
                _phase.value = AddMealPhase.Idle
                _error.value = e.message ?: "Не удалось запустить фоновый анализ"
            }
        }
    }

    /**
     * Возврат к чистой форме после отправки: навигация сохраняет нашу ViewModel
     * (saveState/restoreState), поэтому сбрасываем и фазу, и фото, и заметку —
     * следующий вход на экран всегда начинается с чистого листа.
     */
    fun resetAfterSent() {
        if (_phase.value is AddMealPhase.Sent) {
            _phase.value = AddMealPhase.Idle
        }
        _photos.value = emptyList()
        _note.value = ""
    }



    /** Быстрое добавление блюда из избранного — без фото и анализа. */
    fun addFromFavorite(favorite: FavoriteEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            _error.value = null
            try {
                val now = LocalDateTime.now()
                val settings = settingsRepository.get()
                val meal = MealEntity(
                    id = UUID.randomUUID().toString(),
                    date = now.toLocalDate().toString(),
                    time = now.toLocalTime().format(HH_MM),
                    name = favorite.name,
                    calories = favorite.calories,
                    protein = favorite.protein,
                    fat = favorite.fat,
                    carbs = favorite.carbs,
                    aiThoughts = "Добавлено из избранного",
                    reasoning = "",
                    confidenceScore = 10.0,
                    dailyGoalSnapshot = settings.dailyGoal,
                    createdAt = System.currentTimeMillis()
                )
                mealRepository.addMeal(meal, emptyList(), emptyList())
                _messages.emit("Сохранено")
                onDone()
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось сохранить"
            }
        }
    }


    companion object {
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * Контракт TakePicture, который также выдаёт camera-приложению права на запись
 * в FileProvider-Uri (грант флагами). Базовая реализация androidx.activity
 * флагов не добавляет (проверено по байткоду activity 1.9.3).
 */
private class GrantingTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
}

@Composable
fun AddMealScreen(
    onDone: () -> Unit,
    onGoSettings: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: AddMealViewModel = viewModel()
) {
    val context = LocalContext.current
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // Камера.
    var pendingCaptureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(GrantingTakePicture()) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success) uri?.let { viewModel.addPhoto(context, it) }
    }
    fun launchCamera() {
        val file = File(context.cacheDir, "camera/${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        file.createNewFile()
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        pendingCaptureUri = uri
        cameraLauncher.launch(uri)
    }

    // Галерея (максимум 10 фото суммарно, обрезка — в колбэке).
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        val remaining = (10 - viewModel.photos.value.size).coerceAtLeast(0)
        uris.take(remaining).forEach { viewModel.addPhoto(context, it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackPill(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Добавить еду 🍽️",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Сфотографируйте блюдо — ИИ посчитает калории и КБЖУ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

when (phase) {
            AddMealPhase.Sent -> Unit
            AddMealPhase.Idle -> {
                error?.let { ErrorBlock(message = it, onGoSettings = onGoSettings) }
                if (photos.isEmpty() && favorites.isNotEmpty()) {
                    FavoritesRow(favorites = favorites) { favorite ->
                        viewModel.addFromFavorite(favorite, onDone)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhotoSourceTile(
                        emoji = "📷",
                        label = "Камера",
                        modifier = Modifier.weight(1f),
                        onClick = ::launchCamera
                    )
                    PhotoSourceTile(
                        emoji = "🖼️",
                        label = "Галерея",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
                if (photos.isNotEmpty()) {
                    PhotoPreviewRow(photos = photos, onRemove = viewModel::removePhoto)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = viewModel::setNote,
                    label = { Text("Что ели? Например: борщ со сметаной") },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                GlowButton(
                    text = "🚀 Проанализировать",
                    onClick = { viewModel.enqueueBackground(context, onDone) },
                    enabled = photos.isNotEmpty()
                )
                if (photos.isNotEmpty()) {
                    Text(
                        text = "Работает в фоне: приложение можно закрыть — результат придёт уведомлением",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // Полноэкранный оверлей «Анализ запущен»: плавно растворяется и уводит
    // на главный экран (NavHost добавляет подъём+fade нового экрана).
    if (phase is AddMealPhase.Sent) {
        SentOverlay(onDone = {
            viewModel.resetAfterSent()
            onDone()
        })
    }
}
}

// ---------- Блоки фаз ----------

/** Избранное: тап по чипу сразу записывает блюдо в дневник. */
@Composable
private fun FavoritesRow(favorites: List<FavoriteEntity>, onPick: (FavoriteEntity) -> Unit) {
    Column {
        Text(
            text = "⭐ Избранное",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(favorites, key = { it.id }) { favorite ->
                Surface(
                    onClick = { onPick(favorite) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = favorite.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${favorite.calories.roundToInt()} ккал",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onGoSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        if (message == "Укажите ключ Gemini в настройках") {
            TextButton(onClick = onGoSettings) { Text("Перейти в настройки") }
        }
    }
}

/** Плашка источника фото как в вебе: пунктирная рамка, квадрат, сжатие. */
@Composable
private fun PhotoSourceTile(
    emoji: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "tilePress")
    val shape = RoundedCornerShape(26.dp)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .aspectRatio(1f)
            .scale(scale)
            .shadow(5.dp, shape, ambientColor = Color(0x1216241C), spotColor = Color(0x1216241C))
            .dashedBorder(1.5.dp, MaterialTheme.colorScheme.outlineVariant, 26.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Пунктирная рамка как border-dashed в вебе (PathEffect). */
private fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp): Modifier =
    this.drawWithContent {
        drawContent()
        drawRoundRect(
            color = color,
            style = Stroke(
                width = width.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            ),
            cornerRadius = CornerRadius(radius.toPx(), radius.toPx())
        )
    }

/** Полноэкранный оверлей «Анализ запущен». Всегда схлопывается: по таймеру,
 * по тапу в любом месте или системной кнопке «назад», затем уходит на главную. */
@Composable
private fun SentOverlay(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var dismissRequested by remember { mutableStateOf(false) }
    val checkScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 260f),
        label = "check"
    )
    val overlayInteraction = remember { MutableInteractionSource() }

    // Автозакрытие через 1.25 с; тап/«назад» закрывают мгновенно.
    LaunchedEffect(dismissRequested) {
        if (dismissRequested) {
            visible = false
        } else {
            kotlinx.coroutines.delay(1250)
            dismissRequested = true
        }
    }
    // После завершения exit-анимации — мягкий переход на главный экран.
    LaunchedEffect(visible) {
        if (!visible) {
            kotlinx.coroutines.delay(420)
            runCatching { onDone() }
        }
    }
    BackHandler(enabled = true) { dismissRequested = true }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(tween(360)) + scaleOut(targetScale = 0.97f, animationSpec = tween(360))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = { dismissRequested = true }
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            MaterialTheme.colorScheme.background
                        ),
                        startY = 0f,
                        endY = 1200f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Круглая галочка с spring-появлением (эластично, как в вебе).
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .scale(checkScale)
                        .shadow(
                            18.dp, CircleShape,
                            ambientColor = Color(0x6617C289), spotColor = Color(0x6617C289)
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF1BC289), Color(0xFF0A7A55)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "Анализ запущен",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "ИИ считает калории на вашем фото",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(26.dp))
                ScanFrame()
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "Можно закрыть приложение — результат придёт уведомлением",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Скелет анализа как в вебе: серые плашки + лента сканирования (2s, вверх-вниз). */
@Composable
private fun ScanFrame() {
    val t = rememberInfiniteTransition(label = "scan")
    val sweep by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "sweep"
    )
    val frameShape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(frameShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .offset(y = (sweep * 89).dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                .shadow(
                    10.dp, RoundedCornerShape(2.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
        )
    }
}

@Composable
private fun PhotoPreviewRow(photos: List<Uri>, onRemove: (Uri) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(photos, key = { it.toString() }) { uri ->
            val thumbShape = RoundedCornerShape(16.dp)
            Box {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(thumbShape)
                        .shadow(5.dp, thumbShape, ambientColor = Color(0x1216241C), spotColor = Color(0x1216241C))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thumbShape)
                )
                IconButton(
                    onClick = { onRemove(uri) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Убрать фото",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private const val FILE_PROVIDER_AUTHORITY = "com.nutrilens.app.fileprovider"