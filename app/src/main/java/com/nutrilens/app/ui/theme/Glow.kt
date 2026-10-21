package com.nutrilens.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Амбиентный фон «аврора»: мягкое изумрудно-бирюзовое сияние у верхнего края,
 * которое очень медленно дрейфует (30–38 с, противофаза), и тёплая дымка внизу.
 * Рисуется одним drawBehind (без лишних слоёв и measure-проходов) и читает
 * анимированные значения только в фазе отрисовки — без рекомпозиций.
 * При «Удалить анимации» сияние неподвижно.
 */
@Composable
fun NutriGlowBackground(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extras = NutriTheme.colors
    val base = scheme.background
    val a = scheme.primary
    val b = scheme.secondary
    val warm = scheme.tertiary
    val reduced = LocalReducedMotion.current

    val strength = if (extras.isDark) 1.6f else 1f

    val driftA: State<Float>
    val driftB: State<Float>
    if (reduced) {
        driftA = remember { mutableFloatStateOf(0.5f) }
        driftB = remember { mutableFloatStateOf(0.5f) }
    } else {
        val drift = rememberInfiniteTransition(label = "aurora")
        driftA = drift.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Reverse),
            label = "auroraA"
        )
        driftB = drift.animateFloat(
            initialValue = 1f, targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(38000, easing = LinearEasing), RepeatMode.Reverse),
            label = "auroraB"
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(base)
                val w = size.width
                val h = size.height
                val da by driftA
                val db by driftB

                // Главное сияние: справа сверху, ядро за краем экрана.
                val c1 = Offset(w * (0.78f + 0.12f * da), -h * 0.06f)
                val r1 = w * 1.05f
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to a.copy(alpha = 0.16f * strength),
                        0.45f to a.copy(alpha = 0.06f * strength),
                        1f to Color.Transparent,
                        center = c1, radius = r1
                    ),
                    radius = r1, center = c1
                )
                // Бирюзовый шлейф слева.
                val c2 = Offset(w * (0.05f + 0.15f * db), h * 0.08f)
                val r2 = w * 0.9f
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to b.copy(alpha = 0.10f * strength),
                        0.5f to b.copy(alpha = 0.035f * strength),
                        1f to Color.Transparent,
                        center = c2, radius = r2
                    ),
                    radius = r2, center = c2
                )
                // Тёплая дымка внизу — едва заметная, уравновешивает композицию.
                val c3 = Offset(w * (0.3f + 0.2f * da), h * 1.02f)
                val r3 = w * 0.95f
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to warm.copy(alpha = 0.05f * strength),
                        1f to Color.Transparent,
                        center = c3, radius = r3
                    ),
                    radius = r3, center = c3
                )
            }
    ) {
        content()
    }
}
