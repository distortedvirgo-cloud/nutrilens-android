package com.nutrilens.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nutrilens.app.bg.AnalysisScheduler
import com.nutrilens.app.bg.ToolJobWorker
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.ToolJobRepository
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.random.Random

private enum class PhotoToolKind { FRIDGE, MENU }

@Composable
fun FridgeScreen(onBack: () -> Unit) = PhotoToolScreen(PhotoToolKind.FRIDGE, onBack)

@Composable
fun MenuScreen(onBack: () -> Unit) = PhotoToolScreen(PhotoToolKind.MENU, onBack)

private fun Context.copyToToolImage(uri: Uri, subdir: String): File? = runCatching {
    val dir = File(filesDir, subdir).apply { mkdirs() }
    val file = File(dir, "tool_${System.currentTimeMillis()}_${Random.nextInt(100_000)}.jpg")
    contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    }
    file
}.getOrNull()

@Composable
private fun PhotoToolScreen(kind: PhotoToolKind, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    var useRemaining by remember { mutableStateOf(true) }

    // Фоновая задача инструмента: активная/неудавшаяся/последний готовый результат.
    val jobRepo = remember { ToolJobRepository(NutriLensDatabase.getInstance(context).toolJobDao()) }
    val kindKey = if (kind == PhotoToolKind.FRIDGE) ToolJobWorker.KIND_FRIDGE else ToolJobWorker.KIND_MENU
    val activeJobs by jobRepo.observeActive(kindKey).collectAsStateWithLifecycle(initialValue = emptyList())
    val failedJobs by jobRepo.observeFailed(kindKey).collectAsStateWithLifecycle(initialValue = emptyList())
    val lastDone by jobRepo.observeLastDone(kindKey).collectAsStateWithLifecycle(initialValue = null)

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris: List<Uri> ->
        val subdir = if (kind == PhotoToolKind.FRIDGE) "fridge_images" else "menu_images"
        val copied = uris.mapNotNull { context.copyToToolImage(it, subdir) }
        photos = (photos + copied).takeLast(4)
    }

    val title = if (kind == PhotoToolKind.FRIDGE) "🧊 Холодильник" else "🍽️ Ресторан"
    val subtitle = if (kind == PhotoToolKind.FRIDGE) {
        "Сфотографируйте продукты — ИИ предложит блюда"
    } else {
        "Сфотографируйте меню — ИИ поможет выбрать"
    }
    val cta = if (kind == PhotoToolKind.FRIDGE) "Что приготовить?" else "Что заказать?"

    /**
     * Анализ считается фоновой задачей: экран замораживает фото и параметры
     * в job.input и ставит ToolJobWorker; результат приходит через lastDone.
     */
    fun analyze() {
        if (photos.isEmpty() || activeJobs.isNotEmpty()) return
        scope.launch {
            val db = NutriLensDatabase.getInstance(context)
            val mealRepository = MealRepository(
                db.mealDao(), db.waterDao(), db.weightDao(), db.workoutDao()
            )
            val settings = SettingsRepository(db.settingsDao()).get()
            val today = LocalDate.now().toString()
            val eaten = mealRepository.mealsOn(today).sumOf { it.calories }
            val remaining = (settings.dailyGoal - eaten).roundToInt().coerceAtLeast(0)
            val input = JSONObject().apply {
                put("photos", JSONArray().apply { photos.forEach { put(it.absolutePath) } })
                put("remainingCalories", remaining)
                put("useRemaining", useRemaining)
            }
            AnalysisScheduler.enqueueTool(context, kindKey, input.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(title = title, subtitle = subtitle, onBack = onBack)

        FreshCard(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { useRemaining = !useRemaining }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Checkbox(checked = useRemaining, onCheckedChange = { useRemaining = it })
                Text(
                    "Учитывать остаток калорий на сегодня",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { file ->
                Box {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(11.dp))
                            .clickable { photos = photos - file },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Убрать",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (photos.size < 4) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            pickLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = "Добавить фото",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (activeJobs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ToolJobProcessingCard(title)
        }

        failedJobs.firstOrNull()?.let { job ->
            Spacer(Modifier.height(12.dp))
            ToolJobErrorCard(
                title = title,
                error = job.error ?: "Не удалось проанализировать фото",
                onRetry = { scope.launch { AnalysisScheduler.retryTool(context, job.id) } },
                onDismiss = { scope.launch { jobRepo.deleteJob(job.id) } }
            )
        }

        Spacer(Modifier.height(14.dp))
        PillButton(
            text = cta,
            onClick = ::analyze,
            enabled = photos.isNotEmpty() && activeJobs.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        )

        lastDone?.result?.takeIf { it.isNotBlank() }?.let { text ->
            Spacer(Modifier.height(14.dp))
            FreshCard(Modifier.fillMaxWidth()) {
                MarkdownText(text, Modifier.padding(16.dp))
            }
        }
    }
}
