package com.nutrilens.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.BodyComposition
import com.nutrilens.app.data.BodyMeasurementEntity
import com.nutrilens.app.data.CheckInEntity
import com.nutrilens.app.data.FatBurnPlanSync
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.TrainingStore
import com.nutrilens.app.data.checkInsActive
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette
import com.nutrilens.app.training.StrengthAnalyzer
import com.nutrilens.app.training.StrengthCheck
import com.nutrilens.app.training.WorkoutCsv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Недельный чек-ин режима «Сжигание жира»: карточка «Пора подвести неделю» на
 * «Сегодня», лист из трёх шагов (вес → замер → самочувствие), экран итогов
 * с новым планом и история «Чек-ины» в Прогрессе. Расчёт — FatBurnPlanSync.completeCheckIn.
 */

class CheckInViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)
    private val settingsRepo = SettingsRepository(db.settingsDao())

    val settings: StateFlow<SettingsEntity> = settingsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    /** Все чек-ины, новые сверху. */
    val history: StateFlow<List<CheckInEntity>> = db.checkInDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Вес, уже записанный сегодня, — им заполняем первый шаг. */
    private val _todayWeight = MutableStateFlow<Double?>(null)
    val todayWeight: StateFlow<Double?> = _todayWeight.asStateFlow()

    private val _lastWeight = MutableStateFlow<Double?>(null)
    val lastWeight: StateFlow<Double?> = _lastWeight.asStateFlow()

    private val _todayMeasurement = MutableStateFlow<BodyMeasurementEntity?>(null)
    val todayMeasurement: StateFlow<BodyMeasurementEntity?> = _todayMeasurement.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _result = MutableStateFlow<CheckInEntity?>(null)
    val result: StateFlow<CheckInEntity?> = _result.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Когда последний раз загружали тренировки (3.27); null — ни разу. */
    private val _trainingImported = MutableStateFlow<LocalDate?>(null)
    val trainingImported: StateFlow<LocalDate?> = _trainingImported.asStateFlow()

    /** Итог последней загрузки тренировок — одна строка под кнопкой. */
    private val _trainingMessage = MutableStateFlow<String?>(null)
    val trainingMessage: StateFlow<String?> = _trainingMessage.asStateFlow()

    fun importTraining(uri: Uri) {
        viewModelScope.launch {
            _trainingMessage.value = try {
                TrainingStore.describe(TrainingStore.importUri(getApplication(), uri))
            } catch (e: WorkoutCsv.FormatException) {
                e.message
            } catch (e: Exception) {
                "Не получилось прочитать файл: ${e.message ?: "попробуй ещё раз"}"
            }
            _trainingImported.value = TrainingStore.importedAt(getApplication())
        }
    }

    /** Перед открытием листа: сбросить прошлый итог и подтянуть сегодняшние вес и замер. */
    fun prepare() {
        _result.value = null
        _error.value = null
        _trainingMessage.value = null
        _trainingImported.value = TrainingStore.importedAt(getApplication())
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val latest = db.weightDao().latest()
            _lastWeight.value = latest?.weight
            _todayWeight.value = latest?.takeIf { it.date == today }?.weight
            _todayMeasurement.value = db.bodyMeasurementDao().latest()?.takeIf { it.date == today }
        }
    }

    fun complete(
        weightKg: Double,
        measurement: BodyMeasurementEntity?,
        heightCm: Double?,
        hunger: Int?,
        energy: Int?,
        sleep: Int?
    ) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            try {
                val saved = FatBurnPlanSync.completeCheckIn(getApplication(), weightKg, measurement, heightCm, hunger, energy, sleep)
                if (saved == null) _error.value = "Чек-ин работает в режиме «Сжигание жира» — включи его в Профиле"
                else _result.value = saved
            } catch (e: Exception) {
                _error.value = "Не получилось подвести итоги: ${e.message ?: "попробуй ещё раз"}"
            }
            _saving.value = false
        }
    }
}

private val CHECKIN_DATE = DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU"))

private fun parseWeight(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..300.0 }

/** «+0,4» / «−1,2» / «0» — со знаком, минус типографский. */
private fun signed(v: Double): String {
    val text = BodyComposition.fmt1(abs(v))
    return when {
        text == "0" -> "0"
        v > 0 -> "+$text"
        else -> "−$text"
    }
}

/** Цвет сигнала сохранения мышц. */
@Composable
private fun signalColor(signal: String): Color {
    val c = NutriTheme.colors
    return when (signal) {
        "red" -> c.dangerTo
        "yellow" -> c.warnTo
        else -> c.success
    }
}

private fun signalLabel(signal: String): String = when (signal) {
    "red" -> "красный"
    "yellow" -> "жёлтый"
    else -> "зелёный"
}

@Composable
private fun SignalDot(signal: String, size: Int = 12) {
    Box(
        Modifier
            .size(size.dp)
            .background(signalColor(signal), CircleShape)
    )
}

// ---------- «Сегодня»: пора подвести неделю ----------

/** Заметная карточка на «Сегодня», пока чек-ин «пора». */
@Composable
fun CheckInDueCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    FreshCard(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, onClick = onClick) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(
                title = "Пора подвести неделю · 2 минуты",
                icon = Icons.Rounded.EventAvailable,
                tint = primary,
                subtitle = "Вес, замер и самочувствие — и план на новую неделю"
            )
            GlowButton("Начать чек-ин", onClick = onClick, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------- Лист чек-ина ----------

/**
 * Чек-ин в три коротких шага, каждый можно пропустить, кроме веса.
 * Ничего не сохраняется до конца: закрыл лист на середине — ничего не потерял и не сломал.
 */
@Composable
fun CheckInSheet(onDismiss: () -> Unit, viewModel: CheckInViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val todayWeight by viewModel.todayWeight.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()
    val todayMeasurement by viewModel.todayMeasurement.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val trainingImported by viewModel.trainingImported.collectAsStateWithLifecycle()
    val trainingMessage by viewModel.trainingMessage.collectAsStateWithLifecycle()
    val trainingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTraining(uri)
    }

    var step by remember { mutableStateOf(0) }
    var weightText by remember { mutableStateOf("") }
    var weightTouched by remember { mutableStateOf(false) }
    // Вес уже записан сегодня — подставляем, пока человек сам не начал вводить.
    val prefill = todayWeight?.let { BodyComposition.fmt1(it) }.orEmpty()
    val shownWeight = if (weightTouched) weightText else prefill
    val weight = parseWeight(shownWeight)

    val form = remember(todayMeasurement) { MeasurementFormState(settings, todayMeasurement) }
    form.settings = settings
    var measured by remember { mutableStateOf(false) }
    var hunger by remember { mutableStateOf<Int?>(null) }
    var energy by remember { mutableStateOf<Int?>(null) }
    var sleep by remember { mutableStateOf<Int?>(null) }

    val done = result
    val title = when {
        done != null -> "Итоги недели"
        step == 0 -> "Вес сегодня"
        step == 1 -> "Замер лентой"
        else -> "Самочувствие за неделю"
    }
    val subtitle = when {
        done != null -> "Чек-ин ${LocalDate.parse(done.date).format(CHECKIN_DATE)} · следующий через неделю"
        else -> "Шаг ${step + 1} из 3"
    }

    NutriSheet(onDismiss = onDismiss, title = title, subtitle = subtitle) {
        if (done != null) {
            CheckInResultContent(done)
            Spacer(Modifier.height(20.dp))
            GlowButton("Готово", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        } else when (step) {
            0 -> {
                NutriTextField(
                    value = shownWeight,
                    onValueChange = { v ->
                        weightTouched = true
                        weightText = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
                    },
                    label = "Вес",
                    suffix = "кг",
                    placeholder = lastWeight?.let { BodyComposition.fmt1(it) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (todayWeight != null) "Ты уже взвешивался сегодня — проверь и жми «Дальше»."
                    else "Лучше утром, после туалета и до еды — так цифры честнее.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                TrainingImportRow(
                    importedAt = trainingImported,
                    message = trainingMessage,
                    onPick = { trainingPicker.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }
                )
                Spacer(Modifier.height(20.dp))
                SheetActions(
                    secondaryText = "Отмена",
                    onSecondary = onDismiss,
                    primaryText = "Дальше",
                    onPrimary = { step = 1 },
                    primaryEnabled = weight != null
                )
            }
            1 -> {
                BodyMeasurementForm(form)
                Spacer(Modifier.height(20.dp))
                SheetActions(
                    secondaryText = "Замерю позже",
                    onSecondary = {
                        measured = false
                        step = 2
                    },
                    primaryText = "Дальше",
                    onPrimary = {
                        measured = true
                        step = 2
                    },
                    primaryEnabled = form.result != null
                )
            }
            else -> {
                Text(
                    "Оцени неделю от 1 до 5 — это поможет понять, не слишком ли жёсткий план.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                RatingRow("Голод", "1 — почти не хотелось есть, 5 — постоянно", hunger) { hunger = it }
                Spacer(Modifier.height(14.dp))
                RatingRow("Энергия", "1 — без сил, 5 — бодрость", energy) { energy = it }
                Spacer(Modifier.height(14.dp))
                RatingRow("Сон", "1 — плохо, 5 — отлично", sleep) { sleep = it }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                if (saving) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Считаю итоги и новый план…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    val finish = { withRatings: Boolean ->
                        val w = weight
                        if (w != null) {
                            viewModel.complete(
                                weightKg = w,
                                measurement = if (measured) form.entity() else null,
                                heightCm = if (measured) form.newHeight() else null,
                                hunger = if (withRatings) hunger else null,
                                energy = if (withRatings) energy else null,
                                sleep = if (withRatings) sleep else null
                            )
                        }
                    }
                    SheetActions(
                        secondaryText = "Пропустить",
                        onSecondary = { finish(false) },
                        primaryText = "Подвести итог",
                        onPrimary = { finish(true) },
                        primaryEnabled = weight != null
                    )
                }
            }
        }
    }
}

/** Оценка 1–5: пять кружков, выбранный — в цвете бренда. Повторный тап снимает выбор. */
@Composable
private fun RatingRow(label: String, hint: String, value: Int?, onChange: (Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (n in 1..5) {
                val active = value == n
                Surface(
                    onClick = { onChange(if (active) null else n) },
                    shape = CircleShape,
                    color = if (active) scheme.primary else scheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .semantics { contentDescription = "$label: $n из 5" }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$n",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (active) scheme.onPrimary else scheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ---------- Итоги чек-ина ----------

/** Экран итогов: неделя по фактам, сигнал мышц, новый план и одно действие. */
@Composable
fun CheckInResultContent(c: CheckInEntity) {
    val palette = macroPalette()
    val primary = MaterialTheme.colorScheme.primary
    val periodText = if (c.periodDays == 7) "за неделю" else "за ${c.periodDays} ${plural(c.periodDays, "день", "дня", "дней")}"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Смена этапа программы (3.24) — первым и заметно: это главное, что поменялось.
        c.phaseChange?.let { PhaseChangeBlock(it) }
        // Вес — по тренду: скачки воды не должны решать, удалась ли неделя.
        TonalSection(color = MaterialTheme.colorScheme.secondary) {
            TinyLabel("Вес по тренду", color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DisplayNumber(BodyComposition.fmt1(c.trendWeight ?: c.weight), size = 30, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(
                    "кг · сегодня на весах ${BodyComposition.fmt1(c.weight)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            val change = c.trendChangeKg
            val trend = c.trendWeight
            Text(
                if (change != null && trend != null) {
                    val startWeight = trend - change
                    val pct = if (startWeight > 0) change / startWeight * 100 else 0.0
                    "$periodText ${signed(change)} кг (${signed(pct)}%)"
                } else "Изменение появится, когда будет пара взвешиваний за неделю",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            val fat = c.fatPercent
            val fatChange = c.fatChange
            if (fat != null) {
                Text(
                    "Жир ${BodyComposition.fmt1(fat)}%" + (fatChange?.let { " (${signed(it)} п.п. с прошлого замера)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalTile("${c.diaryDays} из ${c.periodDays}", "дней с едой", palette.carbs, Modifier.weight(1f))
            TonalTile("${c.goodDays}", "хороших дней", palette.protein, Modifier.weight(1f))
            TonalTile("${c.workouts} из ${c.workoutGoal}", "силовых", palette.fat, Modifier.weight(1f))
        }
        val avg = c.avgScore
        if (avg != null) {
            Text(
                "Средняя оценка дня — ${avg.roundToInt()} из 100",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            if (avg != null) {
                "Хороший день — оценка от 70: калории, белок, движение и сон. Дни до версии 3.23 — по калориям и белку."
            } else {
                "Хороший день — калории от −10% до +5% плана и белок от 90% нормы."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Сигнал сохранения мышц — без данных силы, по трём простым признакам.
        val sColor = signalColor(c.muscleSignal)
        TonalSection(color = sColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalDot(c.muscleSignal)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Мышцы: ${signalLabel(c.muscleSignal)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(c.signalNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }

        StrengthSection(c)

        // Новый план: было → стало и почему.
        FreshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TinyLabel(if (c.phaseChange != null) "План на новый этап" else "План на неделю", color = primary)
                val after = c.caloriesAfter
                val before = c.caloriesBefore
                Row(verticalAlignment = Alignment.Bottom) {
                    if (before != null && after != null && abs(before - after) >= 10) {
                        Text(
                            "${before.roundToInt()} →",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    DisplayNumber("${(after ?: 0.0).roundToInt()}", size = 30, color = primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ккал",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    macroLine("Б", c.proteinBefore, c.proteinAfter) + " · " +
                        macroLine("Ж", c.fatBefore, c.fatAfter) + " · " +
                        macroLine("У", c.carbsBefore, c.carbsAfter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (c.note.isNotBlank()) {
                    Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (c.nextAction.isNotBlank()) {
            TonalSection(color = primary) {
                TinyLabel("Главное на неделю", color = primary)
                Spacer(Modifier.height(2.dp))
                Text(
                    c.nextAction,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        val ratings = listOfNotNull(
            c.hunger?.let { "голод $it" },
            c.energy?.let { "энергия $it" },
            c.sleep?.let { "сон $it" }
        )
        if (ratings.isNotEmpty()) {
            Text(
                "Самочувствие: " + ratings.joinToString(" · ") + " из 5",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Сила (3.27) ----------

/**
 * Загрузка тренировок перед чек-ином: экспорт Hevy (Профиль → Настройки → Экспорт данных →
 * «Экспорт тренировок»). Можно и без кнопки — «Поделиться» файлом прямо в NutriLens.
 */
@Composable
private fun TrainingImportRow(importedAt: LocalDate?, message: String?, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SoftButton(
            if (importedAt == null) "Загрузить тренировки из Hevy" else "Обновить тренировки",
            onClick = onPick,
            icon = Icons.Rounded.FitnessCenter,
            height = 44,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            message ?: if (importedAt == null) {
                "Необязательно. Экспорт тренировок из Hevy покажет, держится ли сила, — главный признак, что уходит жир, а не мышцы"
            } else {
                "Последняя загрузка — ${importedAt.format(CHECKIN_DATE)}. Перед чек-ином загрузи свежий экспорт"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
    }
}

/** Сила за неделю: сигнал, одна фраза, до трёх упражнений и максимум одна подсказка. */
@Composable
private fun StrengthSection(c: CheckInEntity) {
    val note = c.strengthNote ?: return
    val scheme = MaterialTheme.colorScheme
    val signal = c.strengthSignal
    val lifts = StrengthCheck.decode(c.strengthLifts)
    TonalSection(color = if (signal != null) signalColor(signal) else scheme.outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (signal != null) {
                SignalDot(signal)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "Сила" + (c.strengthIndex?.let { " · ${StrengthAnalyzer.formatPercent(it)}" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        lifts.forEach { l ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    l.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(liftLine(l), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
        }
        c.strengthTip?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** «60 × 5 → 62,5 × 5 ↑» — стрелка по изменению: держится в пределах 3% — «=». */
private fun liftLine(l: StrengthCheck.Lift): String {
    val sets = listOfNotNull(l.before, l.now).distinct().joinToString(" → ")
    val arrow = when {
        l.changePercent == null -> ""
        l.changePercent > 3 -> " ↑"
        l.changePercent < -3 -> " ↓"
        else -> " ="
    }
    return sets + arrow
}

/** «Б 150» или «Б 145 → 150». */
private fun macroLine(label: String, before: Double?, after: Double?): String {
    val a = (after ?: 0.0).roundToInt()
    val b = before?.roundToInt()
    return if (b != null && b != a) "$label $b → $a г" else "$label $a г"
}

// ---------- Прогресс: история «Чек-ины» ----------

/** Список чек-инов в Прогрессе: дата, тренд веса, жир, сигнал и калории. Тап — итоги того дня. */
@Composable
fun CheckInHistorySection(modifier: Modifier = Modifier, viewModel: CheckInViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<CheckInEntity?>(null) }
    if (history.isEmpty() && settings.targetFatPercent == null) return

    Column(modifier) {
        FreshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.Card)) {
                CardHeader(
                    title = "Чек-ины",
                    icon = Icons.Rounded.EventAvailable,
                    tint = MaterialTheme.colorScheme.primary,
                    subtitle = nextCheckInText(settings)?.takeIf { settings.checkInsActive }?.let { "Следующий — $it" } ?: "Итоги каждой недели"
                )
                if (history.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Здесь появятся итоги недель: тренд веса, жир, сигнал мышц и план.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    history.take(12).forEachIndexed { i, c ->
                        if (i > 0) Hairline()
                        CheckInHistoryRow(c, onClick = { opened = c })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    opened?.let { c ->
        NutriSheet(
            onDismiss = { opened = null },
            title = "Итоги недели",
            subtitle = "Чек-ин ${runCatching { LocalDate.parse(c.date).format(CHECKIN_DATE) }.getOrDefault(c.date)}"
        ) {
            CheckInResultContent(c)
            Spacer(Modifier.height(20.dp))
            SoftButton("Закрыть", onClick = { opened = null }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CheckInHistoryRow(c: CheckInEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(vertical = 8.dp)
        ) {
            SignalDot(c.muscleSignal, size = 10)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    runCatching { LocalDate.parse(c.date).format(CHECKIN_DATE) }.getOrDefault(c.date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${BodyComposition.fmt1(c.trendWeight ?: c.weight)} кг" +
                        (c.fatPercent?.let { " · жир ${BodyComposition.fmt1(it)}%" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${(c.caloriesAfter ?: 0.0).roundToInt()} ккал",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
