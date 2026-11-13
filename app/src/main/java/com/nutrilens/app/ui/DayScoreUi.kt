package com.nutrilens.app.ui

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutrilens.app.data.CheckInEntity
import com.nutrilens.app.data.DataEvents
import com.nutrilens.app.data.NutriLensDatabase
import com.nutrilens.app.data.SettingsRepository
import com.nutrilens.app.data.checkInReference
import com.nutrilens.app.data.scoreDays
import com.nutrilens.app.data.scorePlan
import com.nutrilens.app.insights.CheckInPlanner
import com.nutrilens.app.insights.DayScore
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Оценка дня режима «Сжигание жира» (3.23): блок «Оценка дня» на «Сегодня»
 * (кольцо, четыре части и вопрос про сон), цвета дней для полосы недели и
 * «Недели программы» в Прогрессе. Формула — insights/DayScore.
 */

/** Оценка дня для полосы недели: число и уровень (цвет). */
data class WeekDayScore(val score: Int, val level: DayScore.Level)

/** Цвет уровня дня. Сегодняшний день не закончен — низкий уровень красим брендом, а не «плохо». */
@Composable
fun scoreLevelColor(level: DayScore.Level, unfinished: Boolean = false): Color {
    val c = NutriTheme.colors
    return when (level) {
        DayScore.Level.GOOD -> c.success
        DayScore.Level.MEDIUM -> if (unfinished) c.brandFrom else Color(0xFFE0A100)
        DayScore.Level.LOW -> if (unfinished) c.brandFrom else c.dangerFrom.copy(alpha = 0.75f)
        DayScore.Level.NONE -> MaterialTheme.colorScheme.outline
    }
}

private fun ceilInt(v: Double): Int = ceil(v).toInt().coerceAtLeast(0)

// ---------- «Сегодня»: оценка дня ----------

/**
 * Компактный блок «Оценка дня»: кольцо с числом и четыре части — что сделано и
 * сколько осталось. Сегодня — «пока N», день не называем плохим. Для сегодняшнего
 * дня тут же вопрос «Поспал 8 часов?» и отметка силовой.
 */
@Composable
fun DayScoreCard(
    day: DayScore.Day,
    planCalories: Double,
    planProtein: Double,
    stepGoal: Int,
    stepsEnabled: Boolean,
    isToday: Boolean,
    onSleep: (Boolean?) -> Unit,
    onToggleWorkout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val r = DayScore.compute(day, planCalories, planProtein, stepGoal)
    val total = r.total
    // День до 3.23 (без записи активности), хороший по старому правилу, не «портим» задним числом.
    val level = if (!day.hasRecord && DayScore.legacyGood(day.kcal, day.protein, planCalories, planProtein)) {
        DayScore.Level.GOOD
    } else {
        DayScore.level(total)
    }
    val ringColor = scoreLevelColor(level, unfinished = isToday)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val fraction by animateFloatAsState(total / 100f, tween(Motion.ExtraLong, easing = Motion.Emphasized), label = "scoreRing")

    val headline = when {
        isToday -> "Пока $total из 100"
        level == DayScore.Level.GOOD -> "Хороший день · $total"
        level == DayScore.Level.MEDIUM -> "Неплохой день · $total"
        else -> "Оценка $total из 100"
    }
    val hint = when {
        isToday && r.good -> "Уже хороший день — так держать"
        isToday -> "Хороший день — от 70. Ещё есть время добрать"
        else -> "Калории, белок, движение и сон"
    }

    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Оценка дня: $total из 100"
                }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 7.dp.toPx()
                        val inset = stroke / 2f
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                        if (fraction > 0f) {
                            drawArc(
                                ringColor, -90f, 360f * fraction, false, Offset(inset, inset), arc,
                                style = Stroke(stroke, cap = StrokeCap.Round)
                            )
                        }
                    }
                    DisplayNumber("$total", size = 22, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    TinyLabel("Оценка дня", color = MaterialTheme.colorScheme.primary)
                    Text(headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Четыре части: что сделано и конкретно сколько осталось.
            val kcalText = when {
                day.kcal <= 0.0 -> "ещё нет записей"
                r.calories >= DayScore.MAX_CALORIES - 0.01 -> "в коридоре плана"
                day.kcal < planCalories -> "до коридора ещё ${ceilInt(planCalories * 0.9 - day.kcal).coerceAtLeast(1)} ккал"
                else -> "выше коридора на ${ceilInt(day.kcal - planCalories * 1.05).coerceAtLeast(1)} ккал"
            }
            val proteinText = if (r.protein >= DayScore.MAX_PROTEIN - 0.01) "норма есть"
            else "ещё ${ceilInt(planProtein * 0.9 - day.protein).coerceAtLeast(1)} г белка"
            val steps = day.steps
            val movementText = when {
                day.workout -> "силовая есть"
                steps != null && steps >= stepGoal -> "цель шагов есть"
                steps != null -> "ещё ${fmtSteps(stepGoal - steps)} шагов"
                stepsEnabled -> "ещё ${fmtSteps(stepGoal)} шагов"
                else -> "силовая или шаги"
            }
            val sleepText = when (day.sleptWell) {
                true -> "8 часов есть"
                false -> "в эту ночь меньше"
                null -> if (isToday) "отметь ниже" else "нет отметки"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScorePartChip("Калории", kcalText, DayScore.partState(r.calories, DayScore.MAX_CALORIES), Modifier.weight(1f))
                ScorePartChip("Белок", proteinText, DayScore.partState(r.protein, DayScore.MAX_PROTEIN), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScorePartChip("Движение", movementText, DayScore.partState(r.movement, DayScore.MAX_MOVEMENT), Modifier.weight(1f))
                ScorePartChip("Сон", sleepText, DayScore.partState(r.sleep, DayScore.MAX_SLEEP), Modifier.weight(1f))
            }

            if (isToday) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Поспал 8 часов?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // Повторный тап снимает ответ — можно передумать в течение дня.
                    NutriChip(
                        text = "Да",
                        selected = day.sleptWell == true,
                        onClick = { onSleep(if (day.sleptWell == true) null else true) }
                    )
                    Spacer(Modifier.width(8.dp))
                    NutriChip(
                        text = "Нет",
                        selected = day.sleptWell == false,
                        onClick = { onSleep(if (day.sleptWell == false) null else false) }
                    )
                }
                CheckRow(checked = day.workout, onToggle = onToggleWorkout) {
                    Text(
                        "Силовая тренировка сегодня",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Часть оценки: подпись, состояние (сделано / частично / ещё нет) и что осталось. */
@Composable
private fun ScorePartChip(label: String, detail: String, state: DayScore.PartState, modifier: Modifier = Modifier) {
    val c = NutriTheme.colors
    val color = when (state) {
        DayScore.PartState.DONE -> c.success
        DayScore.PartState.PARTIAL -> MaterialTheme.colorScheme.primary
        DayScore.PartState.NONE -> MaterialTheme.colorScheme.outline
    }
    val stateText = when (state) {
        DayScore.PartState.DONE -> "сделано"
        DayScore.PartState.PARTIAL -> "частично"
        DayScore.PartState.NONE -> "ещё нет"
    }
    Surface(
        shape = RoundedCornerShape(Radius.Small),
        color = color.copy(alpha = tintAlpha()),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: $stateText, $detail"
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .background(if (state == DayScore.PartState.NONE) Color.Transparent else color, CircleShape)
            ) {
                if (state == DayScore.PartState.DONE) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                } else if (state == DayScore.PartState.NONE) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------- Прогресс: «Недели программы» ----------

data class ProgramState(
    /** Последние 7 дней (последний — сегодня) с оценкой. */
    val days: List<Pair<LocalDate, WeekDayScore>>,
    /** Хороших дней с прошлого чек-ина (без сегодняшнего). */
    val goodSinceCheckIn: Int,
    /** Недель, засчитанных программой: ≥ 5 хороших дней и чек-ин. */
    val weeksDone: Int,
    /** Засчитанных недель подряд — от последнего чек-ина назад. */
    val streak: Int
)

class ProgramViewModel(application: Application) : AndroidViewModel(application) {
    private val db = NutriLensDatabase.getInstance(application)
    private val settingsRepo = SettingsRepository(db.settingsDao())

    private val _state = MutableStateFlow<ProgramState?>(null)
    val state: StateFlow<ProgramState?> = _state.asStateFlow()

    init {
        viewModelScope.launch { settingsRepo.observe().map { it.targetFatPercent != null }.distinctUntilChanged().collect { reload() } }
        viewModelScope.launch { DataEvents.changed.collect { reload() } }
        viewModelScope.launch { db.checkInDao().observeAll().collect { reload() } }
    }

    private suspend fun reload() {
        try {
            val settings = settingsRepo.get()
            if (settings.targetFatPercent == null) {
                _state.value = null
                return
            }
            val plan = db.scorePlan(settings)
            val today = LocalDate.now()
            val reference = settings.checkInReference
            val windowStart = today.minusDays(6)
            val sinceStart = reference?.takeIf { it.isBefore(windowStart) } ?: windowStart
            val days = db.scoreDays(sinceStart, today)
            val week = days.filter { !it.date.isBefore(windowStart) }.map { d ->
                d.date to WeekDayScore(
                    DayScore.compute(d, plan.calories, plan.protein, plan.stepGoal).total,
                    DayScore.dayLevel(d, plan.calories, plan.protein, plan.stepGoal)
                )
            }
            val good = days.filter { d ->
                d.date.isBefore(today) && (reference == null || !d.date.isBefore(reference)) &&
                    d.kcal >= com.nutrilens.app.insights.FatBurnPlanner.MIN_DAY_KCAL &&
                    DayScore.isGood(d, plan.calories, plan.protein, plan.stepGoal)
            }.size
            val checkIns = db.checkInDao().all() // по возрастанию даты
            val done = checkIns.map { weekDone(it) }
            _state.value = ProgramState(
                days = week,
                goodSinceCheckIn = good,
                weeksDone = done.count { it },
                streak = done.asReversed().takeWhile { it }.size
            )
        } catch (e: Exception) {
            _state.value = null
        }
    }

    /** Неделя засчитана: чек-ин сделан и хороших дней ≥ 5 (за длинный период — пропорционально). */
    private fun weekDone(c: CheckInEntity): Boolean = c.goodDays >= CheckInPlanner.scaled(5, c.periodDays)
}

private val WEEKDAY_SHORT = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * «Недели программы» в Прогрессе: дни недели цветом по оценке, засчитанные недели
 * и «N недель подряд». Только хорошее: пропущенная неделя просто не засчитывается,
 * про «потерянные серии» не говорим.
 */
@Composable
fun ProgramWeeksSection(modifier: Modifier = Modifier, viewModel: ProgramViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val today = LocalDate.now()
    Column(modifier) {
        FreshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeader(
                    title = "Недели программы",
                    icon = Icons.Rounded.Insights,
                    tint = MaterialTheme.colorScheme.primary,
                    subtitle = "Неделя засчитана: 5 хороших дней и чек-ин"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    s.days.forEach { (date, score) ->
                        val unfinished = date == today
                        val color = scoreLevelColor(score.level, unfinished)
                        val hasData = score.level != DayScore.Level.NONE
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = if (hasData) "${date.dayOfMonth}: оценка ${score.score}" else "${date.dayOfMonth}: нет данных"
                                }
                        ) {
                            Text(
                                WEEKDAY_SHORT[date.dayOfWeek.value - 1],
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        if (hasData) color.copy(alpha = if (unfinished) 0.25f else 0.85f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        CircleShape
                                    )
                            ) {
                                Text(
                                    if (hasData) "${score.score}" else "·",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasData && !unfinished) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Text(
                    "С прошлого чек-ина хороших дней: ${s.goodSinceCheckIn}" +
                        if (s.goodSinceCheckIn >= 5) " — на чек-ине неделя засчитается" else " из 5",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (s.weeksDone > 0) {
                    TonalSection(color = NutriTheme.colors.success) {
                        Text(
                            if (s.streak >= 2) "${s.streak} ${plural(s.streak, "неделя", "недели", "недель")} подряд — отличный ритм"
                            else "Засчитано ${s.weeksDone} ${plural(s.weeksDone, "неделя", "недели", "недель")} программы",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (s.streak >= 2 && s.weeksDone > s.streak) {
                            Text(
                                "Всего засчитано: ${s.weeksDone}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        "Первая неделя засчитается на чек-ине, если наберётся 5 хороших дней (оценка от 70).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
