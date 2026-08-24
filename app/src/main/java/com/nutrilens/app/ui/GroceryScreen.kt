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
import androidx.compose.material3.CircularProgressIndicator
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
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.data.GroceryCategoryData
import com.nutrilens.app.data.GroceryData
import com.nutrilens.app.data.GroceryStore
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun GroceryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf<GroceryData?>(null) }
    var preferences by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        data = GroceryStore.load(context)
    }

    fun generate() {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            try {
                val settings = SettingsRepository(
                    NutriLensDatabase.getInstance(context).settingsDao()
                ).get()
                if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
                    error = "Сначала добавьте ключ Gemini в настройках"
                    loading = false
                    return@launch
                }
                val plan = GeminiTools.generateGroceryList(settings,
                    settings.userContext,
                    settings.dailyGoal,
                    preferences.trim()
                )
                val fresh = GroceryData(
                    plan = plan.plan,
                    categories = plan.categories.map {
                        GroceryCategoryData(it.category, it.items)
                    },
                    checked = emptyList()
                )
                GroceryStore.save(context, fresh)
                data = fresh
            } catch (e: Exception) {
                error = e.message ?: "Не удалось составить план"
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
                text = if (loading) "Составляем план…" else "Составить план на неделю",
                onClick = ::generate,
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
                        "ИИ составляет план питания и список покупок…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
