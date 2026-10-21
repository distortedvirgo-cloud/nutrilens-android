package com.nutrilens.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutrilens.app.ui.theme.Motion
import com.nutrilens.app.ui.theme.NutriTheme

/*
 * Дизайн-кит NutriLens. Эталон — лист деталей блюда: один главный элемент,
 * тонированные плашки вместо белых карточек внутри карточек, тонкие разделители
 * в списках, действия внизу (вторичная + главная). Экраны собираются только из
 * этих токенов и компонентов — без своих радиусов, отступов и Material-дефолтов.
 */

/** Скругления: всего четыре ступени + пилюля. */
object Radius {
    /** Нижние листы (верхние углы). */
    val Sheet = 32.dp
    /** Карточки верхнего уровня и крупные медиа. */
    val Card = 24.dp
    /** Плашки, кнопки, поля ввода, миниатюры. */
    val Tile = 18.dp
    /** Мелкие элементы внутри плашек: иконки-бейджи, сегменты, пузыри. */
    val Small = 14.dp
    val Pill = 999.dp
}

/** Отступы: экран, карточка, промежутки. */
object Space {
    /** Горизонтальный отступ всех экранов и листов. */
    val Screen = 20.dp
    /** Внутренний отступ карточек. */
    val Card = 18.dp
    /** Между карточками в колонке. */
    val Gap = 12.dp
    /** Между смысловыми блоками экрана. */
    val Section = 24.dp
}

/** Альфа тонировки плашек — одна на всё приложение (как КБЖУ в эталоне). */
@Composable
fun tintAlpha(): Float = if (NutriTheme.colors.isDark) 0.16f else 0.09f

/**
 * Поле ввода: тональная заливка без рамки, на фокусе — тонкая рамка бренда.
 * Параметры совпадают с OutlinedTextField, чтобы замена была один-в-один.
 */
@Composable
fun NutriTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    suffix: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    supportingText: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = scheme.onSurfaceVariant) } },
        suffix = suffix?.let { { Text(it, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant) } },
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(Radius.Tile),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHigh,
            unfocusedContainerColor = scheme.surfaceContainerHigh,
            disabledContainerColor = scheme.surfaceContainer,
            errorContainerColor = scheme.errorContainer.copy(alpha = 0.5f),
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            errorBorderColor = scheme.error,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = scheme.primary
        )
    )
}

/**
 * Нижний лист в стиле эталона: поверхность, верхние углы 32, отступы 20,
 * прокрутка. Заменяет и полноэкранные листы, и диалоги.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutriSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radius.Sheet, topEnd = Radius.Sheet)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.Screen)
                .padding(bottom = 24.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/**
 * Диалог-подтверждение как лист: заголовок, содержимое, внизу «Отмена»
 * (вторичная) и главное действие. destructive — главное действие красное.
 */
@Composable
fun NutriDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    subtitle: String? = null,
    confirmEnabled: Boolean = true,
    dismissText: String = "Отмена",
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    NutriSheet(onDismiss = onDismiss, title = title, subtitle = subtitle) {
        content()
        Spacer(Modifier.height(24.dp))
        SheetActions(
            secondaryText = dismissText,
            onSecondary = onDismiss,
            primaryText = confirmText,
            onPrimary = onConfirm,
            primaryEnabled = confirmEnabled,
            destructive = destructive
        )
    }
}

/** Ряд действий внизу листа: вторичная слева, главная справа — как в эталоне. */
@Composable
fun SheetActions(
    secondaryText: String,
    onSecondary: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    destructive: Boolean = false,
    primaryIcon: ImageVector? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SoftButton(text = secondaryText, onClick = onSecondary, modifier = Modifier.weight(1f))
        if (destructive) {
            DangerButton(text = primaryText, onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f))
        } else {
            GlowButton(
                text = primaryText,
                onClick = onPrimary,
                enabled = primaryEnabled,
                icon = primaryIcon,
                height = 52,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Опасное действие (удалить): тонированная красная плашка, без свечения. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val error = MaterialTheme.colorScheme.error
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radius.Tile),
        color = error.copy(alpha = if (NutriTheme.colors.isDark) 0.22f else 0.12f),
        modifier = modifier
            .height(52.dp)
            .pressScale(interaction)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = error)
        }
    }
}

/**
 * Плашка значения — главный кирпич эталона (КБЖУ в деталях блюда):
 * крупное число цветом акцента, подпись, тонированный фон.
 */
@Composable
fun TonalTile(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(Radius.Tile)
    val base = modifier
        .clip(shape)
        .background(color.copy(alpha = tintAlpha()))
    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        modifier
            .pressScale(interaction)
            .clip(shape)
            .background(color.copy(alpha = tintAlpha()))
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick
            )
    } else base
    Column(
        modifier = clickable.padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
        }
        Text(text = value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Тонированная секция внутри листа/экрана (как «оценка состава» в эталоне):
 * цветной фон без рамки и тени.
 */
@Composable
fun TonalSection(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.Tile),
        color = color.copy(alpha = tintAlpha()),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

/**
 * Пилюля-чип: действие внутри карточки. primary — тонированный бренд,
 * иначе нейтральная тональная поверхность. selected — для фильтров.
 */
@Composable
fun NutriChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val active = primary || selected
    val bg by animateColorAsState(
        if (active) scheme.primaryContainer else scheme.surfaceContainerHigh,
        tween(Motion.Short),
        label = "chipBg"
    )
    val fg = if (active) scheme.onPrimaryContainer else scheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radius.Pill),
        color = bg,
        modifier = modifier
            .heightIn(min = 40.dp)
            .pressScale(interaction)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (icon != null || selected) {
                Icon(
                    icon ?: Icons.Rounded.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** Текстовое действие («Повторить», «Скрыть», время): бренд-цвет, без фона. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radius.Pill),
        color = Color.Transparent,
        modifier = modifier
            .heightIn(min = 40.dp)
            .pressScale(interaction)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/** Переключатель в цветах бренда. */
@Composable
fun NutriSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = scheme.outline,
            uncheckedTrackColor = scheme.surfaceContainerHighest,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

/** Круглая отметка — как в «Привычках дня»: заливка бренда и пружинная галочка. */
@Composable
fun NutriCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(Motion.Medium),
        label = "checkFill"
    )
    val scale by animateFloatAsState(if (checked) 1f else 0f, Motion.press(), label = "checkScale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

/** Строка с круглой отметкой: вся строка — зона нажатия (≥48dp). */
@Composable
fun CheckRow(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.Small))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp)
    ) {
        NutriCheck(checked)
        Spacer(Modifier.width(12.dp))
        content()
    }
}

/** Шкала прогресса: пилюля-трек и анимированное заполнение. */
@Composable
fun ProgressTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    brush: Brush = NutriTheme.colors.brandGradient,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    height: Dp = 8.dp
) {
    val f by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        tween(Motion.ExtraLong, easing = Motion.Emphasized),
        label = "track"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Radius.Pill))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radius.Pill))
                .background(brush)
        )
    }
}

/** Тонкий разделитель списков; inset — отступ слева (под иконку). */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(NutriTheme.colors.hairline)
    )
}

/** Шапка внутри карточки: иконка-бейдж, заголовок, опционально подпись и хвост. */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        if (icon != null) {
            IconBadge(icon, tint, size = 36)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** Ответ ИИ: карточка с шапкой «искра + заголовок» и разметкой. */
@Composable
fun AiResultCard(text: String, modifier: Modifier = Modifier, title: String = "Ответ ИИ") {
    FreshCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.Card)) {
            CardHeader(title = title, icon = Icons.Rounded.AutoAwesome)
            Spacer(Modifier.height(12.dp))
            MarkdownText(text)
        }
    }
}

/** Подпись группы над карточками (оверлайн) — единый стиль для всех экранов. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    TinyLabel(text, modifier.padding(start = 4.dp, bottom = 10.dp))
}
