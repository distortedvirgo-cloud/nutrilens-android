package com.nutrilens.app.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.BodyComposition
import com.nutrilens.app.data.CycleStore
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.insights.Cycle
import com.nutrilens.app.steps.HealthConnectSteps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Учёт цикла (3.27): переключатель в Профиле (только женский профиль) и карточка
 * «Начались?» на «Сегодня» в ожидаемый день. Данные — CycleStore, логика — Cycle.
 */

class CycleViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val enabled: Boolean = false,
        val last: LocalDate? = null,
        val next: LocalDate? = null,
        val length: Int = Cycle.DEFAULT_LENGTH,
        val marks: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        val app = getApplication<Application>()
        val starts = CycleStore.starts(app)
        _state.value = State(
            enabled = CycleStore.isEnabled(app),
            last = starts.lastOrNull(),
            next = Cycle.nextStart(starts),
            length = Cycle.averageLength(starts),
            marks = starts.size
        )
    }

    fun setEnabled(on: Boolean) {
        CycleStore.setEnabled(getApplication(), on)
        _message.value = null
        refresh()
        if (on) syncHealthConnect(quiet = true)
    }

    fun startedToday() {
        CycleStore.addStart(getApplication())
        _message.value = "Отмечено: начало сегодня"
        refresh()
    }

    fun notYet() {
        CycleStore.notYet(getApplication())
        refresh()
    }

    fun syncHealthConnect(quiet: Boolean = false) {
        viewModelScope.launch {
            val added = CycleStore.syncFromHealthConnect(getApplication())
            if (!quiet || added > 0) {
                _message.value = if (added > 0) "Из Health Connect добавлено отметок: $added"
                else "В Health Connect новых отметок нет — или нет разрешения"
            }
            refresh()
        }
    }
}

private val CYCLE_DATE = DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU"))

/** Профиль → «Цикл». Только в женском профиле; по умолчанию выключено. */
@Composable
fun CycleSettingsSection(settings: SettingsEntity, modifier: Modifier = Modifier, viewModel: CycleViewModel = viewModel()) {
    if (BodyComposition.isMale(settings)) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val hcAvailable = remember { HealthConnectSteps.isAvailable(context) }
    val hcContractOrNull = remember { HealthConnectSteps.permissionContract() }
    val hcContract: ActivityResultContract<Set<String>, Set<String>> = remember(hcContractOrNull) {
        hcContractOrNull ?: NoOpPermissionContract()
    }
    val hcLauncher = rememberLauncherForActivityResult(hcContract) { _ -> viewModel.syncHealthConnect() }
    val hcPermission = remember { CycleStore.healthPermission() }

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = "Цикл",
                icon = Icons.Rounded.WaterDrop,
                tint = MaterialTheme.colorScheme.secondary,
                subtitle = if (state.enabled) "Учитывается в чек-ине" else "Выключено",
                trailing = { NutriSwitch(checked = state.enabled, onCheckedChange = viewModel::setEnabled) }
            )
            Text(
                "Перед месячными и в первые дни вес выше на 1–2 кг из-за воды. С учётом цикла чек-ин не примет это " +
                    "за «вес стоит» и не урежет калории зря. Хранятся только даты — на этом телефоне, без отправки куда-либо.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.enabled) {
                val next = state.next
                Text(
                    when {
                        next == null -> "Отметь начало ближайших месячных — дальше приложение будет ждать их само и спросит в нужный день"
                        else -> "Следующие ожидаются около ${next.format(CYCLE_DATE)} · цикл ≈ ${state.length} дн." +
                            (state.last?.let { " · последние — ${it.format(CYCLE_DATE)}" } ?: "")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SoftButton("Начались сегодня", onClick = viewModel::startedToday, modifier = Modifier.fillMaxWidth())
                if (hcAvailable && hcContractOrNull != null && hcPermission != null) {
                    SoftButton(
                        "Подтянуть из Health Connect",
                        onClick = {
                            try {
                                hcLauncher.launch(setOf(hcPermission))
                            } catch (e: Exception) {
                                viewModel.syncHealthConnect()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** «Сегодня»: ожидаемый день наступил — спросить одним тапом. */
@Composable
fun CycleAskCard(onYes: () -> Unit, onNotYet: () -> Unit, modifier: Modifier = Modifier) {
    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = "Месячные начались?",
                icon = Icons.Rounded.WaterDrop,
                tint = MaterialTheme.colorScheme.secondary,
                subtitle = "По прошлым циклам — примерно сейчас"
            )
            Text(
                "Отметка нужна, чтобы чек-ин не принял воду за «вес стоит». Видишь её только ты.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SoftButton("Ещё нет", onClick = onNotYet, modifier = Modifier.weight(1f))
                GlowButton("Да, сегодня", onClick = onYes, modifier = Modifier.weight(1f))
            }
        }
    }
}
