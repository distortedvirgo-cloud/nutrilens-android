package com.nutrilens.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.ai.mealJson
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.bg.ToolJobWorker
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.ToolJobEntity
import com.nutrilens.app.data.ToolJobRepository
import com.nutrilens.app.insights.effectiveMacroGoals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

private val MEAL_TYPES = listOf("Завтрак", "Обед", "Ужин", "Перекус", "Десерт")

class IdeasViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NutriLensDatabase.getInstance(application)
    private val mealRepository = MealRepository(
        database.mealDao(),
        database.waterDao(),
        database.weightDao(),
        database.workoutDao()
    )
    private val settingsRepository = SettingsRepository(database.settingsDao())
    private val jobRepository = ToolJobRepository(database.toolJobDao())

    private val today = LocalDate.now().toString()

    private val _recommendations = MutableStateFlow<List<GeminiTools.Recommendation>>(emptyList())
    val recommendations: StateFlow<List<GeminiTools.Recommendation>> = _recommendations.asStateFlow()

    /** Активные задачи идей (QUEUED/RUNNING) — для индикатора «в фоне». */
    val activeJobs: StateFlow<List<ToolJobEntity>> = jobRepository
        .observeActive(ToolJobWorker.KIND_IDEAS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Неудавшиеся задачи идей — для карточки сбоя с повтором. */
    val failedJobs: StateFlow<List<ToolJobEntity>> = jobRepository
        .observeFailed(ToolJobWorker.KIND_IDEAS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Последний готовый результат — источник списка идей. */
    val lastDone: StateFlow<ToolJobEntity?> = jobRepository
        .observeLastDone(ToolJobWorker.KIND_IDEAS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            lastDone.collect { job ->
                _recommendations.value = job?.let {
                    runCatching {
                        mealJson.decodeFromString(
                            ListSerializer(GeminiTools.Recommendation.serializer()), it.result
                        )
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }
    }

    /**
     * Идеи считаются фоновой задачей: экран только замораживает параметры
     * в job.input и ставит ToolJobWorker; результат приходит через lastDone.
     */
    fun loadIdeas(mealType: String) {
        if (activeJobs.value.isNotEmpty()) return
        viewModelScope.launch {
            val settings = settingsRepository.get()
            val meals = mealRepository.mealsOn(today)
            val totals = meals.sumOf { it.calories }
            val remaining = (settings.dailyGoal - totals).roundToInt().coerceAtLeast(100)
            val weight = mealRepository.getLatestWeight()
            val goals = effectiveMacroGoals(
                settings.proteinGoal, settings.fatGoal, settings.carbsGoal,
                settings.dailyGoal, weight
            )
            val input = JSONObject().apply {
                put("note", mealType)
                put("remainingCalories", remaining)
                put("macroP", goals.protein)
                put("macroF", goals.fat)
                put("macroC", goals.carbs)
            }
            AnalysisScheduler.enqueueTool(
                getApplication(), ToolJobWorker.KIND_IDEAS, input.toString()
            )
        }
    }

    /** Повтор неудавшейся задачи идей (кнопка «Повторить» на карточке сбоя). */
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

    fun openRecipe(rec: GeminiTools.Recommendation) {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            _recipeTitle.value = rec.title
            _recipeText.value = null
            _recipeLoading.value = true
            try {
                _recipeText.value = GeminiTools.getDetailedRecipe(settings, rec.recipePrompt)
            } catch (e: Exception) {
                _recipeText.value = "Не удалось загрузить рецепт: ${e.message}"
            }
            _recipeLoading.value = false
        }
    }

    private val _recipeTitle = MutableStateFlow<String?>(null)
    val recipeTitle: StateFlow<String?> = _recipeTitle.asStateFlow()

    private val _recipeText = MutableStateFlow<String?>(null)
    val recipeText: StateFlow<String?> = _recipeText.asStateFlow()

    private val _recipeLoading = MutableStateFlow(false)
    val recipeLoading: StateFlow<Boolean> = _recipeLoading.asStateFlow()

    fun closeRecipe() {
        _recipeTitle.value = null
        _recipeText.value = null
    }
}

@Composable
fun IdeasScreen(onBack: () -> Unit, viewModel: IdeasViewModel = viewModel()) {
    val activeJobs by viewModel.activeJobs.collectAsStateWithLifecycle()
    val failedJobs by viewModel.failedJobs.collectAsStateWithLifecycle()
    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val recipeTitle by viewModel.recipeTitle.collectAsStateWithLifecycle()
    val recipeText by viewModel.recipeText.collectAsStateWithLifecycle()
    val recipeLoading by viewModel.recipeLoading.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(MEAL_TYPES[1]) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Screen)
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            title = "Что съесть?",
            subtitle = "Идеи в рамках вашей цели",
            onBack = onBack
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            MEAL_TYPES.forEach { type ->
                NutriChip(text = type, onClick = { selectedType = type }, selected = selectedType == type)
            }
        }

        activeJobs.firstOrNull()?.let {
            Spacer(Modifier.height(12.dp))
            ToolJobProcessingCard("Идеи еды")
        }

        failedJobs.firstOrNull()?.let { job ->
            Spacer(Modifier.height(12.dp))
            ToolJobErrorCard(
                title = "Идеи еды",
                error = job.error ?: "Не удалось",
                onRetry = { viewModel.retryTool(job) },
                onDismiss = { viewModel.dismiss(job) }
            )
        }

        Spacer(Modifier.height(12.dp))
        PillButton(
            text = "Предложить идеи",
            onClick = { viewModel.loadIdeas(selectedType) },
            enabled = activeJobs.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        )

        recommendations.forEachIndexed { idx, rec ->
            Spacer(Modifier.height(Space.Gap))
            FreshCard(Modifier.fillMaxWidth().staggeredIn(idx)) {
                Column(Modifier.padding(Space.Card)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rec.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${rec.calories.roundToInt()}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "ккал",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rec.shortDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    SoftButton(
                        text = "Смотреть рецепт",
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        onClick = { viewModel.openRecipe(rec) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (recommendations.isEmpty() && activeJobs.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Нажмите «Предложить идеи» — ИИ учтёт съеденное сегодня и подберёт 3 подходящих блюда.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (recipeTitle != null) {
        RecipeSheet(
            title = recipeTitle ?: "",
            loading = recipeLoading,
            text = recipeText,
            onClose = viewModel::closeRecipe
        )
    }
}

/** Рецепт — нижний лист: тянется жестом, длинный текст удобно листать. */
@Composable
private fun RecipeSheet(title: String, loading: Boolean, text: String?, onClose: () -> Unit) {
    NutriSheet(onDismiss = onClose, title = title) {
        run {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                MarkdownText(text ?: "")
            }
        }
    }
}
