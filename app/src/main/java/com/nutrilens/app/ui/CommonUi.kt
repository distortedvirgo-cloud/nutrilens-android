package com.nutrilens.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutrilens.app.ui.theme.LocalReducedMotion
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme

/**
 * Упругое сжатие при нажатии (skill: stable interaction states — меняем только
 * graphicsLayer, границы лейаута не двигаются). Пружина прерываемая:
 * быстрый повторный тап не «дёргает» анимацию.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = Motion.press(),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Мягкая многослойная тень: две тени (контактная + рассеянная) читаются
 * дороже одной. В тёмной теме глубину даёт тон поверхности, тень — минимальная.
 */
@Composable
fun Modifier.softShadow(shape: androidx.compose.ui.graphics.Shape, elevation: Int = 10): Modifier {
    val c = NutriTheme.colors
    val strong = if (c.isDark) 0.35f else 0.07f
    val contact = if (c.isDark) 0.4f else 0.05f
    return this
        .shadow(1.dp, shape, ambientColor = c.shadow.copy(alpha = contact), spotColor = c.shadow.copy(alpha = contact))
        .shadow(elevation.dp, shape, ambientColor = c.shadow.copy(alpha = strong), spotColor = c.shadow.copy(alpha = strong))
}

/**
 * Карточка: белая (в тёмной — тонально поднятая) поверхность, тонкая «волосяная»
 * граница и мягкая двойная тень. С onClick — нажимаемая: ripple обрезан формой,
 * карточка слегка сжимается.
 */
@Composable
fun FreshCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    radius: Int = 24,
    borderAlpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(radius.dp)
    val border = BorderStroke(1.dp, NutriTheme.colors.hairline.copy(alpha = NutriTheme.colors.hairline.alpha * borderAlpha))
    if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = shape,
            color = color,
            border = border,
            modifier = modifier
                .pressScale(interaction, 0.975f)
                .softShadow(shape, 8)
        ) { content() }
    } else {
        Surface(
            shape = shape,
            color = color,
            border = border,
            modifier = modifier.softShadow(shape, 8)
        ) { content() }
    }
}

/**
 * Главная кнопка: градиент бренда, цветная «светящаяся» тень, упругое сжатие
 * и лёгкий хаптик. Выключенная — приглушённая, без тени и отклика.
 */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Int = 52,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val c = NutriTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val fade by animateFloatAsState(if (enabled) 1f else 0.45f, tween(Motion.Medium), label = "btnAlpha")
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        color = Color.Transparent,
        modifier = modifier
            .height(height.dp)
            .pressScale(interaction)
            .then(
                if (enabled) Modifier.shadow(
                    14.dp, shape,
                    ambientColor = c.brandFrom.copy(alpha = 0.45f),
                    spotColor = c.brandFrom.copy(alpha = 0.45f)
                ) else Modifier
            )
            .clip(shape)
            .graphicsLayer { alpha = fade }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .background(c.brandGradient)
                .padding(horizontal = 18.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = c.onBrand, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = c.onBrand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        // Стандартный акцент — градиентная кнопка с glow.
        GlowButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
    } else {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = RoundedCornerShape(18.dp),
            color = containerColor,
            modifier = modifier
                .height(52.dp)
                .pressScale(interaction)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
            }
        }
    }
}

/** Вторичная кнопка: тональная поверхность без тени, нажатие — сжатие + затемнение. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Int = 52,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    val bg by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(Motion.Short),
        label = "softBg"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        color = bg,
        modifier = modifier
            .height(height.dp)
            .pressScale(interaction)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Заголовок экрана: крупный плотный заголовок, подзаголовок приглушённый. */
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
            .padding(top = 12.dp, bottom = 18.dp)
            .staggeredIn(0)
    ) {
        if (onBack != null) {
            BackPill(onClick = onBack)
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Text(
                text = title,
                style = if (onBack != null) MaterialTheme.typography.headlineMedium
                else MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Круглая кнопка «назад»: 48dp зона нажатия, стеклянная поверхность. */
@Composable
fun BackPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, NutriTheme.colors.hairline),
        modifier = modifier
            .size(46.dp)
            .pressScale(interaction, 0.88f)
            .softShadow(shape, 6)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Крупные цифры: Manrope ExtraBold, плотный трекинг. */
@Composable
fun DisplayNumber(text: String, size: Int = 40, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = size.sp,
            lineHeight = (size * 1.1f).sp,
            letterSpacing = (-size * 0.03f).sp
        ),
        color = color
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(bottom = 10.dp)
    )
}

/** Маленькая подпись-«оверлайн»: uppercase, разреженный трекинг. */
@Composable
fun TinyLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outline) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        color = color,
        modifier = modifier
    )
}

/**
 * Иконка в тонированной «плашке» — замена эмодзи в структурных местах
 * (skill: no emoji as icons). Иконка декоративная, если рядом есть текст.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Int = 40,
    contentDescription: String? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.34f).dp))
            .background(tint.copy(alpha = if (NutriTheme.colors.isDark) 0.18f else 0.12f))
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((size * 0.5f).dp)
        )
    }
}

/**
 * Сегментный переключатель с «перетекающим» индикатором на пружине.
 * Общий для отчёта, лидерборда и других мест с выбором периода/режима.
 */
@Composable
fun <T> SegmentedSwitcher(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val segment = maxWidth / options.size
            val x by animateDpAsState(segment * index, Motion.slide(), label = "segX")
            Box(
                Modifier
                    .offset(x = x)
                    .width(segment)
                    .height(42.dp)
                    .softShadow(RoundedCornerShape(14.dp), 4)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            )
            Row(Modifier.fillMaxWidth().height(42.dp)) {
                options.forEachIndexed { i, (value, label) ->
                    val active = i == index
                    val color by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(Motion.Medium),
                        label = "segText"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .semantics {
                                role = Role.Tab
                                this.selected = active
                            }
                            .clickableNoRipple {
                                if (!active) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(value)
                                }
                            }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

/** Клик без ripple — для мест, где отклик уже даёт анимация (индикатор, сжатие). */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick
    )
}

/**
 * Мягкое появление элемента «лесенкой»: fade + подъём снизу на emphasized-кривой,
 * задержка растёт с индексом (ограничена, чтобы длинные списки не «ждали»).
 * При «Удалить анимации» элемент сразу в финальном состоянии.
 */
@Composable
fun Modifier.staggeredIn(index: Int, delayMs: Int = Motion.StaggerStep, durationMs: Int = Motion.Long): Modifier {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            kotlinx.coroutines.delay(delayMs * index.coerceIn(0, 8).toLong())
            progress.animateTo(1f, tween(durationMs, easing = Motion.Emphasized))
        }
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 22.dp.toPx()
        val s = 0.98f + 0.02f * p
        scaleX = s
        scaleY = s
    }
}
