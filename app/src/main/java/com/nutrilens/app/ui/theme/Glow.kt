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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Амбиентный фон как во fresh-оболочке веба: два мягких свечения (accent +
 * синий, ~0.13/0.07) медленно дрейфуют в противофазе (26s и 32s), создавая
 * «дыхание» вместо статичной заливки. Радиальные градиенты — дёшево для GPU.
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
    val dyA by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26000), RepeatMode.Reverse),
        label = "dyA"
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
        // Свечение accent — справа сверху, дрейф (−34px, +28px), рост ×1.14.
        Box(
            Modifier
                .size(440.dp)
                .offset(x = (120 - 34 * dxA).dp, y = (-110 + 28 * dyA).dp)
                .scale(1f + 0.14f * dxA)
                .background(
                    Brush.radialGradient(listOf(a.copy(alpha = 0.13f), Color.Transparent))
                )
        )
        // Свечение синего — слева на 40% высоты, дрейф (+30px, −24px), сжатие ×0.9.
        Box(
            Modifier
                .size(380.dp)
                .offset(x = (-120 + 30 * dxB).dp, y = (280 - 24 * dyB).dp)
                .scale(1.06f - 0.16f * dyB)
                .background(
                    Brush.radialGradient(listOf(b.copy(alpha = 0.07f), Color.Transparent))
                )
        )
        content()
    }
}