package com.nutrilens.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Амбиентный фон: без «источника света». Вместо кругов с ярким центром —
 * большие заливки (600dp), чьи ядра уходят за экран: видна только внешняя
 * дымка, которая мягко тает к краям. Плюс едва заметная общая тональная
 * подложка сверху вниз. Дрейф остаётся медленным (26s/32s, противофаза).
 */
@Composable
fun NutriGlowBackground(content: @Composable () -> Unit) {
    val a = MaterialTheme.colorScheme.primary
    val b = MaterialTheme.colorScheme.secondary

    val drift = rememberInfiniteTransition(label = "glowDrift")
    val dxA by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26000), RepeatMode.Reverse),
        label = "dxA"
    )
    val dxB by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(32000), RepeatMode.Reverse),
        label = "dxB"
    )
    val dyB by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(32000), RepeatMode.Reverse),
        label = "dyB"
    )

    Box(Modifier.fillMaxSize()) {
        // Общая тональная подложка: мягко-мятный свет сверху (в тон палитры
        // вместо белого), чище к низу. В тёмной теме — глубокий зелёный.
        val wash = MaterialTheme.colorScheme.primaryContainer
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            wash.copy(alpha = 0.45f),
                            Color.Transparent,
                            a.copy(alpha = 0.025f)
                        ),
                        endY = 1600f
                    )
                )
        )
        // Дымка акцента сверху-справа: ядро за экраном, виден только шлейф
        // (stops дают плато, а не яркую точку в центре).
        Box(
            Modifier
                .size(600.dp)
                .offset(x = (260 - 30 * dxA).dp, y = (-240).dp)
                .background(
                    Brush.radialGradient(
                        0f to a.copy(alpha = 0.075f),
                        0.55f to a.copy(alpha = 0.035f),
                        1f to Color.Transparent
                    )
                )
        )
        // Вторая дымка (синяя) слева, чуть ниже середины.
        Box(
            Modifier
                .size(640.dp)
                .offset(x = (-300 + 26 * dxB).dp, y = (420 - 22 * dyB).dp)
                .background(
                    Brush.radialGradient(
                        0f to b.copy(alpha = 0.05f),
                        0.55f to b.copy(alpha = 0.025f),
                        1f to Color.Transparent
                    )
                )
        )
        content()
    }
}