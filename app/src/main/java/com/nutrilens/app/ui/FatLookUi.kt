package com.nutrilens.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutrilens.app.R
import com.nutrilens.app.data.BodyComposition
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Процент жира по виду (3.30): таблица фото 3×3, тап по похожему, при необходимости ±1%.
 * Формула по ленте у многих (особенно высоких) сильно ошибается в абсолютной цифре —
 * старт надёжнее выбрать глазами, а лента дальше считает изменения (FatCalibration).
 */

/** Таблица: картинка, где кончаются строки (доли высоты: подписи сдвигают трети) и проценты клеток. */
private class LookChart(val res: Int, val aspect: Float, val rowEnds: List<Float>, val values: List<List<Double>>)

private val MALE_CHART = LookChart(
    R.drawable.fat_chart_male, 521f / 588f, listOf(0.301f, 0.648f, 1f),
    listOf(listOf(3.5, 6.5, 11.0), listOf(15.0, 20.0, 25.0), listOf(30.0, 35.0, 40.0))
)
private val FEMALE_CHART = LookChart(
    R.drawable.fat_chart_female, 500f / 644f, listOf(0.307f, 0.663f, 1f),
    listOf(listOf(11.0, 16.0, 21.0), listOf(25.0, 30.0, 35.0), listOf(40.0, 45.0, 50.0))
)

const val LOOK_MIN = 5.0
const val LOOK_MAX = 55.0

/**
 * Выбор процента по фото. [value] — выбранный процент (null — ещё не выбран).
 * Подсветка — у клетки, ближайшей к значению.
 */
@Composable
fun FatLookPicker(male: Boolean, value: Double?, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    val chart = if (male) MALE_CHART else FEMALE_CHART
    val scheme = MaterialTheme.colorScheme
    val nearest = value?.let { v -> chart.values.flatten().minByOrNull { abs(it - v) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "На кого ты похож больше всего? Смотри на живот и бока, не на мышцы. Если ты между двумя — нажми ближайшее и поправь кнопками.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(chart.aspect)
                .clip(RoundedCornerShape(16.dp))
        ) {
            Image(
                painter = painterResource(chart.res),
                contentDescription = "Таблица процента жира по фото",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Column(Modifier.fillMaxSize()) {
                var prev = 0f
                chart.rowEnds.forEachIndexed { r, end ->
                    val h = end - prev
                    prev = end
                    Row(Modifier.fillMaxWidth().weight(h)) {
                        chart.values[r].forEach { v ->
                            val selected = nearest == v
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .then(
                                        if (selected) Modifier
                                            .background(scheme.primary.copy(alpha = 0.18f))
                                            .border(3.dp, scheme.primary, RoundedCornerShape(10.dp))
                                        else Modifier
                                    )
                                    .clickable { onChange(v) }
                                    .semantics { contentDescription = "Похоже на ${BodyComposition.fmt1(v)}%" }
                            )
                        }
                    }
                }
            }
        }
        if (value != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("По виду", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text(
                        "≈ ${value.roundToInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                }
                FilledTonalIconButton(
                    onClick = { onChange((value - 1).coerceAtLeast(LOOK_MIN)) },
                    modifier = Modifier.semantics { contentDescription = "Меньше на 1%" }
                ) { Icon(Icons.Rounded.Remove, contentDescription = null) }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { onChange((value + 1).coerceAtMost(LOOK_MAX)) },
                    modifier = Modifier.semantics { contentDescription = "Больше на 1%" }
                ) { Icon(Icons.Rounded.Add, contentDescription = null) }
            }
            Text(
                "Это старт. Дальше процент меняется по ленте: талия ушла — процент снизился.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
