package com.nutrilens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.update.UpdateChecker
import com.nutrilens.app.update.UpdateOffer
import com.nutrilens.app.update.UpdatePrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * При входе: если на GitHub вышла версия новее — лист «Вышло обновление» со
 * сводкой изменений и тремя вариантами: скачать, напомнить позже, не напоминать.
 */
@Composable
fun UpdatePromptOnLaunch() {
    val context = LocalContext.current
    var offer by remember { mutableStateOf<UpdateOffer?>(null) }
    LaunchedEffect(Unit) {
        delay(1200) // не перебивать первый кадр и «Что нового»
        offer = UpdatePrompt.check(context)
    }
    val current = offer ?: return
    UpdateSheet(
        offer = current,
        onLater = { UpdatePrompt.remindLater(context); offer = null },
        onSkip = { UpdatePrompt.skip(context, current.release.version); offer = null },
        onInstalled = { offer = null }
    )
}

@Composable
private fun UpdateSheet(
    offer: UpdateOffer,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = NutriTheme.colors
    // null — ждём выбора; -1 — загрузка без известного размера; 0..100 — проценты.
    var progress by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun download() {
        if (!UpdateChecker.canInstall(context)) {
            UpdateChecker.openInstallUnknownAppsSettings(context)
            message = "Разрешите установку из этого источника и нажмите «Скачать» ещё раз"
            return
        }
        message = null
        progress = 0
        scope.launch {
            try {
                val file = UpdateChecker.downloadApk(context, offer.release.apkUrl) { p -> progress = p }
                UpdateChecker.installApk(context, file)
                onInstalled()
            } catch (e: Exception) {
                progress = null
                message = "Не удалось скачать: ${e.message?.take(80) ?: "нет сети"}"
            }
        }
    }

    // Свайп вниз во время загрузки не прерывает её; без загрузки — «позже».
    NutriSheet(onDismiss = { if (progress == null) onLater() }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Radius.Card))
                .background(c.brandGradient)
        ) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        TinyLabel(
            "Вышло обновление · ${BuildConfig.VERSION_NAME} → ${offer.release.version}",
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            offer.title.ifBlank { "NutriLens ${offer.release.version}" },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (offer.items.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TonalSection(color = MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    offer.items.forEach { UpdateItem(it) }
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        val p = progress
        if (p != null) {
            Text(
                if (p >= 0) "Скачиваем… $p%" else "Скачиваем…",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            if (p >= 0) {
                LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "После загрузки Android предложит установить — данные сохранятся",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            GlowButton(
                text = "Скачать",
                icon = Icons.Rounded.SystemUpdate,
                onClick = { download() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            SoftButton(text = "Напомнить позже", onClick = onLater, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Не напоминать об этой версии", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun UpdateItem(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
