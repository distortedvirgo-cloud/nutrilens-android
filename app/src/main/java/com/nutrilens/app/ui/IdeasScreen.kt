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
import com.nutrilens.app.ai.buildRecentMealsContext
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.insights.effectiveMacroGoals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val today = LocalDate.now().toString()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _recommendations = MutableStateFlow<List<GeminiTools.Recommendation>>(emptyList())
    val recommendations: StateFlow<List<GeminiTools.Recommendation>> = _recommendations.asStateFlow()

    private val _recipeTitle = MutableStateFlow<String?>(null)
    val recipeTitle: StateFlow<String?> = _recipeTitle.asStateFlow()

    private val _recipeText = MutableStateFlow<String?>(null)
    val recipeText: StateFlow<String?> = _recipeText.asStateFlow()

    private val _recipeLoading = MutableStateFlow(false)
    val recipeLoading: StateFlow<Boolean> = _recipeLoading.asStateFlow()

    fun loadIdeas(mealType: String) {
        if (_loading.value) return
        viewModelScope.launch {
            val settings = settingsRepository.get()
            if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
                _error.value = "Сначала добавьте ключ Gemini в настройках"
                return@launch
            }
            _loading.value = true
            _error.value = null
            _recommendations.value = emptyList()
            try {
                val meals = mealRepository.mealsOn(today)
                val totals = meals.sumOf { it.calories }
                val remaining = (settings.dailyGoal - totals).roundToInt().coerceAtLeast(100)
                val weight = mealRepository.getLatestWeight()
                val goals = effectiveMacroGoals(
                    settings.proteinGoal, settings.fatGoal, settings.carbsGoal,
                    settings.dailyGoal, weight
                )
                val ideas = GeminiTools.getRecommendations(
                    settings = settings,
                    userContext = settings.userContext,
                    userInput = mealType,
                    remainingCalories = remaining,
                    recentMealsContext = buildRecentMealsContext(meals),
                    macroGoals = Triple(goals.protein, goals.fat, goals.carbs)
                )
                _recommendations.value = ideas
                if (ideas.isEmpty()) _error.value = "Идеи закончились — попробуйте другой тип приёма"
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось получить идеи"
            }
            _loading.value = false
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

    fun closeRecipe() {
        _recipeTitle.value = null
        _recipeText.value = null
    }
}

@Composable
fun IdeasScreen(onBack: () -> Unit, viewModel: IdeasViewModel = viewModel()) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val recipeTitle by viewModel.recipeTitle.collectAsStateWithLifecycle()
    val recipeText by viewModel.recipeText.collectAsStateWithLifecycle()
    val recipeLoading by viewModel.recipeLoading.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(MEAL_TYPES[1]) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            title = "💡 Что съесть?",
            subtitle = "Идеи в рамках вашей цели",
            onBack = onBack
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MEAL_TYPES.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = {
                        Text(type, style = MaterialTheme.typography.labelLarge)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        PillButton(
            text = if (loading) "Думаем…" else "Предложить идеи",
            onClick = { viewModel.loadIdeas(selectedType) },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        )

        error?.let { err ->
            Spacer(Modifier.height(12.dp))
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (loading) {
            Spacer(Modifier.height(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "ИИ подбирает идеи под ваш остаток калорий…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        recommendations.forEach { rec ->
            Spacer(Modifier.height(10.dp))
            FreshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rec.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "${rec.calories.roundToInt()} ккал",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rec.shortDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { viewModel.openRecipe(rec) }) {
                        Text("Смотреть рецепт →", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!loading && recommendations.isEmpty() && error == null) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Нажмите «Предложить идеи» — ИИ учтёт съеденное сегодня и подберёт 3 подходящих блюда.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }

    if (recipeTitle != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeRecipe,
            title = {
                Text(recipeTitle ?: "", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (recipeLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        MarkdownText(recipeText ?: "")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeRecipe) { Text("Закрыть") }
            }
        )
    }
}
