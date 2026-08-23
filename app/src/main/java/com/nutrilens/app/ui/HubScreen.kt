package com.nutrilens.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class HubItem(
    val emoji: String,
    val title: String,
    val description: String,
    val route: String
)

private val HUB_ITEMS = listOf(
    HubItem("💬", "Диетолог", "Личный ИИ-консультант по питанию", "chat"),
    HubItem("💡", "Идеи еды", "Что съесть в рамках вашей цели", "ideas"),
    HubItem("🧊", "Холодильник", "Сфотографируйте продукты — получите блюда", "fridge"),
    HubItem("🍽️", "Ресторан", "Помощь с выбором по фото меню", "menu"),
    HubItem("🛒", "Покупки", "План питания и список покупок на неделю", "grocery"),
    HubItem("💧", "Вода", "Трекер воды и персональный совет", "waterTool"),
    HubItem("🧠", "Разбор привычек", "ИИ-анализ пищевых привычек", "habitTool"),
    HubItem("⚙️", "Настройки", "Цели, напоминания, ключ ИИ, данные", "settings")
)

@Composable
fun HubScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 110.dp)
    ) {
        ScreenHeader(
            title = "Ещё",
            subtitle = "Инструменты на базе ИИ и настройки"
        )
        var index = 0
        while (index < HUB_ITEMS.size) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubCard(HUB_ITEMS[index], Modifier.weight(1f), onNavigate)
                if (index + 1 < HUB_ITEMS.size) {
                    HubCard(HUB_ITEMS[index + 1], Modifier.weight(1f), onNavigate)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (index + 2 < HUB_ITEMS.size) Spacer(Modifier.height(10.dp))
            index += 2
        }
    }
}

@Composable
private fun HubCard(
    item: HubItem,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    FreshCard(modifier.clickable { onNavigate(item.route) }) {
        Column(Modifier.padding(16.dp)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    item.emoji,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
