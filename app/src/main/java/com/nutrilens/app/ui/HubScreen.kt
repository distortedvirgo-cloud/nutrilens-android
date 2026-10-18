package com.nutrilens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette

private enum class HubTone { Brand, Teal, Warm, Violet, Blue, Neutral }

private data class HubItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val route: String,
    val tone: HubTone
)

private val HUB_AI = listOf(
    HubItem(Icons.AutoMirrored.Rounded.Chat, "Диетолог", "Личный ИИ-консультант по питанию", "chat", HubTone.Brand),
    HubItem(Icons.Rounded.Lightbulb, "Идеи еды", "Что съесть в рамках вашей цели", "ideas", HubTone.Warm),
    HubItem(Icons.Rounded.Kitchen, "Холодильник", "Сфотографируйте продукты — получите блюда", "fridge", HubTone.Teal),
    HubItem(Icons.AutoMirrored.Rounded.MenuBook, "Ресторан", "Помощь с выбором по фото меню", "menu", HubTone.Violet),
    HubItem(Icons.Rounded.ShoppingCart, "Покупки", "План питания и список на неделю", "grocery", HubTone.Brand),
    HubItem(Icons.Rounded.Psychology, "Разбор привычек", "ИИ-анализ пищевых привычек", "habitTool", HubTone.Violet)
)

private val HUB_MORE = listOf(
    HubItem(Icons.Rounded.WaterDrop, "Вода", "Трекер воды и персональный совет", "waterTool", HubTone.Blue),
    HubItem(Icons.Rounded.EmojiEvents, "Лидерборд", "Соревнование по плану калорий", "leaderboard", HubTone.Warm),
    HubItem(Icons.Rounded.Settings, "Настройки", "Цели, напоминания, ключ ИИ, данные", "settings", HubTone.Neutral)
)

@Composable
private fun toneColor(tone: HubTone): Color = when (tone) {
    HubTone.Brand -> MaterialTheme.colorScheme.primary
    HubTone.Teal -> MaterialTheme.colorScheme.secondary
    HubTone.Warm -> NutriTheme.colors.warnTo
    HubTone.Violet -> macroPalette().carbs
    HubTone.Blue -> NutriTheme.colors.waterDeep
    HubTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun HubScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 120.dp)
    ) {
        ScreenHeader(
            title = "Ещё",
            subtitle = "Инструменты на базе ИИ и настройки"
        )
        TinyLabel("Инструменты ИИ", Modifier.padding(start = 4.dp, bottom = 10.dp))
        HUB_AI.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { col, item ->
                    HubCard(item, row * 2 + col + 1, Modifier.weight(1f), onNavigate)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
        TinyLabel("Прочее", Modifier.padding(start = 4.dp, bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HUB_MORE.forEachIndexed { i, item ->
                HubRow(item, HUB_AI.size + i + 1, onNavigate)
            }
        }
    }
}

/** Плитка инструмента: тонированная иконка, заголовок, описание. */
@Composable
private fun HubCard(
    item: HubItem,
    index: Int,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    FreshCard(
        modifier = modifier.staggeredIn(index),
        radius = 24,
        onClick = { onNavigate(item.route) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(16.dp)
        ) {
            IconBadge(icon = item.icon, tint = toneColor(item.tone), size = 44)
            Spacer(Modifier.weight(1f))
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Строка списка «Прочее»: иконка, текст, шеврон. */
@Composable
private fun HubRow(item: HubItem, index: Int, onNavigate: (String) -> Unit) {
    FreshCard(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredIn(index),
        radius = 22,
        onClick = { onNavigate(item.route) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            IconBadge(icon = item.icon, tint = toneColor(item.tone), size = 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
