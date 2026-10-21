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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nutrilens.app.update.ChangelogEntry
import com.nutrilens.app.update.WhatsNew
import com.nutrilens.app.ui.theme.NutriTheme

/**
 * «Что нового» после обновления: лист в стиле карточки блюда — герой-бейдж
 * версии, пункты с галочками, одна главная кнопка. Показывается один раз.
 */
@Composable
fun WhatsNewOnUpdate() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ChangelogEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = WhatsNew.pending(context) }
    if (entries.isEmpty()) return
    val close = {
        WhatsNew.markSeen(context)
        entries = emptyList()
    }
    WhatsNewSheet(entries = entries, onClose = close)
}

@Composable
private fun WhatsNewSheet(entries: List<ChangelogEntry>, onClose: () -> Unit) {
    val c = NutriTheme.colors
    val latest = entries.first()
    NutriSheet(onDismiss = onClose) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Radius.Card))
                .background(c.brandGradient)
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        TinyLabel("Что нового · версия ${latest.versionName}", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(latest.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Spacer(Modifier.height(20.dp))
                SectionTitle("Версия ${entry.versionName} · ${entry.title}")
            }
            TonalSection(color = MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    entry.items.forEach { item -> ChangeItem(item) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        GlowButton(text = "Отлично", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChangeItem(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
