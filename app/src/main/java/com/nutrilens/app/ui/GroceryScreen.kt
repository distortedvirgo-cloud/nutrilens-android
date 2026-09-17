package com.nutrilens.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.ai.mealJson
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.bg.ToolJobWorker
import com.nutrilens.app.data.GroceryCategoryData
import com.nutrilens.app.data.GroceryData
import com.nutrilens.app.data.GroceryStore
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.ToolJobRepository
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun GroceryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf<GroceryData?>(null) }
    var preferences by remember { mutableStateOf("") }

    // Фоновая задача генерации плана: активная/неудавшаяся/последний готовый результат.
    val jobRepo = remember { ToolJobRepository(NutriLensDatabase.getInstance(context).toolJobDao()) }
    val activeJobs by jobRepo.observeActive(ToolJobWorker.KIND_GROCERY)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val failedJobs by jobRepo.observeFailed(ToolJobWorker.KIND_GROCERY)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val lastDone by jobRepo.observeLastDone(ToolJobWorker.KIND_GROCERY)
        .collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(Unit) {
        data = GroceryStore.load(context)
    }

    // Готовый результат задачи: если план в GroceryStore отличается от свежего — сохраняем.
    // Чекбоксы при этом начинаются с нуля (checked=false), как и раньше при генерации.
    LaunchedEffect(lastDone?.id, lastDone?.result) {
        val job = lastDone ?: return@LaunchedEffect
        if (job.result.isBlank()) return@LaunchedEffect
        val plan = runCatching {
            mealJson.decodeFromString(GeminiTools.GroceryPlan.serializer(), job.result)
        }.getOrNull() ?: return@LaunchedEffect
        val current = data
        if (current == null || current.plan != plan.plan) {
            val fresh = GroceryData(
                plan = plan.plan,
                categories = plan.categories.map {
                    GroceryCategoryData(it.category, it.items)
                },
                checked = emptyList()
            )
            GroceryStore.save(context, fresh)
            data = fresh
        }
    }

    /**
     * Генерация плана считается фоновой задачей: экран замораживает пожелания
     * и цель в job.input и ставит ToolJobWorker; результат приходит через lastDone.
     */
    fun generate() {
        if (activeJobs.isNotEmpty()) return
        scope.launch {
            val settings = SettingsRepository(
                NutriLensDatabase.getInstance(context).settingsDao()
            ).get()
            val input = JSONObject().apply {
                put("note", preferences.trim())
                put("dailyGoal", settings.dailyGoal)
            }
            AnalysisScheduler.enqueueTool(context, ToolJobWorker.KIND_GROCERY, input.toString())
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
            title = "🛒 Покупки",
            subtitle = "План питания и список покупок на неделю",
            onBack = onBack
        )

        if (data == null) {
            OutlinedTextField(
                value = preferences,
                onValueChange = { preferences = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                placeholder = { Text("Пожелания: бюджет, нелюбимые продукты, время на готовку…") },
                shape = RoundedCornerShape(20.dp),
                maxLines = 4
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = "Составить план на неделю",
                onClick = ::generate,
                enabled = activeJobs.isEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
            if (activeJobs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ToolJobProcessingCard("Покупки")
            }
            failedJobs.firstOrNull()?.let { job ->
                Spacer(Modifier.height(12.dp))
                ToolJobErrorCard(
                    title = "Покупки",
                    error = job.error ?: "Не удалось составить план",
                    onRetry = { scope.launch { AnalysisScheduler.retryTool(context, job.id) } },
                    onDismiss = { scope.launch { jobRepo.deleteJob(job.id) } }
                )
            }
        } else {
            val current = data ?: return
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    scope.launch {
                        GroceryStore.clear(context)
                        data = null
                    }
                }) {
                    Text("Составить заново", fontWeight = FontWeight.SemiBold)
                }
            }

            if (activeJobs.isNotEmpty()) {
                ToolJobProcessingCard("Покупки")
                Spacer(Modifier.height(10.dp))
            }
            failedJobs.firstOrNull()?.let { job ->
                ToolJobErrorCard(
                    title = "Покупки",
                    error = job.error ?: "Не удалось составить план",
                    onRetry = { scope.launch { AnalysisScheduler.retryTool(context, job.id) } },
                    onDismiss = { scope.launch { jobRepo.deleteJob(job.id) } }
                )
                Spacer(Modifier.height(10.dp))
            }

            if (current.plan.isNotBlank()) {
                FreshCard(Modifier.fillMaxWidth()) {
                    MarkdownText(current.plan, Modifier.padding(16.dp))
                }
                Spacer(Modifier.height(10.dp))
            }

            current.categories.forEach { category ->
                FreshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            category.category,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        category.items.forEach { item ->
                            val checked = item in current.checked
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            data = GroceryStore.toggleItem(context, item)
                                        }
                                    }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        scope.launch {
                                            data = GroceryStore.toggleItem(context, item)
                                        }
                                    }
                                )
                                Text(
                                    item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (checked) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    textDecoration = if (checked) {
                                        TextDecoration.LineThrough
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
