package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nutrilens.app.ui.theme.LocalReducedMotion
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.bg.ToolJobWorker
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.ToolJobEntity
import com.nutrilens.app.data.ToolJobRepository
import com.nutrilens.app.data.WaterRepository
import com.nutrilens.app.insights.waterNormaMl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

class WaterToolViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val mealRepository = MealRepository(
        database.mealDao(),
        database.waterDao(),
        database.weightDao(),
        database.workoutDao()
    )
    private val waterRepository = WaterRepository(database.waterDao())
    private val settingsRepository = SettingsRepository(database.settingsDao())
    private val jobRepository = ToolJobRepository(database.toolJobDao())

    private val today = LocalDate.now().toString()

    private val _waterMl = MutableStateFlow(0)
    val waterMl: StateFlow<Int> = _waterMl.asStateFlow()

    private val _normMl = MutableStateFlow(2000)
    val normMl: StateFlow<Int> = _normMl.asStateFlow()

    /** Активные задачи совета (QUEUED/RUNNING) — для индикатора «в фоне». */
    val activeJobs: StateFlow<List<ToolJobEntity>> = jobRepository
        .observeActive(ToolJobWorker.KIND_WATER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Неудавшиеся задачи совета — для карточки сбоя с повтором. */
    val failedJobs: StateFlow<List<ToolJobEntity>> = jobRepository
        .observeFailed(ToolJobWorker.KIND_WATER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Последний готовый совет — источник текста карточки совета. */
    val lastDone: StateFlow<ToolJobEntity?> = jobRepository
        .observeLastDone(ToolJobWorker.KIND_WATER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            _waterMl.value = mealRepository.getWaterMl(today)
            val weight = mealRepository.getLatestWeight()
            val workout = mealRepository.getWorkoutDone(today)
            _normMl.value = waterNormaMl(weight, workout)
        }
    }

    fun add(deltaMl: Int) {
        viewModelScope.launch {
            waterRepository.addWater(today, deltaMl)
            _waterMl.value = mealRepository.getWaterMl(today)
        }
    }

    /**
     * Совет считается фоновой задачей: экран замораживает вес в job.input и
     * ставит ToolJobWorker; результат приходит через lastDone.
     */
    fun loadAdvice() {
        if (activeJobs.value.isNotEmpty()) return
        viewModelScope.launch {
            val input = JSONObject().apply {
                mealRepository.getLatestWeight()?.let { put("weightKg", it) }
            }
            AnalysisScheduler.enqueueTool(getApplication(), ToolJobWorker.KIND_WATER, input.toString())
        }
    }

    /** Повтор неудавшейся задачи совета (кнопка «Повторить»). */
    fun retryTool(job: ToolJobEntity) {
        viewModelScope.launch {
            AnalysisScheduler.retryTool(getApplication(), job.id)
        }
    }

    /** Скрыть карточку сбоя. */
    fun dismiss(job: ToolJobEntity) {
        viewModelScope.launch {
            jobRepository.deleteJob(job.id)
        }
    }
}

@Composable
fun WaterToolScreen(onBack: () -> Unit, viewModel: WaterToolViewModel = viewModel()) {
    val waterMl by viewModel.waterMl.collectAsStateWithLifecycle()
    val normMl by viewModel.normMl.collectAsStateWithLifecycle()
    val activeJobs by viewModel.activeJobs.collectAsStateWithLifecycle()
    val failedJobs by viewModel.failedJobs.collectAsStateWithLifecycle()
    val lastDone by viewModel.lastDone.collectAsStateWithLifecycle()

    val progress = if (normMl > 0) (waterMl / normMl.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            title = "Вода",
            subtitle = "Трекер воды и персональный совет",
            onBack = onBack
        )

        FreshCard(Modifier.fillMaxWidth().staggeredIn(1)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                WaterGlass(progress = progress, waterMl = waterMl, normMl = normMl)
                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SoftButton(
                        text = "−250",
                        onClick = { viewModel.add(-250) },
                        enabled = waterMl > 0,
                        height = 50,
                        modifier = Modifier.weight(0.8f)
                    )
                    PillButton(
                        text = "+250 мл",
                        onClick = { viewModel.add(250) },
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GlowButton(
            text = "Совет от ИИ",
            icon = Icons.Rounded.AutoAwesome,
            onClick = viewModel::loadAdvice,
            enabled = activeJobs.isEmpty(),
            modifier = Modifier.fillMaxWidth().staggeredIn(2)
        )

        if (activeJobs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ToolJobProcessingCard("Вода")
        }

        failedJobs.firstOrNull()?.let { job ->
            Spacer(Modifier.height(12.dp))
            ToolJobErrorCard(
                title = "Вода",
                error = job.error ?: "Не удалось получить совет",
                onRetry = { viewModel.retryTool(job) },
                onDismiss = { viewModel.dismiss(job) }
            )
        }

        lastDone?.result?.takeIf { it.isNotBlank() }?.let { text ->
            Spacer(Modifier.height(14.dp))
            FreshCard(Modifier.fillMaxWidth()) {
                MarkdownText(text, Modifier.padding(16.dp))
            }
        }
    }
}

/**
 * «Стакан» воды: круг, заполненный жидкостью до текущей доли нормы.
 * Уровень поднимается пружиной, поверхность мягко колышется двумя волнами
 * в противофазе (при «Удалить анимации» — ровная поверхность).
 */
@Composable
private fun WaterGlass(progress: Float, waterMl: Int, normMl: Int) {
    val c = NutriTheme.colors
    val level by animateFloatAsState(progress, Motion.settle(), label = "waterLevel")
    val reduced = LocalReducedMotion.current
    val phase = if (!reduced) {
        val t = rememberInfiniteTransition(label = "wave")
        t.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "wavePhase"
        )
    } else null
    val track = c.water.copy(alpha = if (c.isDark) 0.14f else 0.1f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Выпито $waterMl из $normMl мл"
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(track, radius = r)
            val wave = Path()
            val clip = Path().apply { addOval(Rect(center, r)) }
            clipPath(clip) {
                val p = phase?.value ?: 0f
                val amp = if (level in 0.02f..0.98f) 6.dp.toPx() else 0f
                val baseY = size.height * (1f - level)
                for (layer in 0..1) {
                    wave.reset()
                    wave.moveTo(0f, size.height)
                    var x = 0f
                    val step = 6f
                    while (x <= size.width) {
                        val k = x / size.width * 2f * Math.PI.toFloat()
                        val y = baseY + amp * kotlin.math.sin(k + p + layer * Math.PI.toFloat())
                        wave.lineTo(x, y)
                        x += step
                    }
                    wave.lineTo(size.width, size.height)
                    wave.close()
                    drawPath(
                        wave,
                        brush = Brush.verticalGradient(
                            listOf(c.water, c.waterDeep),
                            startY = baseY,
                            endY = size.height
                        ),
                        alpha = if (layer == 0) 0.45f else 1f
                    )
                }
            }
            drawCircle(
                c.water.copy(alpha = 0.35f),
                radius = r - 1.dp.toPx(),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val onLiquid = level > 0.55f
            DisplayNumber(
                text = "$waterMl",
                size = 40,
                color = if (onLiquid) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "из $normMl мл",
                style = MaterialTheme.typography.labelLarge,
                color = if (onLiquid) Color.White.copy(alpha = 0.9f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
