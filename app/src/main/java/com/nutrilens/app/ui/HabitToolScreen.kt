package com.nutrilens.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun HabitToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var habitText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun analyze() {
        if (habitText.isBlank() || loading) return
        scope.launch {
            loading = true
            error = null
            result = null
            try {
                val settings = SettingsRepository(
                    NutriLensDatabase.getInstance(context).settingsDao()
                ).get()
                if (settings.apiKey.isBlank()) {
                    error = "Сначала добавьте ключ Gemini в настройках"
                    loading = false
                    return@launch
                }
                result = GeminiTools.analyzeHabit(
                    settings.apiKey,
                    settings.userContext,
                    habitText.trim()
                )
            } catch (e: Exception) {
                error = e.message ?: "Не удалось разобрать привычку"
            }
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            title = "🧠 Разбор привычки",
            subtitle = "Опишите привычку — ИИ поможет её понять и изменить",
            onBack = onBack
        )

        OutlinedTextField(
            value = habitText,
            onValueChange = { habitText = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            placeholder = { Text("Например: каждый вечер ем сладкое за сериалом") },
            shape = RoundedCornerShape(20.dp),
            maxLines = 6
        )

        Spacer(Modifier.height(12.dp))
        PillButton(
            text = if (loading) "Разбираем…" else "Разобрать привычку",
            onClick = ::analyze,
            enabled = habitText.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth()
        )

        error?.let { err ->
            Spacer(Modifier.height(12.dp))
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (loading) {
            Spacer(Modifier.height(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        result?.let { text ->
            Spacer(Modifier.height(14.dp))
            FreshCard(Modifier.fillMaxWidth()) {
                MarkdownText(text, Modifier.padding(16.dp))
            }
        }
    }
}
