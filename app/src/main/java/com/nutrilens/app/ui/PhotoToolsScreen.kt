package com.nutrilens.app.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
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
import androidx.compose.material3.CircularProgressIndicator
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
import coil.compose.AsyncImage
import com.nutrilens.app.ai.GeminiTools
import com.nutrilens.app.ai.ImagePrep
import com.nutrilens.app.data.MealRepository
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.launch
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
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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

    fun analyze() {
        if (photos.isEmpty() || loading) return
        scope.launch {
            loading = true
            error = null
            result = null
            try {
                val db = NutriLensDatabase.getInstance(context)
                val mealRepository = MealRepository(
                    db.mealDao(), db.waterDao(), db.weightDao(), db.workoutDao()
                )
                val settings = SettingsRepository(db.settingsDao()).get()
                if (settings.apiKey.isBlank() && settings.nanoApiKey.isBlank()) {
                    error = "Сначала добавьте ключ Gemini в настройках"
                    loading = false
                    return@launch
                }
                val today = LocalDate.now().toString()
                val eaten = mealRepository.mealsOn(today).sumOf { it.calories }
                val remaining = (settings.dailyGoal - eaten).roundToInt().coerceAtLeast(0)
                val images = photos.map {
                    Base64.encodeToString(ImagePrep.readBytes(it), Base64.NO_WRAP)
                }
                result = if (kind == PhotoToolKind.FRIDGE) {
                    GeminiTools.analyzeFridge(settings, settings.userContext, settings.dailyGoal,
                        remaining, useRemaining, images
                    )
                } else {
                    GeminiTools.analyzeMenu(settings, settings.userContext, settings.dailyGoal,
                        remaining, useRemaining, images
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: "Не удалось проанализировать фото"
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

        Spacer(Modifier.height(14.dp))
        PillButton(
            text = if (loading) "Анализируем…" else cta,
            onClick = ::analyze,
            enabled = photos.isNotEmpty() && !loading,
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
