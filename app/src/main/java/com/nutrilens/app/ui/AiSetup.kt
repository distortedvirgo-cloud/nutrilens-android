package com.nutrilens.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.hasAnyAiKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Где взять ключ: NanoGPT — пополняемый баланс, Gemini — бесплатный ключ Google. */
const val NANO_KEY_URL = "https://nano-gpt.com/api"
const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * Выбор провайдера ИИ: NanoGPT (рекомендуем) и Gemini (бесплатная альтернатива).
 * Общий для онбординга и Профиль → Нейросеть.
 */
@Composable
fun AiProviderCards(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProviderCard(
            selected = selected == "nanogpt",
            title = "NanoGPT",
            badge = "Рекомендуем",
            badgeColor = MaterialTheme.colorScheme.primary,
            text = "Точнее на сложных блюдах, без дневных лимитов. Платно по факту: копейки за фото, баланс пополняется на сайте.",
            onClick = { onSelect("nanogpt") }
        )
        ProviderCard(
            selected = selected == "gemini",
            title = "Gemini",
            badge = "Бесплатно",
            badgeColor = MaterialTheme.colorScheme.secondary,
            text = "Бесплатный ключ Google. Хватает на обычный день, но есть лимиты запросов в минуту и в сутки.",
            onClick = { onSelect("gemini") }
        )
    }
}

@Composable
private fun ProviderCard(
    selected: Boolean,
    title: String,
    badge: String,
    badgeColor: Color,
    text: String,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.Tile),
        color = if (selected) primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = badgeColor.copy(alpha = 0.15f)) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Ссылка «Получить ключ …» для выбранного провайдера. */
@Composable
fun GetKeyLink(provider: String) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { uri.openUri(if (provider == "gemini") GEMINI_KEY_URL else NANO_KEY_URL) }) {
        Text(if (provider == "gemini") "Получить бесплатный ключ в Google AI Studio" else "Получить ключ на nano-gpt.com")
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/**
 * Плашка «Подключите нейросеть» — пока не задан ни один ключ. Читает настройки
 * сама и до загрузки из БД ничего не показывает (без мигания на старте).
 */
@Composable
fun AiKeyBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val missing by produceState(initialValue = false) {
        SettingsRepository(NutriLensDatabase.getInstance(context).settingsDao()).observe()
            .collect { value = !it.hasAnyAiKey }
    }
    if (!missing) return
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.Card),
        color = primary.copy(alpha = 0.12f),
        border = BorderStroke(1.5.dp, primary.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, contentDescription = null, tint = primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Подключите нейросеть", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Без ключа NanoGPT или Gemini ИИ не посчитает калории. Нажмите — займёт минуту",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = primary)
        }
    }
}
