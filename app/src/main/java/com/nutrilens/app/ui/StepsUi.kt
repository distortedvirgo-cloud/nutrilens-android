package com.nutrilens.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.DataEvents
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.StepsSource
import com.nutrilens.app.insights.DayScore
import com.nutrilens.app.steps.HealthConnectSteps
import com.nutrilens.app.steps.StepCounter
import com.nutrilens.app.steps.StepsSync
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * Шаги (3.23): секция «Шаги» в Профиле (включить, источник, Health Connect, цель)
 * и плитка шагов на «Сегодня» с ручным вводом. Чтение шагов — steps/StepsSync.
 * Шаги — цель поведения: калорий к бюджету они не прибавляют.
 */

/** Шаги не меняют бюджет калорий — говорим это один раз, там где шаги настраиваются и вводятся. */
const val STEPS_NO_CALORIES_NOTE = "Шаги не прибавляют калории: расход уже учтён в недельном пересчёте"

/** «8 000» — разряды через неразрывный пробел. */
fun fmtSteps(n: Int): String {
    val digits = kotlin.math.abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
    return if (n < 0) "-$grouped" else grouped
}

fun stepsSourceLabel(source: String?): String = when (source) {
    StepsSource.HEALTH_CONNECT -> "Health Connect"
    StepsSource.SENSOR -> "датчик телефона"
    else -> "вручную"
}

class StepsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)
    private val settingsRepo = SettingsRepository(db.settingsDao())

    val settings: StateFlow<SettingsEntity> = settingsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    /** Откуда сейчас берутся шаги (см. StepsSource). */
    private val _source = MutableStateFlow(StepsSource.MANUAL)
    val source: StateFlow<String> = _source.asStateFlow()

    /** Health Connect есть на телефоне (подключён или нет). */
    private val _hcAvailable = MutableStateFlow(false)
    val hcAvailable: StateFlow<Boolean> = _hcAvailable.asStateFlow()

    private val _hasSensor = MutableStateFlow(true)
    val hasSensor: StateFlow<Boolean> = _hasSensor.asStateFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val app: Application = getApplication()
            _hcAvailable.value = HealthConnectSteps.isAvailable(app)
            _hasSensor.value = StepCounter.hasSensor(app)
            _source.value = StepsSync.currentSource(app)
        }
    }

    /** Включить/выключить шаги. Разрешение уже спрошено экраном; отказ — шаги только вручную. */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(stepsEnabled = on) }
            val app: Application = getApplication()
            StepsSync.schedule(app, on)
            if (on) StepsSync.refresh(app)
            DataEvents.notifyChanged()
            refreshStatus()
        }
    }

    fun setGoal(goal: Int) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(stepGoal = DayScore.clampStepGoal(goal)) }
            DataEvents.notifyChanged()
        }
    }

    /** После окна разрешений Health Connect: перечитать источник и шаги. */
    fun onHealthConnectResult() {
        viewModelScope.launch {
            StepsSync.refresh(getApplication())
            refreshStatus()
        }
    }
}

/** Пустой контракт на случай, если библиотека Health Connect не отдала свой: кнопку тогда не показываем. */
private class NoOpPermissionContract : ActivityResultContract<Set<String>, Set<String>>() {
    override fun createIntent(context: Context, input: Set<String>): Intent = Intent()
    override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
}

// ---------- Профиль: «Шаги» ----------

/** Секция Профиля: включить шаги (спрашивает разрешение), источник, Health Connect и цель шагов. */
@Composable
fun StepsSettingsSection(modifier: Modifier = Modifier, viewModel: StepsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val hcAvailable by viewModel.hcAvailable.collectAsStateWithLifecycle()
    val hasSensor by viewModel.hasSensor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = macroPalette()

    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    // Разрешение «Физическая активность» (Android 10+). Отказ — шаги всё равно включаем, но только вручную.
    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        viewModel.setEnabled(true)
    }
    val hcContractOrNull = remember { HealthConnectSteps.permissionContract() }
    val hcContract: ActivityResultContract<Set<String>, Set<String>> = remember(hcContractOrNull) {
        hcContractOrNull ?: NoOpPermissionContract()
    }
    val hcLauncher = rememberLauncherForActivityResult(hcContract) { _ -> viewModel.onHealthConnectResult() }
    val hcPermission = remember { HealthConnectSteps.readPermission() }

    fun enable() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            StepCounter.hasSensor(context) && !StepCounter.hasPermission(context)
        if (needsPermission) {
            try {
                activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } catch (e: Exception) {
                viewModel.setEnabled(true)
            }
        } else {
            viewModel.setEnabled(true)
        }
    }

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = "Шаги",
                icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                tint = palette.carbs,
                subtitle = if (settings.stepsEnabled) "Источник: ${stepsSourceLabel(source)}" else "Выключены",
                trailing = {
                    NutriSwitch(
                        checked = settings.stepsEnabled,
                        onCheckedChange = { on -> if (on) enable() else viewModel.setEnabled(false) }
                    )
                }
            )
            if (!settings.stepsEnabled) {
                Text(
                    "Включи — и шаги появятся на «Сегодня». Считает датчик телефона или Health Connect, ввести вручную можно всегда.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val note = when (source) {
                    StepsSource.HEALTH_CONNECT -> "Шаги берутся из Health Connect — вместе с часами и браслетом."
                    StepsSource.SENSOR -> "Шаги считает датчик телефона. Шаги до первого открытия приложения за день могут не попасть в счёт — их можно поправить вручную."
                    else -> if (!hasSensor) {
                        "В телефоне нет шагомера — вводи шаги вручную: тап по плитке шагов на «Сегодня»."
                    } else {
                        "Нет разрешения на физическую активность — шаги вводятся вручную. Разрешить можно в настройках телефона."
                    }
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (source == StepsSource.MANUAL && hasSensor && !StepCounter.hasPermission(context)) {
                    SoftButton("Разрешить датчик шагов", onClick = { enable() }, modifier = Modifier.fillMaxWidth())
                }
                if (hcAvailable && source != StepsSource.HEALTH_CONNECT && hcContractOrNull != null && hcPermission != null) {
                    SoftButton(
                        "Подключить Health Connect",
                        onClick = {
                            try {
                                hcLauncher.launch(setOf(hcPermission))
                            } catch (e: Exception) {
                                // Health Connect не открылся (например, APK вне Play) — остаёмся на датчике.
                                viewModel.refreshStatus()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Цель шагов", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "От ${fmtSteps(DayScore.MIN_STEP_GOAL)} до ${fmtSteps(DayScore.MAX_STEP_GOAL)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StepGoalStepper(
                        value = DayScore.clampStepGoal(settings.stepGoal),
                        onChange = viewModel::setGoal
                    )
                }
                Text(STEPS_NO_CALORIES_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Степпер «− 8 000 +» с шагом 500. */
@Composable
private fun StepGoalStepper(value: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.Pill))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = { onChange(value - DayScore.STEP_GOAL_STEP) },
            enabled = value > DayScore.MIN_STEP_GOAL,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, "Меньше", Modifier.size(18.dp)) }
        }
        Text(
            fmtSteps(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Surface(
            onClick = { onChange(value + DayScore.STEP_GOAL_STEP) },
            enabled = value < DayScore.MAX_STEP_GOAL,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Больше", Modifier.size(18.dp)) }
        }
    }
}

// ---------- «Сегодня»: плитка шагов ----------

/** Плитка шагов на «Сегодня»: число, цель, источник и полоса. Тап — ввести вручную. */
@Composable
fun StepsCard(steps: Int?, source: String?, goal: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = macroPalette().carbs
    FreshCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            Modifier
                .padding(14.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Шаги: ${steps ?: 0} из $goal. Нажми, чтобы ввести вручную"
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.AutoMirrored.Rounded.DirectionsWalk, color, size = 34)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (steps != null) "${fmtSteps(steps)} шагов" else "— шагов",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "цель ${fmtSteps(goal)} · " + if (steps != null) stepsSourceLabel(source) else "тап — ввести",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (steps != null && steps < goal) {
                    Text(
                        "ещё ${fmtSteps(goal - steps)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = color
                    )
                } else if (steps != null) {
                    Text("цель есть", style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
            Spacer(Modifier.height(10.dp))
            ProgressTrack(
                fraction = if (goal > 0 && steps != null) steps.toFloat() / goal else 0f,
                brush = SolidColor(color),
                track = color.copy(alpha = tintAlpha() + 0.06f),
                height = 6.dp
            )
        }
    }
}

/**
 * Ручной ввод шагов за день: ручное значение главнее датчика на этот день.
 * [canReset] — ручное значение уже есть, его можно убрать и вернуться к автоматическому счёту.
 */
@Composable
fun StepsEntryDialog(
    current: Int?,
    canReset: Boolean,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val value = text.toIntOrNull()?.takeIf { it in 0..200_000 }
    NutriDialog(
        onDismiss = onDismiss,
        title = "Шаги за день",
        subtitle = "Ручное значение главнее датчика на этот день. $STEPS_NO_CALORIES_NOTE.",
        confirmText = "Сохранить",
        confirmEnabled = value != null,
        onConfirm = { value?.let(onSave) }
    ) {
        NutriTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
            label = "Шаги",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        if (canReset) {
            Spacer(Modifier.height(12.dp))
            SoftButton("Считать автоматически", onClick = onReset, modifier = Modifier.fillMaxWidth())
        }
    }
}
