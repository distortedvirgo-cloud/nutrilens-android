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
import androidx.compose.material3.CircularProgressIndicator
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
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.WaterRepository
import com.nutrilens.app.insights.waterNormaMl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val today = LocalDate.now().toString()

    private val _waterMl = MutableStateFlow(0)
    val waterMl: StateFlow<Int> = _waterMl.asStateFlow()

    private val _normMl = MutableStateFlow(2000)
    val normMl: StateFlow<Int> = _normMl.asStateFlow()

    private val _loadingAdvice = MutableStateFlow(false)
    val loadingAdvice: StateFlow<Boolean> = _loadingAdvice.asStateFlow()

    private val _advice = MutableStateFlow<String?>(null)
    val advice: StateFlow<String?> = _advice.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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

    fun loadAdvice() {
        if (_loadingAdvice.value) return
        viewModelScope.launch {
            val settings = settingsRepository.get()
            if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
                _error.value = "Сначала добавьте ключ Gemini в настройках"
                return@launch
            }
            _loadingAdvice.value = true
            _error.value = null
            try {
                _advice.value = GeminiTools.waterAdvice(settings,
                    settings.userContext,
                    mealRepository.getLatestWeight()
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось получить совет"
            }
            _loadingAdvice.value = false
        }
    }
}

@Composable
fun WaterToolScreen(onBack: () -> Unit, viewModel: WaterToolViewModel = viewModel()) {
    val waterMl by viewModel.waterMl.collectAsStateWithLifecycle()
    val normMl by viewModel.normMl.collectAsStateWithLifecycle()
    val loadingAdvice by viewModel.loadingAdvice.collectAsStateWithLifecycle()
    val advice by viewModel.advice.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

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
            text = if (loadingAdvice) "ИИ думает…" else "Совет от ИИ",
            onClick = viewModel::loadAdvice,
            enabled = !loadingAdvice,
            modifier = Modifier.fillMaxWidth()
        )

        error?.let { err ->
            Spacer(Modifier.height(12.dp))
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (loadingAdvice) {
            Spacer(Modifier.height(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        advice?.let { text ->
            Spacer(Modifier.height(14.dp))
            FreshCard(Modifier.fillMaxWidth()) {
                MarkdownText(text, Modifier.padding(16.dp))
            }
        }
    }
}
