package com.nutrilens.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nutrilens.app.ai.BodyFatAi
import com.nutrilens.app.data.BodyComposition
import com.nutrilens.app.data.BodyPhotos
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class PhotoBusy { ADD, AI }

/**
 * Фото прогресса (3.25): по желанию, только на этом устройстве. Первое и последнее фото
 * рядом — сравнение «до и сейчас». Оценка жира по фото — грубый ориентир ИИ, в план не идёт.
 * Видна только в режиме «Сжигание жира».
 */
@Composable
fun BodyPhotosSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext
    val db = remember { NutriLensDatabase.getInstance(app) }
    val settings by remember { SettingsRepository(db.settingsDao()).observe() }.collectAsState(initial = null)
    val s = settings ?: return
    if (s.targetFatPercent == null) return

    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf<List<BodyPhotos.Photo>>(emptyList()) }
    var busy by remember { mutableStateOf<PhotoBusy?>(null) }
    var confirmDelete by remember { mutableStateOf<BodyPhotos.Photo?>(null) }
    LaunchedEffect(version) { photos = BodyPhotos.list(app) }

    fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = PhotoBusy.ADD
        scope.launch {
            runCatching { BodyPhotos.add(app, uri) }
                .onSuccess { toast("Фото сохранено") }
                .onFailure { toast("Не получилось открыть фото") }
            busy = null
            version++
        }
    }

    val first = photos.firstOrNull()
    val last = if (photos.size > 1) photos.last() else null
    val newest = last ?: first

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(title = "Фото прогресса", icon = Icons.Rounded.PhotoCamera, subtitle = "по желанию")
            if (first == null) {
                Text(
                    "Фото в одной позе и при одном свете раз в 2 недели — лучший способ увидеть то, что не видно на весах.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    PhotoShot(first, if (last != null) "До" else "Первое", Modifier.weight(1f))
                    if (last != null) PhotoShot(last, "Сейчас", Modifier.weight(1f))
                    else Box(Modifier.weight(1f))
                }
            }
            newest?.estimate?.comment?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SoftButton(
                if (busy == PhotoBusy.ADD) "Сохраняю…" else "Добавить фото",
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = busy == null,
                icon = Icons.Rounded.PhotoCamera,
                modifier = Modifier.fillMaxWidth()
            )
            if (newest != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SoftButton(
                        if (busy == PhotoBusy.AI) "Оцениваю…" else "Оценить жир по фото",
                        onClick = {
                            busy = PhotoBusy.AI
                            scope.launch {
                                try {
                                    val weight = db.weightDao().latest()?.weight
                                    val bytes = withContext(Dispatchers.IO) { newest.full.readBytes() }
                                    val e = BodyFatAi.estimate(
                                        s, bytes, BodyComposition.isMale(s), BodyComposition.heightCm(s), weight
                                    )
                                    BodyPhotos.saveEstimate(app, newest.id, BodyPhotos.Estimate(e.low, e.high, e.comment, System.currentTimeMillis()))
                                    if (e.comment.isNotBlank()) toast(e.comment)
                                } catch (e: Exception) {
                                    toast(e.message ?: "ИИ не ответил — попробуй позже")
                                }
                                busy = null
                                version++
                            }
                        },
                        enabled = busy == null,
                        icon = Icons.Rounded.AutoAwesome,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { confirmDelete = newest }, enabled = busy == null) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Удалить последнее фото", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (busy != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                "Фото хранятся только на этом телефоне — в резервную копию и лидерборд не попадают. " +
                    "В ИИ фото уходит, только когда нажмёшь «Оценить»; оценка грубая (±5%) и в план не идёт.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    confirmDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить фото?") },
            text = { Text("Фото от ${photo.date.format(DATE)} удалится с телефона.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { BodyPhotos.delete(app, photo.id); version++ }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } }
        )
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

@Composable
private fun PhotoShot(photo: BodyPhotos.Photo, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            AsyncImage(
                model = photo.full,
                contentDescription = "Фото: $label",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text("$label · ${photo.date.format(DATE)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        photo.estimate?.let {
            Text(
                "ИИ: ≈ ${BodyComposition.fmt1(it.low)}–${BodyComposition.fmt1(it.high)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
