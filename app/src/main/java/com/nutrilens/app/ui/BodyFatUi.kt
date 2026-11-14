package com.nutrilens.app.ui

import android.app.Application
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.nutrilens.app.data.programMinTarget
import com.nutrilens.app.data.programWarnTarget
import com.nutrilens.app.ui.theme.NutriTheme
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.insights.FatBurnPlanner
import com.nutrilens.app.insights.Trend
import com.nutrilens.app.ui.theme.macroPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * Цель по проценту жира: секция в Профиле, лист замера, карточка «Состав тела»
 * в Прогрессе и акцент на белке на «Сегодня». Формулы — в BodyComposition.
 */

class BodyFatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)
    private val settingsRepo = SettingsRepository(db.settingsDao())
    private val repo = BodyMeasurementRepository(db.bodyMeasurementDao())

    val settings: StateFlow<SettingsEntity> = settingsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    /** Все замеры по возрастанию даты — для графика и стартовой точки цели. */
    val measurements: StateFlow<List<BodyMeasurementEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastWeight = MutableStateFlow<Double?>(null)
    val lastWeight: StateFlow<Double?> = _lastWeight.asStateFlow()

    /** Сглаженный тренд веса за 90 дней — для темпа в карточке плана. */
    private val _weightTrend = MutableStateFlow<List<Trend.Point>>(emptyList())
    val weightTrend: StateFlow<List<Trend.Point>> = _weightTrend.asStateFlow()

    /**
     * Живой расчёт плана для подробной карточки: темп и чего не хватает для точности.
     * Сами цифры плана берутся из settings — они меняются только при пересчёте.
     */
    private val _planPreview = MutableStateFlow<FatBurnPlanner.Plan?>(null)
    val planPreview: StateFlow<FatBurnPlanner.Plan?> = _planPreview.asStateFlow()

    init {
        viewModelScope.launch {
            _lastWeight.value = db.weightDao().latest()?.weight
            reloadTrend()
        }
        viewModelScope.launch {
            DataEvents.changed.collect {
                _lastWeight.value = db.weightDao().latest()?.weight
                reloadTrend()
                refreshPlan()
            }
        }
        // Режим или цель поменялись (в т.ч. с другого экрана) — пересчёт и свежее превью.
        viewModelScope.launch {
            settingsRepo.observe()
                .map { it.targetFatPercent }
                .distinctUntilChanged()
                .collect { refreshPlan() }
        }
    }

    private suspend fun reloadTrend() {
        val since = LocalDate.now().minusDays(90).toString()
        _weightTrend.value = Trend.smooth(
            db.weightDao().since(since).mapNotNull { w ->
                runCatching { FatBurnPlanner.DayValue(LocalDate.parse(w.date), w.weight) }.getOrNull()
            }
        )
    }

    private suspend fun refreshPlan() {
        FatBurnPlanSync.ensureFresh(getApplication())
        _planPreview.value = FatBurnPlanSync.preview(getApplication())
    }

    fun setTarget(value: Double?) = updateSettings {
        it.copy(targetFatPercent = value?.coerceIn(BodyComposition.MIN_TARGET.toDouble(), BodyComposition.MAX_TARGET.toDouble()))
    }

    // Включение режима с 3.24 — только через предупреждение и мастер запуска
    // (FatBurnProgramViewModel.start), выключение — через подтверждение (stop):
    // так у программы всегда есть старт, этапы и первый план.

    fun setSex(value: String) = updateSettings { it.copy(sex = value) }

    fun setHeight(value: Double) = updateSettings { it.copy(heightCm = value) }

    /** День недельного чек-ина, ISO: 1 — понедельник … 7 — воскресенье. */
    fun setCheckInWeekday(value: Int) = updateSettings { it.copy(checkInWeekday = value.coerceIn(1, 7)) }

    /** Темп программы (3.28): план сразу пересчитывается под новый темп. */
    fun setPace(key: String) {
        viewModelScope.launch { FatBurnPlanSync.setPace(getApplication(), key) }
    }

    /** Цель силовых в неделю (2–5) — для сигнала сохранения мышц на чек-ине. */
    fun setWorkoutGoal(value: Int) = updateSettings {
        it.copy(workoutGoal = value.coerceIn(CheckInPlanner.MIN_WORKOUT_GOAL, CheckInPlanner.MAX_WORKOUT_GOAL))
    }

    /** Замер за сегодня; рост из листа (если его не было в профиле) сохраняем заодно. */
    fun save(entity: BodyMeasurementEntity, heightCm: Double?) {
        viewModelScope.launch {
            if (heightCm != null) settingsRepo.update { it.copy(heightCm = heightCm) }
            repo.save(entity)
            com.nutrilens.app.widget.WidgetUpdater.refresh(getApplication())
            // Замер — новый процент жира; план пересчитается, только если ещё не был посчитан.
            refreshPlan()
        }
    }

    // Цель белка зависит от настроек — дашборд и виджеты должны пересчитаться.
    private fun updateSettings(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            DataEvents.notifyChanged()
            com.nutrilens.app.widget.WidgetUpdater.refresh(getApplication())
        }
    }
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale("ru", "RU"))

private fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

private fun numFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }.take(5)

private fun daysAgoText(date: String): String {
    val days = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now()).toInt() }.getOrNull() ?: return ""
    return when (days) {
        0 -> "сегодня"
        1 -> "вчера"
        else -> "$days ${plural(days, "день", "дня", "дней")} назад"
    }
}

// ---------- Лист замера ----------

/**
 * Состояние формы замера — вынесено из листа, чтобы ту же форму переиспользовал
 * недельный чек-ин (шаг «Замер лентой»). Процент считается вживую.
 */
class MeasurementFormState(settings: SettingsEntity, today: BodyMeasurementEntity?) {
    /** Обновляется при каждой композиции: пол и рост могли поменяться в профиле. */
    var settings by mutableStateOf(settings)
    var waist by mutableStateOf(fmtCm(today?.waistCm))
    var neck by mutableStateOf(fmtCm(today?.neckCm))
    var hips by mutableStateOf(fmtCm(today?.hipsCm))
    var manual by mutableStateOf(fmtCm(today?.manualFatPercent))
    var height by mutableStateOf("")

    val male: Boolean get() = BodyComposition.isMale(settings)
    val knownHeight: Double? get() = BodyComposition.heightCm(settings)
    val heightCm: Double? get() = knownHeight ?: parseNum(height)?.takeIf { it in 100.0..250.0 }
    val waistN: Double? get() = parseNum(waist)
    val neckN: Double? get() = parseNum(neck)
    val hipsN: Double? get() = if (male) null else parseNum(hips)
    val manualN: Double? get() = parseNum(manual)?.takeIf { it in 3.0..60.0 }
    val formula: Double? get() = BodyComposition.navyFatPercent(male, heightCm, waistN, neckN, hipsN)
    /** Обхваты заполнены и правдоподобны — замер можно сохранить. */
    val tapeOk: Boolean get() = formula != null

    /**
     * Процент жира (3.31) — только по виду: старт выбран по таблице фото, лента считает
     * изменения. Без оценки по виду процента нет — формула по ленте у многих сильно ошибается
     * в абсолютной цифре, и показывать её значит вводить в заблуждение.
     */
    val result: Double? get() = settings.fatOffset?.let { com.nutrilens.app.data.FatCalibration.calibrated(formula, it) }

    /** Замер за сегодня; null — обхваты не заполнены. Без оценки по виду процент = 0 («неизвестно»). */
    fun entity(): BodyMeasurementEntity? {
        if (!tapeOk) return null
        val fat = result ?: 0.0
        return BodyMeasurementEntity(
            date = LocalDate.now().toString(),
            waistCm = waistN,
            neckCm = neckN,
            hipsCm = hipsN,
            manualFatPercent = null,
            fatPercent = (fat * 10).roundToInt() / 10.0
        )
    }

    /** Рост из формы — только если в профиле его не было (тогда сохраним заодно). */
    fun newHeight(): Double? = if (knownHeight == null) heightCm else null
}

private fun fmtCm(v: Double?): String = v?.let { BodyComposition.fmt1(it) }.orEmpty()

@Composable
fun rememberMeasurementFormState(settings: SettingsEntity, today: BodyMeasurementEntity?): MeasurementFormState {
    val state = remember { MeasurementFormState(settings, today) }
    state.settings = settings
    return state
}

/** Поля замера и живой результат — общие для листа замера и чек-ина. */
@Composable
fun BodyMeasurementForm(state: MeasurementFormState) {
    val male = state.male
    val heightCm = state.heightCm
    val waistN = state.waistN
    val neckN = state.neckN
    val result = state.result
    val palette = macroPalette()

    Column {
        if (state.knownHeight == null) {
            MeasureField(state.height, { state.height = numFilter(it) }, "Рост", "см")
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MeasureField(state.waist, { state.waist = numFilter(it) }, "Талия", "см", Modifier.weight(1f))
            MeasureField(state.neck, { state.neck = numFilter(it) }, "Шея", "см", Modifier.weight(1f))
        }
        if (!male) {
            Spacer(Modifier.height(10.dp))
            MeasureField(state.hips, { state.hips = numFilter(it) }, "Бёдра", "см")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Талия — на уровне пупка, шея — под кадыком" + (if (male) "" else ", бёдра — по самой широкой части") +
                ". Лучше утром натощак, лента без натяжения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        TonalSection(color = palette.protein) {
            TinyLabel("Процент жира", color = palette.protein)
            Spacer(Modifier.height(2.dp))
            if (result != null) {
                Text(
                    "≈ ${BodyComposition.fmt1(result)}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Старт — по виду, изменения — по ленте",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    when {
                        state.tapeOk -> "Процент жира оцениваешь по виду — по таблице фото. Лента покажет, как он меняется"
                        heightCm == null -> "Укажи рост — он нужен, чтобы считать изменения"
                        waistN != null && neckN != null && waistN <= neckN -> "Проверь цифры: талия должна быть больше шеи"
                        else -> if (male) "Заполни талию и шею" else "Заполни талию, шею и бёдра"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Лист замера: талия, шея, бёдра (только женщинам), свой процент. Процент
 * считается вживую. Повторный замер за сегодня перезаписывает прежний.
 */
@Composable
fun BodyMeasurementSheet(
    settings: SettingsEntity,
    today: BodyMeasurementEntity?,
    onDismiss: () -> Unit,
    onSave: (BodyMeasurementEntity, Double?) -> Unit
) {
    val form = rememberMeasurementFormState(settings, today)

    NutriSheet(
        onDismiss = onDismiss,
        title = "Замер тела",
        subtitle = if (today != null) "Сегодняшний замер уже есть — сохранишь, и он обновится"
        else "Пара обхватов — и посчитаем процент жира"
    ) {
        BodyMeasurementForm(form)
        Spacer(Modifier.height(20.dp))
        SheetActions(
            secondaryText = "Отмена",
            onSecondary = onDismiss,
            primaryText = "Сохранить",
            onPrimary = {
                form.entity()?.let { onSave(it, form.newHeight()) }
                onDismiss()
            },
            primaryEnabled = form.tapeOk
        )
    }
}

@Composable
private fun MeasureField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    NutriTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        suffix = suffix,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

// ---------- Профиль: «Цель по жиру» ----------

/** Секция Профиля: включить цель, выбрать процент, пол и рост для формулы, первый замер. */
@Composable
fun BodyFatGoalSection(
    modifier: Modifier = Modifier,
    viewModel: BodyFatViewModel = viewModel(),
    programViewModel: FatBurnProgramViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    // 3.24: включение — предупреждение → мастер запуска; выключение — подтверждение.
    var showWarning by remember { mutableStateOf(false) }
    var showLaunch by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    val palette = macroPalette()
    val target = settings.targetFatPercent
    val latest = measurements.lastOrNull()

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = "Цель",
                icon = Icons.Rounded.MonitorWeight,
                tint = if (target != null) MaterialTheme.colorScheme.primary else palette.protein,
                subtitle = if (target != null) "Сжигание жира · цель ${BodyComposition.fmt1(target)}% · мышцы сохраняем"
                else "Похудение · свои калории и БЖУ"
            )
            // Режим — явный выбор из двух: так понятно, что это другой способ вести дневник,
            // а не мелкая галочка. Тема приложения переключается сразу же.
            SegmentedSwitcher(
                options = listOf(false to "Похудение", true to "🔥 Сжигание жира"),
                selected = target != null,
                onSelect = { on ->
                    if (on && target == null) showWarning = true
                    else if (!on && target != null) showStopConfirm = true
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (target != null) {
                    "Следим за процентом жира, а не только за весом: калории и БЖУ считаются сами и подстраиваются под твой вес и еду."
                } else {
                    "Сам задаёшь калории и БЖУ в «Цели и макросы». Хочешь рельеф без потери мышц — включи «Сжигание жира»."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (target != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Целевой процент", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Рельеф: мужчинам 10–15%, женщинам 18–24%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PercentStepper(
                        value = target.roundToInt(),
                        onChange = { viewModel.setTarget(it.toDouble()) },
                        min = programMinTarget(BodyComposition.isMale(settings))
                    )
                }
                if (target.roundToInt() < programWarnTarget(BodyComposition.isMale(settings))) {
                    Text(
                        "Очень сухая цель: держать её трудно, и не всем она полезна.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ProgramPauseBlock(settings, onPause = programViewModel::pause, onResume = programViewModel::resume)
                val male = BodyComposition.isMale(settings)
                SegmentedSwitcher(
                    options = listOf(true to "Мужчина", false to "Женщина"),
                    selected = male,
                    onSelect = { viewModel.setSex(if (it) "male" else "female") },
                    modifier = Modifier.fillMaxWidth()
                )
                HeightField(settings, onValid = viewModel::setHeight)
                CheckInSettingsBlock(
                    weekday = settings.checkInWeekday,
                    workoutGoal = settings.workoutGoal,
                    onWeekday = viewModel::setCheckInWeekday,
                    onWorkoutGoal = viewModel::setWorkoutGoal,
                    pace = settings.fatBurnPace,
                    male = male,
                    onPace = viewModel::setPace
                )
                BodyComposition.proteinTarget(lastWeight, latest?.fatPercent)?.let { protein ->
                    Text(
                        "Белок: ${protein.roundToInt()} г в день" +
                            if (latest == null) " (2 г на кг веса, после замера — точнее)" else " (2,6 г на кг сухой массы)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.protein
                    )
                }
                if (latest == null) {
                    TonalSection(color = MaterialTheme.colorScheme.primary) {
                        Text("Нужен первый замер", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Измерь талию и шею сантиметровой лентой — займёт минуту.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        GlowButton("Добавить первый замер", onClick = { showSheet = true }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (latest != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Последний замер: ${BodyComposition.fmt1(latest.fatPercent)}% · ${daysAgoText(latest.date)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    NutriChip(text = "Замер", onClick = { showSheet = true }, icon = Icons.Rounded.Add)
                }
            }
        }
    }

    if (showSheet) {
        BodyMeasurementSheet(
            settings = settings,
            today = measurements.lastOrNull { it.date == LocalDate.now().toString() },
            onDismiss = { showSheet = false },
            onSave = viewModel::save
        )
    }
    if (showWarning) {
        ProgramWarningSheet(
            onDismiss = { showWarning = false },
            onContinue = {
                showWarning = false
                showLaunch = true
            },
            viewModel = programViewModel
        )
    }
    if (showLaunch) {
        ProgramLaunchSheet(onDismiss = { showLaunch = false }, viewModel = programViewModel)
    }
    if (showStopConfirm) {
        NutriDialog(
            onDismiss = { showStopConfirm = false },
            title = "Выключить режим?",
            subtitle = "Программа закончится: этапы и перерывы сбросятся, снова заработают твои калории и БЖУ. " +
                "Замеры, чек-ины и дневник останутся.",
            confirmText = "Выключить",
            destructive = true,
            onConfirm = {
                showStopConfirm = false
                programViewModel.stop()
            }
        )
    }
}

/**
 * Настройки недельного чек-ина в секции режима: день недели (по умолчанию воскресенье)
 * и цель силовых в неделю (2–5). Выбираются сразу при включении режима, менять можно когда угодно.
 */
@Composable
fun CheckInSettingsBlock(
    weekday: Int,
    workoutGoal: Int,
    onWeekday: (Int) -> Unit,
    onWorkoutGoal: (Int) -> Unit,
    /** Темп (3.28): ключ из настроек (null — по умолчанию для пола); onPace null — не показываем. */
    pace: String? = null,
    male: Boolean = true,
    onPace: ((String) -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onPace != null) {
            val chosen = FatBurnPlanner.Pace.of(pace) ?: FatBurnPlanner.Pace.default(male)
            Text("Темп", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when (chosen) {
                    FatBurnPlanner.Pace.GENTLE -> "0,4% веса в неделю — легче держать, мышцам спокойнее. Хорошо, если жира немного или тяжело с голодом."
                    FatBurnPlanner.Pace.NORMAL -> "0,6% веса в неделю — баланс скорости и сохранения мышц."
                    FatBurnPlanner.Pace.FAST -> "0,8% веса в неделю — быстрее, но голоднее. Подходит, если жира много и силовые идут регулярно."
                } + " Ближе к цели темп сам снизится.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SegmentedSwitcher(
                options = FatBurnPlanner.Pace.entries.map { it.key to it.title },
                selected = chosen.key,
                onSelect = onPace,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(2.dp))
        }
        Text("День чек-ина", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Раз в неделю подводим итоги за 2 минуты — только тогда план и меняется.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedSwitcher(
            options = listOf(1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс"),
            selected = weekday.coerceIn(1, 7),
            onSelect = onWeekday,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
        Text("Силовых в неделю", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Силовые — главный сигнал телу сохранить мышцы в дефиците.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedSwitcher(
            options = (CheckInPlanner.MIN_WORKOUT_GOAL..CheckInPlanner.MAX_WORKOUT_GOAL).map { it to "$it" },
            selected = workoutGoal.coerceIn(CheckInPlanner.MIN_WORKOUT_GOAL, CheckInPlanner.MAX_WORKOUT_GOAL),
            onSelect = onWorkoutGoal,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Рост для формулы: сохраняется, как только введено правдоподобное число. */
@Composable
private fun HeightField(settings: SettingsEntity, onValid: (Double) -> Unit) {
    val saved = BodyComposition.heightCm(settings)
    var text by remember(saved) { mutableStateOf(saved?.roundToInt()?.toString().orEmpty()) }
    NutriTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(3)
            text.toDoubleOrNull()?.takeIf { it in 100.0..250.0 }?.let(onValid)
        },
        label = "Рост",
        suffix = "см",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Степпер «− 15% +» в диапазоне цели; [min] — нижняя граница (3.24: 10% мужчинам, 18% женщинам). */
@Composable
fun PercentStepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int = BodyComposition.MIN_TARGET,
    max: Int = BodyComposition.MAX_TARGET
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.Pill))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = value > min,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Remove, "Меньше", Modifier.size(18.dp)) }
        }
        Text(
            "$value%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Surface(
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = value < max,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Больше", Modifier.size(18.dp)) }
        }
    }
}

// ---------- Прогресс: «Состав тела» ----------

/**
 * Карточка «Состав тела» для Прогресса. Видна, когда цель включена или замеры
 * уже есть; сама держит лист замера.
 */
@Composable
fun BodyCompositionSection(modifier: Modifier = Modifier, viewModel: BodyFatViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    if (settings.targetFatPercent == null && measurements.isEmpty()) return

    Column(modifier) {
        BodyCompositionCard(settings, measurements, lastWeight, onAdd = { showSheet = true })
        Spacer(Modifier.height(16.dp))
    }

    if (showSheet) {
        BodyMeasurementSheet(
            settings = settings,
            today = measurements.lastOrNull { it.date == LocalDate.now().toString() },
            onDismiss = { showSheet = false },
            onSave = viewModel::save
        )
    }
}

@Composable
private fun BodyCompositionCard(
    settings: SettingsEntity,
    measurements: List<BodyMeasurementEntity>,
    weight: Double?,
    onAdd: () -> Unit
) {
    val palette = macroPalette()
    val latest = measurements.lastOrNull()

    FreshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card)) {
            CardHeader(
                title = "Состав тела",
                icon = Icons.Rounded.MonitorWeight,
                tint = palette.protein,
                subtitle = latest?.let { "Замер ${LocalDate.parse(it.date).format(DAY_MONTH)}" } ?: "Замеров пока нет"
            )
            if (latest == null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Добавь первый замер талии и шеи — посчитаем процент жира, жировую и сухую массу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                GlowButton("Добавить замер", onClick = onAdd, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
            } else {
                BodyCompositionDetails(settings, measurements, latest, weight, onAdd)
            }
        }
    }
}

@Composable
private fun BodyCompositionDetails(
    settings: SettingsEntity,
    measurements: List<BodyMeasurementEntity>,
    latest: BodyMeasurementEntity,
    weight: Double?,
    onAdd: () -> Unit
) {
    val palette = macroPalette()
    val c = NutriTheme.colors
    val target = settings.targetFatPercent
    // Главное число — сглаженный тренд: один замер лентой гуляет на ±1%, тренд — нет.
    val fatTrend = remember(measurements) {
        Trend.smoothByCount(
            measurements.mapNotNull { m ->
                runCatching { FatBurnPlanner.DayValue(LocalDate.parse(m.date), m.fatPercent) }.getOrNull()
            }
        )
    }
    // 3.31: без оценки по виду процента нет — только динамика обхватов и предложение оценить.
    if (settings.fatOffset == null || fatTrend.isEmpty()) {
        UncalibratedBodyDetails(settings, measurements, latest, onAdd)
        return
    }
    Column {
        val fat = fatTrend.lastOrNull()?.trend ?: latest.fatPercent
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayNumber("${BodyComposition.fmt1(fat)}%", size = 36, color = palette.protein)
            Spacer(Modifier.width(8.dp))
            Text(
                "жира, тренд" + (target?.let { " · цель ${BodyComposition.fmt1(it)}%" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (measurements.size >= 2) {
            Text(
                "Последний замер ${BodyComposition.fmt1(latest.fatPercent)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (weight != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalTile("${BodyComposition.fmt1(BodyComposition.fatMass(weight, fat))} кг", "жир", palette.fat, Modifier.weight(1f))
                TonalTile("${BodyComposition.fmt1(BodyComposition.leanMass(weight, fat))} кг", "сухая масса", palette.protein, Modifier.weight(1f))
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Запиши вес — покажем, сколько в нём жира и мышц.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (target != null) {
            val start = measurements.first().fatPercent
            val progress = BodyComposition.goalProgress(start, fat, target)
            Spacer(Modifier.height(16.dp))
            Row {
                Text(
                    "Путь к цели",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.protein
                )
            }
            Spacer(Modifier.height(6.dp))
            ProgressTrack(
                fraction = progress,
                brush = SolidColor(palette.protein),
                track = palette.protein.copy(alpha = tintAlpha() + 0.06f),
                modifier = Modifier.semantics {
                    contentDescription = "Прогресс к цели: ${(progress * 100).roundToInt()} процентов"
                }
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    "старт ${BodyComposition.fmt1(start)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "цель ${BodyComposition.fmt1(target)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (weight != null) {
                val toLose = BodyComposition.fatToLose(weight, fat, target)
                val weeks = BodyComposition.etaWeeks(weight, toLose)
                Spacer(Modifier.height(12.dp))
                TonalSection(color = palette.protein) {
                    if (weeks == null) {
                        Text(
                            "Цель достигнута — держи белок и силовые, чтобы закрепить",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            "При сохранении мышц ≈ ${BodyComposition.targetWeight(weight, fat, target).roundToInt()} кг",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Сбросить ≈ ${BodyComposition.fmt1(toLose)} кг жира · ${BodyComposition.etaText(weeks)} в спокойном темпе",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (measurements.size >= 2) {
            Spacer(Modifier.height(16.dp))
            Text("Тренд процента жира", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            val shown = fatTrend.takeLast(30)
            TrendLineChart(shown.map { it.trend }, palette.protein, unit = "%", raw = shown.map { it.raw })
        }

        val daysSince = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(latest.date), LocalDate.now()) }.getOrDefault(0L)
        if (daysSince > 7) {
            Spacer(Modifier.height(12.dp))
            TonalSection(color = c.warnTo) {
                Text(
                    "С последнего замера прошло $daysSince ${plural(daysSince.toInt(), "день", "дня", "дней")} — пора обновить, чтобы видеть тренд",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Домашние методы ошибаются на ±3–4% — важнее тренд за несколько недель, чем одно число. Меряйся в одинаковых условиях.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        SoftButton("Добавить замер", onClick = onAdd, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        LookCalibrationButton(settings)
    }
}

/** Динамика обхватов, пока процент не оценён по виду: талия сейчас и с первого замера. */
@Composable
private fun UncalibratedBodyDetails(
    settings: SettingsEntity,
    measurements: List<BodyMeasurementEntity>,
    latest: BodyMeasurementEntity,
    onAdd: () -> Unit
) {
    val palette = macroPalette()
    val waists = measurements.filter { (it.waistCm ?: 0.0) > 0 }
    Column {
        Spacer(Modifier.height(14.dp))
        val now = latest.waistCm
        if (now != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                DisplayNumber("${BodyComposition.fmt1(now)} см", size = 32, color = palette.protein)
                Spacer(Modifier.width(8.dp))
                Text("талия", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            val first = waists.firstOrNull()?.waistCm
            if (first != null && waists.size >= 2) {
                val d = now - first
                Text(
                    "${if (d > 0) "+" else if (d < 0) "−" else ""}${BodyComposition.fmt1(kotlin.math.abs(d))} см с первого замера",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Процент жира оцениваешь по виду — по таблице фото. Формулы по ленте в абсолютной цифре часто ошибаются, " +
                "поэтому лента здесь — для динамики: талия уходит — значит, уходит жир.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        LookCalibrationButton(settings, primary = true)
        Spacer(Modifier.height(8.dp))
        SoftButton("Добавить замер", onClick = onAdd, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * «Уточнить процент по виду» (3.30): таблица фото → поправка к формуле по ленте.
 * В идущей программе этапы и план пересобираются от нового процента.
 */
@Composable
fun LookCalibrationButton(settings: SettingsEntity, primary: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var look by remember { mutableStateOf<Double?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val label = if (settings.fatOffset == null) "Оценить процент по виду" else "Уточнить процент по виду"
    val onOpen = { look = null; message = null; open = true }
    if (primary) GlowButton(label, onClick = onOpen, modifier = Modifier.fillMaxWidth())
    else SoftButton(label, onClick = onOpen, modifier = Modifier.fillMaxWidth())
    if (open) {
        NutriSheet(onDismiss = { open = false }, title = "Процент по виду", subtitle = "Старт — по фото, изменения — по ленте") {
            FatLookPicker(male = BodyComposition.isMale(settings), value = look, onChange = { look = it })
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            SheetActions(
                secondaryText = "Отмена",
                onSecondary = { open = false },
                primaryText = if (saving) "Сохраняю…" else "Сохранить",
                onPrimary = {
                    val v = look ?: return@SheetActions
                    saving = true
                    scope.launch {
                        val ok = com.nutrilens.app.data.FatBurnPlanSync.recalibrate(context, v)
                        saving = false
                        if (ok) open = false else message = "Нужен хотя бы один замер лентой (талия и шея)"
                    }
                },
                primaryEnabled = look != null && !saving
            )
        }
    }
}

// ---------- «Сегодня»: белок как вторая главная цифра ----------

/**
 * Крупная строка белка под кольцом калорий, когда включена цель по жиру:
 * в дефиците именно белок решает, уйдёт жир или мышцы.
 */
@Composable
fun ProteinFocus(eaten: Double, goal: Double, modifier: Modifier = Modifier) {
    val color = macroPalette().protein
    val left = (goal - eaten).roundToInt()
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Белок: ${eaten.roundToInt()} из ${goal.roundToInt()} грамм"
            }
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                TinyLabel("Белок · цель по жиру", color = color)
                Text(
                    "${eaten.roundToInt()} / ${goal.roundToInt()} г",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                if (left > 0) "ещё $left г" else "норма есть",
                style = MaterialTheme.typography.labelLarge,
                color = color,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressTrack(
            fraction = if (goal > 0) (eaten / goal).toFloat() else 0f,
            brush = SolidColor(color),
            track = color.copy(alpha = tintAlpha() + 0.06f),
            height = 10.dp
        )
    }
}
