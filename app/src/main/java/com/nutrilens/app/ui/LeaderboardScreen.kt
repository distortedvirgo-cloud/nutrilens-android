package com.nutrilens.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.BuildConfig
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.leaderboard.LeaderboardEntry
import com.nutrilens.app.leaderboard.LeaderboardMath
import com.nutrilens.app.leaderboard.LeaderboardPeriod
import com.nutrilens.app.leaderboard.LeaderboardRow
import com.nutrilens.app.leaderboard.LeaderboardSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Данные экрана лидерборда. Участники хранятся в GitHub-репозитории
 * (см. LeaderboardSync): экран при открытии пушит свои сегодняшние ккал,
 * затем тянет всех и считает строки через LeaderboardMath.
 */
class LeaderboardViewModel(application: Application) : AndroidViewModel(application) {

    private val sync = LeaderboardSync(application)
    /** Свой id в том виде, в каком его отдаёт прокси (хеш). */
    val playerId: String = LeaderboardSync.publicId(com.nutrilens.app.leaderboard.PlayerIdentity.id(application))
    private val settingsRepository =
        SettingsRepository(NutriLensDatabase.getInstance(application).settingsDao())

    /** Сборка настроена, если задан адрес прокси лидерборда. */
    val configured: Boolean = LeaderboardSync.API.isNotBlank()

    /** Ник из настроек: без него в лидерборде участвовать нельзя. */
    val nickname: StateFlow<String> = settingsRepository.observe()
        .map { it.leaderboardNickname.trim() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _period = MutableStateFlow(LeaderboardPeriod.DAY)
    val period: StateFlow<LeaderboardPeriod> = _period.asStateFlow()

    /** null — ещё не загружали; пустой список — загрузили, но участников нет. */
    private val _entries = MutableStateFlow<List<LeaderboardEntry>?>(null)
    val entries: StateFlow<List<LeaderboardEntry>?> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setPeriod(value: LeaderboardPeriod) {
        _period.value = value
    }

    /** Автозагрузка при открытии и кнопка «Обновить»: сначала пушим свой день, потом тянем всех. */
    fun refresh() {
        if (_loading.value || !configured || nickname.value.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                sync.pushOwn()
                _entries.value = sync.fetchAll()
            } catch (e: Exception) {
                _error.value = "Не удалось загрузить лидерборд: ${e.message?.take(120)}"
            } finally {
                _loading.value = false
            }
        }
    }
}

@Composable
fun LeaderboardScreen(onBack: () -> Unit, viewModel: LeaderboardViewModel = viewModel()) {
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Автозагрузка при открытии: как только известен ник и сборка настроена.
    LaunchedEffect(nickname) {
        if (viewModel.configured && nickname.isNotBlank()) viewModel.refresh()
    }

    // Строки периода считаем локально: при смене периода сеть не трогаем.
    val rows: List<LeaderboardRow> = remember(entries, period, nickname) {
        entries?.let { LeaderboardMath.rows(it, period, viewModel.playerId, nickname) } ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = "Лидерборд",
            subtitle = "Соревнование: кто точнее вписывается в план",
            onBack = onBack
        )

        when {
            !viewModel.configured -> StatusCard("Лидерборд не настроен в этой сборке")
            nickname.isBlank() -> StatusCard("Введи ник в настройках, чтобы участвовать")
            else -> {
                SegmentedSwitcher(
                    options = listOf(
                        LeaderboardPeriod.DAY to "День",
                        LeaderboardPeriod.WEEK to "Неделя",
                        LeaderboardPeriod.MONTH to "Месяц"
                    ),
                    selected = period,
                    onSelect = viewModel::setPeriod,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                SoftButton(
                    text = "Обновить",
                    onClick = viewModel::refresh,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                when {
                    error != null -> ErrorCard(
                        message = error ?: "",
                        onRetry = viewModel::refresh
                    )
                    entries == null -> LoadingState(Modifier.weight(1f))
                    rows.isEmpty() -> StatusCard("Пока только ты в лидерборде")
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(rows) { index, row ->
                            LeaderboardRowCard(
                                row = row,
                                place = index,
                                period = period,
                                modifier = Modifier.staggeredIn(index)
                            )
                        }
                        if (rows.any { it.program }) item {
                            Text(
                                "🔥 — участник программы «Сжигание жира»: его очки — оценка дня (калории, белок, движение, сон)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Цвета медалей топ-3 (золото / серебро / бронза) — семантические, одинаковые в обеих темах.
private val MedalColors = listOf(Color(0xFFF2B53A), Color(0xFFA9B4C2), Color(0xFFCD8A57))

/**
 * Аватар с местом: у топ-3 — корона цвета медали над аватаром и такое же кольцо,
 * у остальных — маленький номер в углу аватара. Отдельный кружок с местом не нужен:
 * имени остаётся больше места.
 */
@Composable
private fun PlacedAvatar(place: Int, avatar: String, nickname: String) {
    val medal = MedalColors.getOrNull(place)
    Box(Modifier.width(48.dp).height(if (medal != null) 60.dp else 48.dp)) {
        Box(
            Modifier.align(Alignment.BottomCenter).size(48.dp).clip(CircleShape)
                .background(medal?.let { Brush.linearGradient(listOf(it, it.copy(alpha = 0.7f))) } ?: SolidColor(Color.Transparent))
                .padding(if (medal != null) 2.5.dp else 2.dp),
            contentAlignment = Alignment.Center
        ) { LeaderAvatar(avatar, nickname, 43.dp) }
        if (medal != null) {
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopCenter).width(26.dp).height(17.dp)) {
                val w = size.width; val h = size.height
                val crown = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.08f, h); lineTo(0f, h * 0.28f); lineTo(w * 0.3f, h * 0.58f); lineTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.7f, h * 0.58f); lineTo(w, h * 0.28f); lineTo(w * 0.92f, h); close()
                }
                val brush = Brush.verticalGradient(listOf(medal.copy(alpha = 0.9f), medal))
                drawPath(crown, brush)
                listOf(0f to h * 0.28f, w * 0.5f to h * 0.12f, w to h * 0.28f).forEach { (x, y) ->
                    drawCircle(medal, radius = w * 0.075f, center = androidx.compose.ui.geometry.Offset(x.coerceIn(w * 0.075f, w - w * 0.075f), y))
                }
            }
        } else {
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Text("${place + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Вторичная строка: «1840 / 2100 ккал», для недели/месяца ещё «· 5/7 дней». */
private fun secondaryLine(row: LeaderboardRow, period: LeaderboardPeriod): String {
    val kcal = "${fmtKcal(row.calories)} / ${fmtKcal(row.goal)} ккал"
    return when (period) {
        LeaderboardPeriod.DAY -> kcal
        LeaderboardPeriod.WEEK -> "$kcal · ${row.daysCounted}/7 дней"
        LeaderboardPeriod.MONTH -> "$kcal · ${row.daysCounted}/${LocalDate.now().lengthOfMonth()} дней"
    }
}

private fun fmtKcal(value: Double): String = value.roundToLong().toString()

@Composable
private fun LeaderboardRowCard(
    row: LeaderboardRow,
    place: Int,
    period: LeaderboardPeriod,
    modifier: Modifier = Modifier
) {
    FreshCard(
        color = if (row.isSelf) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        radius = 22,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlacedAvatar(place, row.avatar, row.nickname)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (row.program) "🔥 ${row.nickname}" else row.nickname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (row.isSelf) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondaryLine(row, period),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isSelf) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (period == LeaderboardPeriod.DAY) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (row.isOver)
                            "переел на +${(row.calories - row.goal).roundToInt()} ккал"
                        else "вписался",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.isOver) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // «68%» крупно и «плана» под ним — так справа уже, и имя влезает целиком.
            // Участник программы (🔥) соревнуется оценкой дня — её и показываем.
            if (row.program) Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${row.score}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("оценка дня", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (row.percent > 100) "+${row.percent - 100}%" else "${row.percent}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (row.isOver) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary
                )
                Text("плана", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Информационная карточка на весь доступный контент (пустые/служебные состояния). */
@Composable
private fun StatusCard(text: String) {
    FreshCard(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp)
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Загружаем лидерборд…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    FreshCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            SoftButton(
                text = "Повторить",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
