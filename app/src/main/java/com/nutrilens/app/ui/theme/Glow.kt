package com.nutrilens.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Два дрейфующих «свечения» фона как в fresh-дизайне веб-версии
 (статичная адаптация: мягкие радиальные градиенты в углах).
 */
@Composable
fun NutriGlowBackground(content: @Composable () -> Unit) {
    val a = MaterialTheme.colorScheme.primary
    val b = MaterialTheme.colorScheme.secondary
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .size(460.dp)
                .offset(x = 190.dp, y = (-140).dp)
                .background(
                    Brush.radialGradient(listOf(a.copy(alpha = 0.14f), Color.Transparent))
                )
        )
        Box(
            Modifier
                .size(400.dp)
                .offset(x = (-140).dp, y = 320.dp)
                .background(
                    Brush.radialGradient(listOf(b.copy(alpha = 0.08f), Color.Transparent))
                )
        )
        content()
    }
}
