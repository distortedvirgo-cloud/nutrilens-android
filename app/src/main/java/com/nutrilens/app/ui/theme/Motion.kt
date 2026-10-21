package com.nutrilens.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Токены движения — единый ритм для всего приложения (skill: motion-consistency,
 * spring-physics, exit-faster-than-enter). Входы ~1.4× длиннее выходов,
 * интерактивные отклики — пружины (прерываемые, без «рывков» при повторном тапе).
 */
object Motion {
    /** Emphasized decelerate (M3): быстрый старт, долгий мягкий финиш — для появлений. */
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Standard (M3): переходы состояния внутри экрана. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Accelerate: уходы — коротко и без «хвоста». */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val Short = 160
    const val Medium = 280
    const val Long = 420
    const val ExtraLong = 900

    /** Шаг «лесенки» для списков: 30–50 мс на элемент. */
    const val StaggerStep = 45

    /** Нажатие: упругое сжатие с лёгким перелётом при отпускании. */
    fun <T> press() = spring<T>(dampingRatio = 0.55f, stiffness = 700f)

    /** Перемещение индикаторов (пилюля дока, сегменты). */
    fun <T> slide() = spring<T>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

    /** Мягкое «доливание» значений (прогресс, кольцо калорий). */
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 90f)
}

/**
 * Уважаем системную настройку «Удалить анимации» (Accessibility) —
 * reduced-motion из чек-листа: при ней бесконечные/декоративные анимации
 * выключаются, а появления рисуются сразу в финальном состоянии.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}
