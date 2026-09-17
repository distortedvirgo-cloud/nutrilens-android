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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.bg.ToolJobWorker
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.ToolJobRepository
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun HabitToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var habitText by remember { mutableStateOf("") }

    // Фоновая задача разбора привычки: активная/неудавшаяся/последний готовый результат.
    val jobRepo = remember { ToolJobRepository(NutriLensDatabase.getInstance(context).toolJobDao()) }
    val activeJobs by jobRepo.observeActive(ToolJobWorker.KIND_HABIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val failedJobs by jobRepo.observeFailed(ToolJobWorker.KIND_HABIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val lastDone by jobRepo.observeLastDone(ToolJobWorker.KIND_HABIT)
        .collectAsStateWithLifecycle(initialValue = null)

    /**
     * Разбор привычки считается фоновой задачей: экран замораживает текст
     * привычки в job.input и ставит ToolJobWorker; результат приходит через lastDone.
     */
    fun analyze() {
        if (habitText.isBlank() || activeJobs.isNotEmpty()) return
        scope.launch {
            val input = JSONObject().apply {
                put("note", habitText.trim())
            }
            AnalysisScheduler.enqueueTool(context, ToolJobWorker.KIND_HABIT, input.toString())
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
            text = "Разобрать привычку",
            onClick = ::analyze,
            enabled = habitText.isNotBlank() && activeJobs.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        )

        if (activeJobs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ToolJobProcessingCard("Разбор привычки")
        }

        failedJobs.firstOrNull()?.let { job ->
            Spacer(Modifier.height(12.dp))
            ToolJobErrorCard(
                title = "Разбор привычки",
                error = job.error ?: "Не удалось разобрать привычку",
                onRetry = { scope.launch { AnalysisScheduler.retryTool(context, job.id) } },
                onDismiss = { scope.launch { jobRepo.deleteJob(job.id) } }
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
