package com.nutrilens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutrilens.app.R

// Шрифты как в веб-версии: Manrope (статический) — заголовки и крупные цифры,
// Onest — основной текст (близок к Golos Text из веба). Статические TTF на
// каждый вес: вариативные в Compose рендерятся единственным весом.
private val Manrope = FontFamily(
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
    Font(R.font.manrope_800, FontWeight.ExtraBold)
)

private val Onest = FontFamily(
    Font(R.font.onest_400, FontWeight.Normal),
    Font(R.font.onest_500, FontWeight.Medium),
    Font(R.font.onest_600, FontWeight.SemiBold),
    Font(R.font.onest_700, FontWeight.Bold),
    Font(R.font.onest_800, FontWeight.ExtraBold)
)

// Палитра перенесена из дизайн-токенов веб-версии (src/index.css, fresh-тема).
private val FreshLight = lightColorScheme(
    primary = Color(0xFF0C9D6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2F4EB),
    onPrimaryContainer = Color(0xFF087A53),
    secondary = Color(0xFF2F6FD0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3ECFA),
    onSecondaryContainer = Color(0xFF1D4A90),
    tertiary = Color(0xFFD08700),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7ECD7),
    onTertiaryContainer = Color(0xFF7A5000),
    error = Color(0xFFD5484F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE3E4),
    onErrorContainer = Color(0xFF8C1D22),
    background = Color(0xFFF5F7F4),
    onBackground = Color(0xFF16241C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16241C),
    surfaceVariant = Color(0xFFFAFCF9),
    onSurfaceVariant = Color(0xFF55685D),
    outline = Color(0xFF8FA196),
    outlineVariant = Color(0xFFE5EBE6),
    inverseSurface = Color(0xFF16241C),
    inverseOnSurface = Color(0xFFF5F7F4)
)

private val FreshDark = darkColorScheme(
    primary = Color(0xFF2EC48B),
    onPrimary = Color(0xFF0E1512),
    primaryContainer = Color(0xFF12352A),
    onPrimaryContainer = Color(0xFF55D6A6),
    secondary = Color(0xFF6BA3F2),
    onSecondary = Color(0xFF0E1512),
    secondaryContainer = Color(0xFF1D3050),
    onSecondaryContainer = Color(0xFFA9C6F7),
    tertiary = Color(0xFFECB14E),
    onTertiary = Color(0xFF201500),
    tertiaryContainer = Color(0xFF3A2C10),
    onTertiaryContainer = Color(0xFFF7D9A0),
    error = Color(0xFFEF7076),
    onError = Color(0xFF401010),
    errorContainer = Color(0xFF58211F),
    onErrorContainer = Color(0xFFFFB4B4),
    background = Color(0xFF0E1512),
    onBackground = Color(0xFFE9F2EC),
    surface = Color(0xFF161F1A),
    onSurface = Color(0xFFE9F2EC),
    surfaceVariant = Color(0xFF1C2721),
    onSurfaceVariant = Color(0xFF9DB3A6),
    outline = Color(0xFF647A6E),
    outlineVariant = Color(0xFF233029),
    inverseSurface = Color(0xFFE9F2EC),
    inverseOnSurface = Color(0xFF0E1512)
)

// Скругления как в веб-версии: карточки 26/28, кнопки 16, поля 12.
private val NutriShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(26.dp),
    medium = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(12.dp)
)

/** tracking-tight как в вебе (-0.02em от размера). */
private fun tight(size: Float) = (-0.02f * size).sp

private fun display(fontFamily: FontFamily, weight: FontWeight, size: Float) =
    TextStyle(
        fontFamily = fontFamily,
        fontWeight = weight,
        fontSize = size.sp,
        letterSpacing = tight(size)
    )

// Размеры перенесены с экранов веб-версии: крупные цифры 40-42px,
// заголовки страниц 24-26px, заголовки экранов 18-20px, «вордмарк» 17px.
private val NutriTypography = Typography(
    displayLarge = display(Manrope, FontWeight.ExtraBold, 40f),   // калории в hero
    displayMedium = display(Manrope, FontWeight.ExtraBold, 32f),
    displaySmall = display(Manrope, FontWeight.ExtraBold, 26f),   // заголовок страницы
    headlineLarge = display(Manrope, FontWeight.ExtraBold, 24f),
    headlineMedium = display(Manrope, FontWeight.ExtraBold, 20f), // заголовок экрана
    headlineSmall = display(Manrope, FontWeight.Bold, 18f),
    titleLarge = display(Manrope, FontWeight.ExtraBold, 17f),     // «вордмарк» NutriLens
    titleMedium = display(Onest, FontWeight.Bold, 15f),
    titleSmall = display(Onest, FontWeight.SemiBold, 13.5f),      // название блюда
    bodyLarge = display(Onest, FontWeight.Normal, 16f),
    bodyMedium = display(Onest, FontWeight.Normal, 14f),
    bodySmall = display(Onest, FontWeight.Normal, 12f),
    labelLarge = display(Onest, FontWeight.Bold, 14f),            // кнопки
    labelMedium = display(Onest, FontWeight.SemiBold, 12f),
    labelSmall = display(Onest, FontWeight.Medium, 10f)
)

/** Цвета макронутриентов из веб-токенов (белки/жиры/углеводы). */
data class MacroPalette(val protein: Color, val fat: Color, val carbs: Color)

private val MacroLight = MacroPalette(
    protein = Color(0xFF2F6FD0),
    fat = Color(0xFFD08700),
    carbs = Color(0xFF7D5FD6)
)

private val MacroDark = MacroPalette(
    protein = Color(0xFF6BA3F2),
    fat = Color(0xFFECB14E),
    carbs = Color(0xFFA98FF0)
)

@Composable
fun macroPalette(): MacroPalette =
    if (isSystemInDarkTheme()) MacroDark else MacroLight

@Composable
fun NutriLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FreshDark else FreshLight,
        shapes = NutriShapes,
        typography = NutriTypography,
        content = content
    )
}