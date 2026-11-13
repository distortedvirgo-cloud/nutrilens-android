package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.BodyComposition
import com.nutrilens.app.data.BodyMeasurementEntity
import com.nutrilens.app.data.BodyMeasurementRepository
import com.nutrilens.app.data.DataEvents
import com.nutrilens.app.data.FatBurnPlanSync
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.WeightEntity
import com.nutrilens.app.data.fatBurnProgramState
import com.nutrilens.app.data.fatTrendNow
import com.nutrilens.app.data.fatTrendPoints
import com.nutrilens.app.data.programMinTarget
import com.nutrilens.app.data.programWarnTarget
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.FatBurnProgram
import com.nutrilens.app.insights.Trend
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Программа режима «Сжигание жира» (3.24): предупреждение перед включением,
 * мастер запуска из четырёх шагов, карточка этапа и разовые карточки на «Сегодня»,
 * итог программы, «Путь программы» в Прогрессе и пауза в Профиле.
 * Логика этапов — insights/FatBurnProgram, сохранение — FatBurnPlanSync.
 */

private val PROGRAM_DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU"))
private val PROGRAM_SHORT = DateTimeFormatter.ofPattern("d MMM", Locale("ru", "RU"))

/** Цвета этапов на шкале — свои, не из темы: блок всегда оранжевый, перерыв зелёный, удержание серое. */
private val BLOCK_COLOR = Color(0xFFF07A2A)
private val BREAK_COLOR = Color(0xFF12A174)

@Composable
private fun phaseColor(type: FatBurnProgram.PhaseType): Color = when (type) {
    FatBurnProgram.PhaseType.BLOCK -> BLOCK_COLOR
    FatBurnProgram.PhaseType.BREAK -> BREAK_COLOR
    FatBurnProgram.PhaseType.MAINTENANCE -> MaterialTheme.colorScheme.outline
}

private fun parseKg(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..300.0 }

/** Свой опыт человека для предупреждения — только для информации, ничего не решает. */
data class ProgramWarningContext(
    val lostKg: Double?,
    val lostPercent: Double?,
    val since: LocalDate?,
    val diaryDays: Int?
)

/** Итог завершённой программы. */
data class ProgramSummary(
    val startFat: Double,
    val fatNow: Double?,
    val weeks: Int,
    val fatKgLost: Double?,
    val avgScore: Double?
)

class FatBurnProgramViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)
    private val settingsRepo = SettingsRepository(db.settingsDao())

    val settings: StateFlow<SettingsEntity> = settingsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    val measurements: StateFlow<List<BodyMeasurementEntity>> = BodyMeasurementRepository(db.bodyMeasurementDao()).observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _warning = MutableStateFlow<ProgramWarningContext?>(null)
    val warning: StateFlow<ProgramWarningContext?> = _warning.asStateFlow()

    /** Последний вес, если он не старше 3 дней: тогда в мастере его можно не вводить. */
    private val _recentWeight = MutableStateFlow<WeightEntity?>(null)
    val recentWeight: StateFlow<WeightEntity?> = _recentWeight.asStateFlow()

    private val _preview = MutableStateFlow<FatBurnPlanSync.LaunchPreview?>(null)
    val preview: StateFlow<FatBurnPlanSync.LaunchPreview?> = _preview.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _summary = MutableStateFlow<ProgramSummary?>(null)
    val summary: StateFlow<ProgramSummary?> = _summary.asStateFlow()

    /** «Ты уже сбросил N кг (X%) с …» по тренду веса или «ведёшь дневник N дней». */
    fun loadWarningContext() {
        viewModelScope.launch {
            _warning.value = try {
                val trend = Trend.smooth(
                    db.weightDao().all().mapNotNull { w ->
                        runCatching { FatBurnPlanner.DayValue(LocalDate.parse(w.date), w.weight) }.getOrNull()
                    }
                )
                val first = trend.firstOrNull()
                val last = trend.lastOrNull()
                val lost = if (first != null && last != null && trend.size >= 2) first.trend - last.trend else null
                val firstMeal = db.mealDao().allDates().minOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val diaryDays = firstMeal?.let { ChronoUnit.DAYS.between(it, LocalDate.now()).toInt() + 1 }
                ProgramWarningContext(
                    lostKg = lost?.takeIf { it >= 0.5 },
                    lostPercent = if (lost != null && first != null && first.trend > 0) lost / first.trend * 100 else null,
                    since = first?.date,
                    diaryDays = diaryDays
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Перед мастером: свежий вес (не старше 3 дней) и чистое превью. */
    fun prepareLaunch() {
        _preview.value = null
        _error.value = null
        viewModelScope.launch {
            val latest = db.weightDao().latest()
            val fresh = latest?.let { w ->
                runCatching { ChronoUnit.DAYS.between(LocalDate.parse(w.date), LocalDate.now()) }.getOrNull()
            }?.let { it in 0L..3L } ?: false
            _recentWeight.value = if (fresh) latest else null
        }
    }

    fun loadPreview(target: Double, weightKg: Double?, fatPercent: Double?) {
        viewModelScope.launch {
            _preview.value = FatBurnPlanSync.launchPreview(getApplication(), target, weightKg, fatPercent)
        }
    }

    fun start(
        target: Double,
        weightKg: Double?,
        measurement: BodyMeasurementEntity?,
        heightCm: Double?,
        onDone: () -> Unit
    ) {
        if (_starting.value) return
        viewModelScope.launch {
            _starting.value = true
            _error.value = null
            try {
                val ok = FatBurnPlanSync.startProgram(getApplication(), target, weightKg, measurement, heightCm)
                if (ok) onDone() else _error.value = "Нужен вес — без него план не посчитать"
            } catch (e: Exception) {
                _error.value = "Не получилось начать программу: ${e.message ?: "попробуй ещё раз"}"
            }
            _starting.value = false
        }
    }

    fun setSex(value: String) = updateSettings { it.copy(sex = value) }

    fun setCheckInWeekday(value: Int) = updateSettings { it.copy(checkInWeekday = value.coerceIn(1, 7)) }

    fun setWorkoutGoal(value: Int) = updateSettings {
        it.copy(workoutGoal = value.coerceIn(CheckInPlanner.MIN_WORKOUT_GOAL, CheckInPlanner.MAX_WORKOUT_GOAL))
    }

    fun pause() {
        viewModelScope.launch { FatBurnPlanSync.pauseProgram(getApplication()) }
    }

    fun resume() {
        viewModelScope.launch { FatBurnPlanSync.resumeProgram(getApplication()) }
    }

    fun stop() {
        viewModelScope.launch { FatBurnPlanSync.stopProgram(getApplication()) }
    }

    fun stayOnMaintenance() {
        viewModelScope.launch { FatBurnPlanSync.stayOnMaintenance(getApplication()) }
    }

    fun dismissNotice() {
        viewModelScope.launch { FatBurnPlanSync.dismissNotice(getApplication()) }
    }

    /** Итог: старт → сейчас, недели, кг жира и средняя оценка дня за программу. */
    fun loadSummary() {
        viewModelScope.launch {
            _summary.value = try {
                val program = settingsRepo.get().fatBurnProgramState
                if (program == null) null else {
                    val fatNow = fatTrendNow(db.bodyMeasurementDao().all())
                    val weightNow = db.weightDao().latest()?.weight
                    val end = program.phases.lastOrNull { it.endedDate != null }?.endedDate ?: LocalDate.now()
                    val weeks = (ChronoUnit.DAYS.between(program.startDate, end) / 7.0).roundToInt().coerceAtLeast(1)
                    val lost = if (fatNow != null && weightNow != null) {
                        ((program.startWeight * program.startFat / 100 - weightNow * fatNow / 100) * 10).roundToInt() / 10.0
                    } else null
                    val scores = db.checkInDao().all()
                        .filter { it.date >= program.startDate.toString() }
                        .mapNotNull { it.avgScore }
                    ProgramSummary(
                        startFat = program.startFat,
                        fatNow = fatNow,
                        weeks = weeks,
                        fatKgLost = lost,
                        avgScore = if (scores.isEmpty()) null else scores.average()
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun updateSettings(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            DataEvents.notifyChanged()
        }
    }
}

// ---------- Профиль: предупреждение перед включением ----------

/**
 * «Сжигание жира — продвинутый режим»: заметка, а не запрет. Три вопроса о безопасности
 * обязательны: любое «да» — режим не включаем и спокойно советуем врача.
 */
@Composable
fun ProgramWarningSheet(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    viewModel: FatBurnProgramViewModel = viewModel()
) {
    val context by viewModel.warning.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadWarningContext() }
    var under18 by remember { mutableStateOf<Boolean?>(null) }
    var pregnancy by remember { mutableStateOf<Boolean?>(null) }
    var eatingDisorder by remember { mutableStateOf<Boolean?>(null) }
    val answers = listOf(under18, pregnancy, eatingDisorder)
    val blocked = answers.any { it == true }
    val allAnswered = answers.all { it != null }

    NutriSheet(onDismiss = onDismiss, title = "Сжигание жира — продвинутый режим") {
        if (blocked) {
            TonalSection(color = MaterialTheme.colorScheme.secondary) {
                Text(
                    "Сейчас этот режим не для тебя",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "В такой ситуации долгий дефицит калорий лучше обсудить с врачом — он подскажет безопасный путь. " +
                        "Дневник, обычное похудение и все подсказки остаются с тобой.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(20.dp))
            GlowButton("Понятно", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        } else {
            WarningBody(context)
            Spacer(Modifier.height(16.dp))
            Text("Пара вопросов о здоровье", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            SafetyQuestion("Тебе меньше 18 лет?", under18) { under18 = it }
            SafetyQuestion("Беременность или кормление грудью?", pregnancy) { pregnancy = it }
            SafetyQuestion("Расстройство пищевого поведения сейчас или в прошлом?", eatingDisorder) { eatingDisorder = it }
            Spacer(Modifier.height(20.dp))
            SheetActions(
                secondaryText = "Пока не надо",
                onSecondary = onDismiss,
                primaryText = "Понятно, включить",
                onPrimary = onContinue,
                primaryEnabled = allAnswered
            )
        }
    }
}

/** Текст предупреждения и свой опыт человека (если есть). */
@Composable
private fun WarningBody(ctx: ProgramWarningContext?) {
    Column {
        Text(
            "Он для тех, кто уже сбросил 5–10% веса или давно ведёт дневник и видит, что это работает. " +
                "Если ты только начинаешь — начни с обычного похудения: так легче и безопаснее.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Это долгая программа на несколько месяцев: блоки дефицита, обязательные перерывы и чек-ин раз в неделю.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val lostKg = ctx?.lostKg
        val lostPercent = ctx?.lostPercent
        val since = ctx?.since
        val diaryDays = ctx?.diaryDays
        val contextLine = when {
            lostKg != null && since != null -> "Ты уже сбросил ${BodyComposition.fmt1(lostKg)} кг" +
                (lostPercent?.let { " (${BodyComposition.fmt1(it)}%)" } ?: "") + " с ${since.format(PROGRAM_DAY_MONTH)}"
            diaryDays != null && diaryDays > 1 -> "Ты ведёшь дневник $diaryDays ${plural(diaryDays, "день", "дня", "дней")}"
            else -> null
        }
        if (contextLine != null) {
            Spacer(Modifier.height(12.dp))
            TonalSection(color = MaterialTheme.colorScheme.primary) {
                Text(contextLine, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Вопрос «да / нет»: ответ обязателен, по умолчанию ничего не выбрано. */
@Composable
private fun SafetyQuestion(text: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        NutriChip(text = "Нет", onClick = { onChange(false) }, selected = value == false)
        Spacer(Modifier.width(6.dp))
        NutriChip(text = "Да", onClick = { onChange(true) }, selected = value == true)
    }
}

// ---------- Мастер запуска ----------

/**
 * Запуск программы в четыре шага: замер и вес → цель → ритм → план программы.
 * Ничего не сохраняется до «Начать программу» (кроме настроек ритма и пола — они
 * и так живут в Профиле): закрыл лист — режим не включился.
 */
@Composable
fun ProgramLaunchSheet(onDismiss: () -> Unit, viewModel: FatBurnProgramViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val recentWeight by viewModel.recentWeight.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.prepareLaunch() }

    val today = LocalDate.now()
    var step by remember { mutableStateOf(0) }
    var weightText by remember { mutableStateOf("") }
    val todayMeasurement = measurements.lastOrNull { it.date == today.toString() }
    val form = rememberMeasurementFormState(settings, todayMeasurement)
    val male = BodyComposition.isMale(settings)
    val floor = programMinTarget(male)
    var target by remember { mutableStateOf<Int?>(null) }

    // Замер за последние 7 дней — подойдёт и без нового.
    val recentMeasurement = measurements.lastOrNull { m ->
        runCatching { ChronoUnit.DAYS.between(LocalDate.parse(m.date), today) in 0L..7L }.getOrDefault(false)
    }
    val enteredWeight = parseKg(weightText)
    val weightOk = enteredWeight != null || recentWeight != null
    val formFat = form.result
    val measurementOk = formFat != null || recentMeasurement != null
    val currentFat = formFat ?: recentMeasurement?.fatPercent
    val defaultTarget = (if (male) 15 else 23).coerceAtLeast(floor)
    val chosenTarget = (target ?: defaultTarget).coerceIn(floor, BodyComposition.MAX_TARGET)

    val titles = listOf("Текущий замер", "Цель", "Ритм", "План программы")
    NutriSheet(onDismiss = onDismiss, title = titles[step.coerceIn(0, 3)], subtitle = "Шаг ${step + 1} из 4") {
        when (step) {
            0 -> {
                SegmentedSwitcher(
                    options = listOf(true to "Мужчина", false to "Женщина"),
                    selected = male,
                    onSelect = { viewModel.setSex(if (it) "male" else "female") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                val rw = recentWeight
                NutriTextField(
                    value = weightText,
                    onValueChange = { v -> weightText = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
                    label = "Вес",
                    suffix = "кг",
                    placeholder = rw?.let { BodyComposition.fmt1(it.weight) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (rw != null) "Вес ${BodyComposition.fmt1(rw.weight)} кг уже записан ${daysAgo(rw.date)} — можно не вводить."
                    else "Нужен свежий вес: лучше утром, до еды.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (recentMeasurement != null) {
                    TonalSection(color = MaterialTheme.colorScheme.primary) {
                        Text(
                            "Замер ${daysAgo(recentMeasurement.date)}: ${BodyComposition.fmt1(recentMeasurement.fatPercent)}% — подойдёт",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Можно обновить ниже — или сразу дальше.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                BodyMeasurementForm(form)
                Spacer(Modifier.height(20.dp))
                SheetActions(
                    secondaryText = "Отмена",
                    onSecondary = onDismiss,
                    primaryText = "Дальше",
                    onPrimary = { step = 1 },
                    primaryEnabled = weightOk && measurementOk
                )
            }
            1 -> {
                if (currentFat != null) {
                    Text(
                        "Сейчас ≈ ${BodyComposition.fmt1(currentFat)}% жира",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Целевой процент", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Рельеф: мужчинам 10–15%, женщинам 18–24%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PercentStepper(value = chosenTarget, onChange = { target = it }, min = floor)
                }
                if (chosenTarget < programWarnTarget(male)) {
                    Spacer(Modifier.height(10.dp))
                    TonalSection(color = NutriTheme.colors.warnTo) {
                        Text(
                            "Это очень сухая форма: держать её трудно, и не всем она полезна. Ниже $floor% цель поставить нельзя.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (currentFat != null && currentFat - chosenTarget <= FatBurnProgram.TARGET_TOLERANCE) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Ты уже у этой цели — программа начнётся сразу с удержания.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
                SheetActions(secondaryText = "Назад", onSecondary = { step = 0 }, primaryText = "Дальше", onPrimary = { step = 2 })
            }
            2 -> {
                CheckInSettingsBlock(
                    weekday = settings.checkInWeekday,
                    workoutGoal = settings.workoutGoal,
                    onWeekday = viewModel::setCheckInWeekday,
                    onWorkoutGoal = viewModel::setWorkoutGoal
                )
                Spacer(Modifier.height(14.dp))
                StepsSettingsSection()
                Spacer(Modifier.height(20.dp))
                SheetActions(secondaryText = "Назад", onSecondary = { step = 1 }, primaryText = "Дальше", onPrimary = { step = 3 })
            }
            else -> {
                val weightForPlan = enteredWeight ?: recentWeight?.weight
                LaunchedEffect(chosenTarget, weightForPlan, currentFat) {
                    viewModel.loadPreview(chosenTarget.toDouble(), weightForPlan, currentFat)
                }
                val p = preview
                if (p == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Собираю программу…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    LaunchPlanContent(p)
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                if (starting) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Запускаю…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    SheetActions(
                        secondaryText = "Назад",
                        onSecondary = { step = 2 },
                        primaryText = "Начать программу",
                        onPrimary = {
                            viewModel.start(
                                target = chosenTarget.toDouble(),
                                weightKg = enteredWeight,
                                measurement = form.entity(),
                                heightCm = if (form.result != null) form.newHeight() else null,
                                onDone = onDismiss
                            )
                        },
                        primaryEnabled = p != null && weightForPlan != null
                    )
                }
            }
        }
    }
}

/** «сегодня» / «вчера» / «3 дня назад». */
private fun daysAgo(date: String): String {
    val days = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now()).toInt() }.getOrNull() ?: return ""
    return when (days) {
        0 -> "сегодня"
        1 -> "вчера"
        else -> "$days ${plural(days, "день", "дня", "дней")} назад"
    }
}

/** Последний шаг мастера: старт → цель, этапы, вилка дат и план на сегодня. */
@Composable
private fun LaunchPlanContent(p: FatBurnPlanSync.LaunchPreview) {
    val palette = macroPalette()
    val program = p.program
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayNumber("${BodyComposition.fmt1(program.startFat)}% → ${BodyComposition.fmt1(program.target)}%", size = 30, color = MaterialTheme.colorScheme.primary)
        }
        val f = p.forecast
        Text(
            if (f != null) "Цель — примерно ${FatBurnProgram.rangeText(f)}. Точнее станет по ходу: план подстраивается на чек-инах."
            else "Ты уже у цели — начнём с удержания.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        FreshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TinyLabel("Этапы", color = MaterialTheme.colorScheme.primary)
                program.phases.forEach { phase -> PhaseLine(program, phase) }
            }
        }
        Text(
            "Перерывы обязательны: 2 недели на уровне расхода помогают телу и голове, после них дефицит снова работает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TinyLabel("План на сегодня", color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayNumber("${p.plan.calories.roundToInt()}", size = 30, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                "ккал в день",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalTile("${p.plan.protein.roundToInt()} г", "белки", palette.protein, Modifier.weight(1f))
            TonalTile("${p.plan.fat.roundToInt()} г", "жиры", palette.fat, Modifier.weight(1f))
            TonalTile("${p.plan.carbs.roundToInt()} г", "углеводы", palette.carbs, Modifier.weight(1f))
        }
    }
}

/** Строка этапа: цветная точка, название и длина. */
@Composable
private fun PhaseLine(program: FatBurnProgram.Program, phase: FatBurnProgram.Phase, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(10.dp)
                .background(phaseColor(phase.type), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        val weeks = phase.plannedWeeks
        val text = FatBurnProgram.phaseTitle(program, phase) + " · $weeks ${plural(weeks, "неделя", "недели", "недель")}" +
            when (phase.type) {
                FatBurnProgram.PhaseType.BLOCK -> phase.targetFat?.let { " · до ${BodyComposition.fmt1(it)}%" } ?: ""
                FatBurnProgram.PhaseType.BREAK -> " · на уровне расхода"
                FatBurnProgram.PhaseType.MAINTENANCE -> " · закрепляем"
            }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Профиль: пауза ----------

/** Пауза в секции режима: «Приостановить программу» / «Продолжить». */
@Composable
fun ProgramPauseBlock(settings: SettingsEntity, onPause: () -> Unit, onResume: () -> Unit) {
    val program = remember(settings.fatBurnProgram, settings.targetFatPercent) { settings.fatBurnProgramState } ?: return
    if (program.awaitingChoice) return
    val since = program.pausedSince
    if (since != null) {
        TonalSection(color = MaterialTheme.colorScheme.secondary) {
            Text(
                "Программа на паузе с ${since.format(PROGRAM_DAY_MONTH)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "План — на уровне расхода, чек-инов и напоминаний нет. Дни паузы в этапы не идут.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            GlowButton("Продолжить", onClick = onResume, icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth())
        }
    } else {
        SoftButton("Приостановить программу", onClick = onPause, icon = Icons.Rounded.Pause, modifier = Modifier.fillMaxWidth())
        Text(
            "Отпуск, болезнь или просто нужна передышка — план станет на уровне расхода, этап подождёт.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- «Сегодня» ----------

/** Карточка этапа: где ты в программе и что делать сейчас — одной строкой. */
@Composable
fun ProgramPhaseCard(settings: SettingsEntity, modifier: Modifier = Modifier) {
    val program = remember(settings.fatBurnProgram, settings.targetFatPercent) { settings.fatBurnProgramState } ?: return
    if (program.awaitingChoice) return
    val phase = program.currentPhase ?: return
    val today = LocalDate.now()
    val kcal = settings.planCalories
    val tdee = settings.planTdee
    val deficit = if (kcal != null && tdee != null) (((tdee - kcal) / 10).roundToInt() * 10) else null
    val hint = FatBurnProgram.phaseHint(program, deficit, settings.planProtein?.roundToInt(), kcal?.roundToInt())
    val days = FatBurnProgram.daysInPhase(program, today)
    val plannedDays = if (phase.type == FatBurnProgram.PhaseType.BREAK) FatBurnProgram.BREAK_DAYS else phase.plannedWeeks * 7
    val color = if (program.paused) MaterialTheme.colorScheme.outline else phaseColor(phase.type)
    val title = when {
        program.paused -> "Программа на паузе"
        program.completed -> "Удержание"
        phase.type == FatBurnProgram.PhaseType.BLOCK ->
            "Блок ${FatBurnProgram.blockOrdinal(program, phase)} из ${program.blockCount} · неделя ${FatBurnProgram.weekOfPhase(program, today)} из ${phase.plannedWeeks}"
        phase.type == FatBurnProgram.PhaseType.BREAK -> {
            val left = FatBurnProgram.breakDaysLeft(program, today).coerceAtLeast(1)
            "Перерыв · ещё $left ${plural(left, "день", "дня", "дней")}"
        }
        else -> "Удержание · неделя ${FatBurnProgram.weekOfPhase(program, today)} из ${phase.plannedWeeks}"
    }
    val subtitle = when {
        program.paused -> "Этап подождёт — продолжить можно в Профиле"
        program.completed -> "Программа пройдена — держим результат"
        phase.type == FatBurnProgram.PhaseType.BLOCK -> "Цель блока ${BodyComposition.fmt1(phase.targetFat ?: program.target)}%"
        phase.type == FatBurnProgram.PhaseType.BREAK -> "Обязательная часть программы"
        else -> "Закрепляем результат"
    }
    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = title,
                icon = if (phase.type == FatBurnProgram.PhaseType.BLOCK && !program.paused) Icons.Rounded.LocalFireDepartment else Icons.Rounded.Spa,
                tint = color,
                subtitle = subtitle
            )
            Text(hint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (!program.paused && !program.completed) {
                ProgressTrack(
                    fraction = (days.toFloat() / plannedDays.coerceAtLeast(1)).coerceIn(0f, 1f),
                    brush = androidx.compose.ui.graphics.SolidColor(color),
                    track = color.copy(alpha = tintAlpha() + 0.06f),
                    modifier = Modifier.semantics { contentDescription = "Прошло $days из $plannedDays дней этапа" }
                )
            }
        }
    }
}

/** Разовая карточка: «Режим стал программой» или смена этапа при открытии. */
@Composable
fun ProgramNoticeCard(notice: FatBurnProgram.Notice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    FreshCard(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(title = notice.title, icon = Icons.Rounded.Timeline, tint = MaterialTheme.colorScheme.primary)
            if (notice.text.isNotBlank()) {
                Text(notice.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            SoftButton("Понятно", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** «Программа завершена»: итог и выбор — остаться на удержании или выключить режим. */
@Composable
fun ProgramCompletedCard(viewModel: FatBurnProgramViewModel, modifier: Modifier = Modifier) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadSummary() }
    val palette = macroPalette()
    FreshCard(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(
                title = "Программа завершена",
                icon = Icons.Rounded.EmojiEvents,
                tint = MaterialTheme.colorScheme.primary,
                subtitle = "Блоки, перерывы и удержание позади — это большая работа"
            )
            val s = summary
            if (s != null) {
                val now = s.fatNow
                Text(
                    "Жир: ${BodyComposition.fmt1(s.startFat)}% → " + (now?.let { "${BodyComposition.fmt1(it)}%" } ?: "—"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalTile("${s.weeks}", plural(s.weeks, "неделя", "недели", "недель"), palette.carbs, Modifier.weight(1f))
                    val lost = s.fatKgLost
                    TonalTile(if (lost != null && lost > 0) "${BodyComposition.fmt1(lost)} кг" else "—", "жира ушло", palette.fat, Modifier.weight(1f))
                    val avg = s.avgScore
                    TonalTile(avg?.let { "${it.roundToInt()}" } ?: "—", "оценка дня", palette.protein, Modifier.weight(1f))
                }
            }
            Text(
                "Что дальше? На удержании калории остаются на уровне расхода, чек-ины продолжаются. Или выключи режим — вернутся твои обычные цели.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlowButton("Остаться на удержании", onClick = viewModel::stayOnMaintenance, modifier = Modifier.fillMaxWidth())
            SoftButton("Выключить режим", onClick = viewModel::stop, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------- Прогресс: «Путь программы» ----------

/**
 * Шкала этапов (блоки оранжевые, перерывы зелёные, удержание серое) с отметкой
 * «сегодня», тренд процента жира под ней и вилка дат до цели.
 */
@Composable
fun ProgramTimelineSection(modifier: Modifier = Modifier, viewModel: FatBurnProgramViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val program = remember(settings.fatBurnProgram, settings.targetFatPercent) { settings.fatBurnProgramState } ?: return
    val today = LocalDate.now()
    val segments = remember(program, today) { FatBurnProgram.timeline(program, today) }
    if (segments.isEmpty()) return
    val fraction = FatBurnProgram.todayFraction(segments, today)
    val fatPoints = remember(measurements, program.startDate) {
        fatTrendPoints(measurements).filter { !it.date.isBefore(program.startDate) }
    }
    val forecast = FatBurnProgram.forecast(program, today)
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(modifier) {
        FreshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeader(
                    title = "Путь программы",
                    icon = Icons.Rounded.Timeline,
                    tint = MaterialTheme.colorScheme.primary,
                    subtitle = "${BodyComposition.fmt1(program.startFat)}% → ${BodyComposition.fmt1(program.target)}% · с ${program.startDate.format(PROGRAM_DAY_MONTH)}"
                )
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .semantics { contentDescription = "Шкала этапов программы, сегодня — отметка" }
                ) {
                    val width = maxWidth
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(Radius.Pill)),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        segments.forEach { seg ->
                            val base = phaseColor(seg.phase.type)
                            Box(
                                Modifier
                                    .weight(seg.days.toFloat())
                                    .fillMaxHeight()
                                    .background(if (seg.state == FatBurnProgram.SegmentState.FUTURE) base.copy(alpha = 0.35f) else base)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .offset(x = (width * fraction - 2.dp).coerceAtMost(width - 4.dp).coerceAtLeast(0.dp))
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(onSurface, RoundedCornerShape(2.dp))
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendItem(BLOCK_COLOR, "блок")
                    LegendItem(BREAK_COLOR, "перерыв")
                    LegendItem(MaterialTheme.colorScheme.outline, "удержание")
                }
                Text(
                    when {
                        program.completed -> "Программа пройдена"
                        forecast != null -> "Цель — примерно ${FatBurnProgram.rangeText(forecast)}"
                        else -> "Цель достигнута — идёт удержание"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (fatPoints.size >= 2) {
                    Text("Тренд процента жира", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val shown = fatPoints.takeLast(30)
                    TrendLineChart(shown.map { it.trend }, BLOCK_COLOR, unit = "%", raw = shown.map { it.raw })
                } else {
                    Text(
                        "Тренд жира появится после пары замеров — замер на чек-ине раз в неделю.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Hairline()
                segments.forEach { seg ->
                    val dates = "${seg.start.format(PROGRAM_SHORT)} – ${seg.end.format(PROGRAM_SHORT)}"
                    val state = when (seg.state) {
                        FatBurnProgram.SegmentState.DONE -> "пройден · $dates"
                        FatBurnProgram.SegmentState.CURRENT -> if (program.paused) "пауза" else "сейчас"
                        FatBurnProgram.SegmentState.FUTURE -> dates
                    }
                    PhaseLine(program, seg.phase, trailing = state)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Итоги чек-ина ----------

/** Заметный блок смены этапа на экране итогов: «Блок 1 завершён → 2 недели перерыва». */
@Composable
fun PhaseChangeBlock(text: String) {
    val headline = text.substringBefore(". ")
    val rest = text.substringAfter(". ", "")
    TonalSection(color = MaterialTheme.colorScheme.primary) {
        TinyLabel("Новый этап программы", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        if (rest.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(rest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
