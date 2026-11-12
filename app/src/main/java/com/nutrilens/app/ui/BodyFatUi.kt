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
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsEntity
import com.nutrilens.app.data.SettingsRepository
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

    init {
        viewModelScope.launch { _lastWeight.value = db.weightDao().latest()?.weight }
        viewModelScope.launch {
            DataEvents.changed.collect { _lastWeight.value = db.weightDao().latest()?.weight }
        }
    }

    fun setTarget(value: Double?) = updateSettings {
        it.copy(targetFatPercent = value?.coerceIn(BodyComposition.MIN_TARGET.toDouble(), BodyComposition.MAX_TARGET.toDouble()))
    }

    fun setSex(value: String) = updateSettings { it.copy(sex = value) }

    fun setHeight(value: Double) = updateSettings { it.copy(heightCm = value) }

    /** Замер за сегодня; рост из листа (если его не было в профиле) сохраняем заодно. */
    fun save(entity: BodyMeasurementEntity, heightCm: Double?) {
        viewModelScope.launch {
            if (heightCm != null) settingsRepo.update { it.copy(heightCm = heightCm) }
            repo.save(entity)
            com.nutrilens.app.widget.WidgetUpdater.refresh(getApplication())
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
    val male = BodyComposition.isMale(settings)
    val knownHeight = BodyComposition.heightCm(settings)
    fun fmt(v: Double?) = v?.let { BodyComposition.fmt1(it) }.orEmpty()
    var waist by remember { mutableStateOf(fmt(today?.waistCm)) }
    var neck by remember { mutableStateOf(fmt(today?.neckCm)) }
    var hips by remember { mutableStateOf(fmt(today?.hipsCm)) }
    var manual by remember { mutableStateOf(fmt(today?.manualFatPercent)) }
    var height by remember { mutableStateOf("") }

    val heightCm = knownHeight ?: parseNum(height)?.takeIf { it in 100.0..250.0 }
    val waistN = parseNum(waist)
    val neckN = parseNum(neck)
    val hipsN = if (male) null else parseNum(hips)
    val manualN = parseNum(manual)?.takeIf { it in 3.0..60.0 }
    val formula = BodyComposition.navyFatPercent(male, heightCm, waistN, neckN, hipsN)
    val result = manualN ?: formula
    val palette = macroPalette()

    NutriSheet(
        onDismiss = onDismiss,
        title = "Замер тела",
        subtitle = if (today != null) "Сегодняшний замер уже есть — сохранишь, и он обновится"
        else "Пара обхватов — и посчитаем процент жира"
    ) {
        if (knownHeight == null) {
            MeasureField(height, { height = numFilter(it) }, "Рост", "см")
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MeasureField(waist, { waist = numFilter(it) }, "Талия", "см", Modifier.weight(1f))
            MeasureField(neck, { neck = numFilter(it) }, "Шея", "см", Modifier.weight(1f))
        }
        if (!male) {
            Spacer(Modifier.height(10.dp))
            MeasureField(hips, { hips = numFilter(it) }, "Бёдра", "см")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Талия — на уровне пупка, шея — под кадыком" + (if (male) "" else ", бёдра — по самой широкой части") +
                ". Лучше утром натощак, лента без натяжения.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        MeasureField(manual, { manual = numFilter(it) }, "Свой процент (весы, калипер, DEXA)", "%")
        Spacer(Modifier.height(4.dp))
        Text(
            "Необязательно. Если указан — используем его вместо формулы.",
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
                    when {
                        manualN != null && formula != null -> "Твой процент. По обхватам выходит ${BodyComposition.fmt1(formula)}%"
                        manualN != null -> "Твой процент"
                        else -> "По формуле ВМС США"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    when {
                        heightCm == null -> "Укажи рост — он нужен формуле"
                        waistN != null && neckN != null && waistN <= neckN -> "Проверь цифры: талия должна быть больше шеи"
                        else -> if (male) "Заполни талию и шею" else "Заполни талию, шею и бёдра"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        SheetActions(
            secondaryText = "Отмена",
            onSecondary = onDismiss,
            primaryText = "Сохранить",
            onPrimary = {
                result?.let { fat ->
                    onSave(
                        BodyMeasurementEntity(
                            date = LocalDate.now().toString(),
                            waistCm = waistN,
                            neckCm = neckN,
                            hipsCm = hipsN,
                            manualFatPercent = manualN,
                            fatPercent = (fat * 10).roundToInt() / 10.0
                        ),
                        if (knownHeight == null) heightCm else null
                    )
                }
                onDismiss()
            },
            primaryEnabled = result != null
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
fun BodyFatGoalSection(modifier: Modifier = Modifier, viewModel: BodyFatViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val lastWeight by viewModel.lastWeight.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val palette = macroPalette()
    val target = settings.targetFatPercent
    val latest = measurements.lastOrNull()

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                title = "Цель по жиру",
                icon = Icons.Rounded.MonitorWeight,
                tint = palette.protein,
                subtitle = if (target != null) "Цель ${BodyComposition.fmt1(target)}% · мышцы сохраняем" else "Выключено",
                trailing = {
                    NutriSwitch(
                        checked = target != null,
                        onCheckedChange = { on -> viewModel.setTarget(if (on) BodyComposition.DEFAULT_TARGET else null) }
                    )
                }
            )
            Text(
                "Следим не только за весом, а за процентом жира: белок считается от сухой массы, прогресс — по замерам талии и шеи.",
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
                        onChange = { viewModel.setTarget(it.toDouble()) }
                    )
                }
                val male = BodyComposition.isMale(settings)
                SegmentedSwitcher(
                    options = listOf(true to "Мужчина", false to "Женщина"),
                    selected = male,
                    onSelect = { viewModel.setSex(if (it) "male" else "female") },
                    modifier = Modifier.fillMaxWidth()
                )
                HeightField(settings, onValid = viewModel::setHeight)
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

/** Степпер «− 15% +» в диапазоне цели. */
@Composable
private fun PercentStepper(value: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.Pill))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = { onChange(value - 1) },
            enabled = value > BodyComposition.MIN_TARGET,
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
            onClick = { onChange(value + 1) },
            enabled = value < BodyComposition.MAX_TARGET,
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
    Column {
        val fat = latest.fatPercent
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DisplayNumber("${BodyComposition.fmt1(fat)}%", size = 36, color = palette.protein)
            Spacer(Modifier.width(8.dp))
            Text(
                "жира" + (target?.let { " · цель ${BodyComposition.fmt1(it)}%" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
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
            TrendLineChart(measurements.takeLast(30).map { it.fatPercent }, palette.protein, unit = "%")
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
