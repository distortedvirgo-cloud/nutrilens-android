package com.nutrilens.app.ui

import android.app.Application
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.data.DataEvents
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.insights.effectiveMacroGoals
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)

    val settings: StateFlow<SettingsEntity> = SettingsRepository(db.settingsDao()).observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    private val _lastWeight = MutableStateFlow<Double?>(null)
    val lastWeight: StateFlow<Double?> = _lastWeight.asStateFlow()

    init {
        viewModelScope.launch { _lastWeight.value = db.weightDao().latest()?.weight }
        viewModelScope.launch {
            DataEvents.changed.collect { _lastWeight.value = db.weightDao().latest()?.weight }
        }
    }
}

private data class ProfileRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val route: String,
    val tint: Color
)

/**
 * «Профиль»: кто я и какие цели, плюс все настройки, сгруппированные как в
 * системных настройках. Каждая строка открывает свой раздел — вместо одного
 * длинного экрана.
 */
@Composable
fun ProfileScreen(onNavigate: (String) -> Unit, viewModel: ProfileViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()
    val c = NutriTheme.colors
    val palette = macroPalette()
    val goals = effectiveMacroGoals(
        settings.proteinGoal, settings.fatGoal, settings.carbsGoal, settings.dailyGoal, lastWeight
    )
    val nickname = settings.leaderboardNickname.trim()
    val modeLabel = when (settings.analysisMode) {
        "simple" -> "Простой"
        "advanced" -> "Продвинутый"
        else -> "Бесплатно"
    }

    // Цвет иконки — по смыслу раздела (те же, что у его экрана настроек), а не по кругу.
    val primary = MaterialTheme.colorScheme.primary
    val groups = listOf(
        "Питание" to listOf(
            ProfileRow(Icons.Rounded.TrackChanges, "Цели и макросы", "${settings.dailyGoal.roundToInt()} ккал", "settings/goals", MaterialTheme.colorScheme.secondary),
            ProfileRow(Icons.Rounded.NotificationsActive, "Напоминания", "", "settings/reminders", c.warnTo),
            ProfileRow(Icons.Rounded.EmojiEvents, "Лидерборд", nickname.ifEmpty { "нет ника" }, "leaderboard", c.warnTo)
        ),
        "ИИ и данные" to listOf(
            ProfileRow(Icons.Rounded.AutoAwesome, "Нейросеть", modeLabel, "settings/ai", primary),
            ProfileRow(Icons.Rounded.Person, "О себе и ник", "", "settings/profile", palette.protein),
            ProfileRow(Icons.Rounded.Storage, "Экспорт и импорт", "", "settings/data", palette.carbs)
        ),
        "Приложение" to listOf(
            ProfileRow(Icons.Rounded.SystemUpdate, "Обновления", "v${BuildConfig.VERSION_NAME}", "settings/updates", primary),
            ProfileRow(Icons.Rounded.BatteryChargingFull, "Фон и диагностика", "", "settings/background", c.success)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Screen)
            .padding(bottom = 120.dp)
    ) {
        FreshCard(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .staggeredIn(0),
            onClick = { onNavigate("settings/profile") }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(c.brandFrom, MaterialTheme.colorScheme.secondary)))
                ) {
                    Text(
                        (nickname.firstOrNull()?.uppercase() ?: "N"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = c.onBrand
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        nickname.ifEmpty { "Без ника" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Цель ${settings.dailyGoal.roundToInt()} ккал" +
                            (lastWeight?.let { " · %.1f кг".format(it) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.staggeredIn(1)) {
            val toGoals = { onNavigate("settings/goals") }
            TonalTile("${goals.protein.roundToInt()} г", "белки", palette.protein, Modifier.weight(1f), onClick = toGoals)
            TonalTile("${goals.fat.roundToInt()} г", "жиры", palette.fat, Modifier.weight(1f), onClick = toGoals)
            TonalTile("${goals.carbs.roundToInt()} г", "углеводы", palette.carbs, Modifier.weight(1f), onClick = toGoals)
        }

        groups.forEachIndexed { gi, (title, rows) ->
            Spacer(Modifier.height(Space.Section))
            GroupLabel(title)
            FreshCard(Modifier.fillMaxWidth().staggeredIn(2 + gi)) {
                Column {
                    rows.forEachIndexed { i, row ->
                        SettingsRowItem(row, showDivider = i < rows.lastIndex) { onNavigate(row.route) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRowItem(row: ProfileRow, showDivider: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickableRow(onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 14.dp)
        ) {
            IconBadge(row.icon, row.tint, size = 36)
            Spacer(Modifier.width(14.dp))
            Text(
                row.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (row.value.isNotEmpty()) {
                Text(
                    row.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showDivider) Hairline(inset = 64.dp)
    }
}

/** Строка списка с обычным ripple на всю ширину. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.fillMaxWidth().clickable(onClick = onClick)
