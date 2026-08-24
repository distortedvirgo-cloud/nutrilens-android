package com.nutrilens.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Тени из токенов веб-версии (--f-shadow-card / soft / lift): чёрнильное семейство.
private val ShadowCard = Color(0x1A0F172A)   // 0.1 @ 12px
private val ShadowSoft = Color(0x120F172A)   // 0.07 @ 4px
private val ShadowLift = Color(0x290F172A)   // 0.16 @ 18px

// Палитра кнопок (дизайн-система): здоровый изумруд → глубокий.
private val AccentFrom = Color(0xFF059669)
private val AccentTo = Color(0xFF047857)

/** Карточка в духе минимализма: белая, почти плоская, едва заметная граница. */
@Composable
fun FreshCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    radius: Int = 26,
    borderAlpha: Float = 0.3f,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    Surface(
        shape = shape,
        color = color,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha)
        ),
        shadowElevation = 0.dp,
        modifier = modifier.shadow(4.dp, shape, ambientColor = Color(0x080F172A), spotColor = Color(0x080F172A))
    ) {
        content()
    }
}

/**
 * Главная кнопка: градиент изумруда, лёгкая тень, сжатие при нажатии.
 */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Int = 52
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val background = Brush.linearGradient(
        colors = listOf(AccentFrom, AccentTo),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset.Infinite
    )
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        color = Color.Transparent,
        modifier = modifier
            .height(height.dp)
            .scale(scale)
            .shadow(8.dp, shape, ambientColor = AccentFrom.copy(alpha = 0.25f), spotColor = AccentFrom.copy(alpha = 0.25f))
            .clip(shape)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .background(background)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/** Пилюля для главных действий — прежний контракт (используется во всех экранах). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    if (containerColor == MaterialTheme.colorScheme.primary) {
        // Стандартный акцент — новая градиентная кнопка с glow.
        GlowButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled, height = 48)
    } else {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "pillCustom")
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            modifier = modifier
                .height(48.dp)
                .scale(scale)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                Text(text, fontWeight = FontWeight.SemiBold, color = contentColor)
            }
        }
    }
}

/** Вторичная кнопка: поверхность + бордер, нажатие — лёгкое сжатие. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Int = 52
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "pressSoft")
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        label = "softBg"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        color = bg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .height(height.dp)
            .scale(scale)
            .shadow(5.dp, shape, ambientColor = ShadowSoft, spotColor = ShadowSoft)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Заголовок экрана: кнопка-назад как в вебе (круглая поверхность на мягкой тени). */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 14.dp)
    ) {
        if (onBack != null) {
            BackPill(onClick = onBack)
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Круглая кнопка «назад»: bg-surface, border line/40, active:scale-90. */
@Composable
fun BackPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "backScale")
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier
            .size(40.dp)
            .scale(scale)
            .shadow(5.dp, shape, ambientColor = ShadowSoft, spotColor = ShadowSoft)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Крупные цифры калорий: Manrope ExtraBold, плотный трекинг — как в вебе. */
@Composable
fun DisplayNumber(text: String, size: Int = 40, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        text = text,
        fontSize = size.sp,
        fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = MaterialTheme.typography.displayLarge.letterSpacing,
        color = color
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

/** Маленькая подпись: uppercase, tracking-wider, ink-faint — как text-[10px] в вебе. */
@Composable
fun TinyLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outline) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = color,
        modifier = modifier
    )
}

/** Кривая появления: decelerate как в вебе. */
private val InEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Мягкое появление элемента «лесенкой»: fade + подъём снизу, задержка растёт
 * с индексом. Для статических списков (хаб, настройки, идеи) — живые экраны.
 */
@Composable
fun Modifier.staggeredIn(index: Int, delayMs: Int = 40, durationMs: Int = 340): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(index) {
        delay(delayMs * index.toLong())
        progress.animateTo(1f, tween(durationMs, easing = InEasing))
    }
    return this.then(
        Modifier
            .alpha(progress.value)
            .graphicsLayer { translationY = (1f - progress.value) * 26f }
    )
}
