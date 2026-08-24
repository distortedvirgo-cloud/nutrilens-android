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

// Дизайн-система по skill «ui-ux-pro-max» (Minimalism & Swiss, density 2/10):
// спокойная пара Lora (serif, «калм-велнес») для заголовков и цифр,
// Raleway (воздушный sans) для интерфейсного текста. Статические TTF на вес.
private val Lora = FontFamily(
    Font(R.font.lora_500, FontWeight.Medium),
    Font(R.font.lora_600, FontWeight.SemiBold),
    Font(R.font.lora_700, FontWeight.Bold)
)

private val Raleway = FontFamily(
    Font(R.font.raleway_400, FontWeight.Normal),
    Font(R.font.raleway_500, FontWeight.Medium),
    Font(R.font.raleway_600, FontWeight.SemiBold),
    Font(R.font.raleway_700, FontWeight.Bold)
)

// Палитра из дизайн-системы: воздушный мятный канвас, здоровый изумруд
// как бренд, оранжевый как энергия/акцент, минимум тяжести.
private val FreshLight = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF053B2C),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFEA580C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEDD5),
    onTertiaryContainer = Color(0xFF7C2D12),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFECFDF5),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF0F8F6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFE1F2ED),
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFECFDF5)
)

private val FreshDark = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF052E2B),
    primaryContainer = Color(0xFF0B4F3C),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF052E2B),
    secondaryContainer = Color(0xFF0F4B3A),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFFB923C),
    onTertiary = Color(0xFF3A1C05),
    tertiaryContainer = Color(0xFF5C2E0A),
    onTertiaryContainer = Color(0xFFFFD7B5),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF5F1B1B),
    onErrorContainer = Color(0xFFFFB4B4),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE7EEF7),
    surface = Color(0xFF111A2C),
    onSurface = Color(0xFFE7EEF7),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFFA8B6C9),
    outline = Color(0xFF7C8BA0),
    outlineVariant = Color(0xFF24314A),
    inverseSurface = Color(0xFFE7EEF7),
    inverseOnSurface = Color(0xFF0B1220)
)

// Скругления: воздушные карточки 28, кнопки 16, поля 12.
private val NutriShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(24.dp),
    medium = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(12.dp)
)

private fun style(fontFamily: FontFamily, weight: FontWeight, size: Float, tracking: Float = 0f) =
    TextStyle(
        fontFamily = fontFamily,
        fontWeight = weight,
        fontSize = size.sp,
        letterSpacing = tracking.sp
    )

// Размеры — как раньше (проверены в вебе), но шрифт спокойный: Lora.
private val NutriTypography = Typography(
    displayLarge = style(Lora, FontWeight.Bold, 40f),      // калории в hero
    displayMedium = style(Lora, FontWeight.Bold, 32f),
    displaySmall = style(Lora, FontWeight.Bold, 26f),      // заголовок страницы
    headlineLarge = style(Lora, FontWeight.Bold, 24f),
    headlineMedium = style(Lora, FontWeight.Bold, 20f),    // заголовок экрана
    headlineSmall = style(Lora, FontWeight.SemiBold, 18f),
    titleLarge = style(Lora, FontWeight.Bold, 17f),        // «вордмарк»
    titleMedium = style(Raleway, FontWeight.SemiBold, 15f),
    titleSmall = style(Raleway, FontWeight.SemiBold, 13.5f),
    bodyLarge = style(Raleway, FontWeight.Normal, 16f),
    bodyMedium = style(Raleway, FontWeight.Normal, 14f),
    bodySmall = style(Raleway, FontWeight.Normal, 12f),
    labelLarge = style(Raleway, FontWeight.SemiBold, 14f), // кнопки
    labelMedium = style(Raleway, FontWeight.SemiBold, 12f),
    labelSmall = style(Raleway, FontWeight.Medium, 10f)
)

/** Цвета макронутриентов (семантические, сохранились из веба). */
data class MacroPalette(val protein: Color, val fat: Color, val carbs: Color)

private val MacroLight = MacroPalette(
    protein = Color(0xFF2F6FD0),
    fat = Color(0xFFEA580C),
    carbs = Color(0xFF7D5FD6)
)

private val MacroDark = MacroPalette(
    protein = Color(0xFF6BA3F2),
    fat = Color(0xFFFB923C),
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