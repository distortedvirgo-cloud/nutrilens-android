package com.nutrilens.app.ui

import android.app.Application
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
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            title = "💧 Вода",
            subtitle = "Трекер воды и персональный совет",
            onBack = onBack
        )

        FreshCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("💧", fontSize = 34.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "$waterMl мл",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "норма — $normMl мл",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { viewModel.add(-250) }) {
                        Text("−250 мл", fontWeight = FontWeight.Bold)
                    }
                    PillButton(
                        text = "+250 мл",
                        onClick = { viewModel.add(250) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        PillButton(
            text = "Совет от ИИ",
            onClick = viewModel::loadAdvice,
            enabled = activeJobs.isEmpty(),
            modifier = Modifier.fillMaxWidth()
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
