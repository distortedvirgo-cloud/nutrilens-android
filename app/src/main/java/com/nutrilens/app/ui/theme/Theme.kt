package com.nutrilens.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutrilens.app.R

// Дизайн-система v2 (skill «ui-ux-pro-max»: Material 3 Expressive + soft premium).
// Один шрифт — Manrope (как в веб-версии): геометричный гротеск с ровными
// табличными цифрами, кириллицей и широкой шкалой весов. Иерархия строится
// весом и размером, а не сменой гарнитуры. Статические TTF на каждый вес.
private val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
    Font(R.font.manrope_800, FontWeight.ExtraBold)
)

// Палитра «Ivory & Emerald»: тёплый слоновой кости канвас, глубокий изумруд
// как бренд, бирюза — второй акцент, янтарь — энергия/перебор. Все пары
// текст/фон проверены на WCAG ≥ 4.5:1 (вторичный текст и outline — тоже,
// т.к. outline используется для мелких подписей).
private val IvoryLight = lightColorScheme(
    primary = Color(0xFF0A7F5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5F2E3),
    onPrimaryContainer = Color(0xFF053D2B),
    secondary = Color(0xFF0E7490),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6F1F6),
    onSecondaryContainer = Color(0xFF083A48),
    tertiary = Color(0xFFC2410C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE8D6),
    onTertiaryContainer = Color(0xFF6B2206),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE4E4),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF5F6F2),
    onBackground = Color(0xFF0D1411),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1411),
    surfaceVariant = Color(0xFFEEF1EC),
    onSurfaceVariant = Color(0xFF4A5650),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBF8),
    surfaceContainer = Color(0xFFF2F4F0),
    surfaceContainerHigh = Color(0xFFECEFEA),
    surfaceContainerHighest = Color(0xFFE6EAE4),
    outline = Color(0xFF667169),
    outlineVariant = Color(0xFFE2E7E0),
    inverseSurface = Color(0xFF16201B),
    inverseOnSurface = Color(0xFFEFF4F0),
    inversePrimary = Color(0xFF3DDC97),
    scrim = Color(0xFF000000)
)

// Тёмная — «Emerald Night»: графит с едва заметным зелёным подтоном,
// поверхности поднимаются тоном (tonal elevation), а не тенью.
private val EmeraldNight = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF032E1E),
    primaryContainer = Color(0xFF0F3B2A),
    onPrimaryContainer = Color(0xFFA9F0CC),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF04302B),
    secondaryContainer = Color(0xFF0D3A39),
    onSecondaryContainer = Color(0xFFB4F5EA),
    tertiary = Color(0xFFFDBA74),
    onTertiary = Color(0xFF3A1C05),
    tertiaryContainer = Color(0xFF4A2810),
    onTertiaryContainer = Color(0xFFFFDDBF),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF4C1717),
    onErrorContainer = Color(0xFFFFC4C4),
    background = Color(0xFF090D0B),
    onBackground = Color(0xFFECF2EE),
    surface = Color(0xFF111714),
    onSurface = Color(0xFFECF2EE),
    surfaceVariant = Color(0xFF18201C),
    onSurfaceVariant = Color(0xFFA3B0A9),
    surfaceContainerLowest = Color(0xFF070A09),
    surfaceContainerLow = Color(0xFF0E1311),
    surfaceContainer = Color(0xFF141B17),
    surfaceContainerHigh = Color(0xFF1A221E),
    surfaceContainerHighest = Color(0xFF212A25),
    outline = Color(0xFF85918A),
    outlineVariant = Color(0xFF26302B),
    inverseSurface = Color(0xFFECF2EE),
    inverseOnSurface = Color(0xFF111714),
    inversePrimary = Color(0xFF0A7F5A),
    scrim = Color(0xFF000000)
)

// Скругления: крупные «мягкие» карточки 28, вложенные 22, кнопки 18, поля 14.
private val NutriShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun style(
    weight: FontWeight,
    size: Float,
    line: Float,
    tracking: Float = 0f
) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

// Крупное — плотнее по трекингу (премиальный «сжатый» дисплей),
// мелкое — чуть разреженнее для читаемости. Тело ≥ 14sp, line-height ~1.45.
private val NutriTypography = Typography(
    displayLarge = style(FontWeight.ExtraBold, 44f, 48f, -1.4f),   // калории в hero
    displayMedium = style(FontWeight.ExtraBold, 34f, 40f, -1f),
    displaySmall = style(FontWeight.ExtraBold, 28f, 34f, -0.6f),   // заголовок страницы
    headlineLarge = style(FontWeight.ExtraBold, 26f, 32f, -0.5f),
    headlineMedium = style(FontWeight.ExtraBold, 22f, 28f, -0.4f), // заголовок экрана
    headlineSmall = style(FontWeight.Bold, 19f, 25f, -0.2f),
    titleLarge = style(FontWeight.ExtraBold, 18f, 24f, -0.2f),     // «вордмарк»
    titleMedium = style(FontWeight.Bold, 15.5f, 21f, -0.1f),
    titleSmall = style(FontWeight.SemiBold, 13.5f, 19f),
    bodyLarge = style(FontWeight.Medium, 16f, 23f),
    bodyMedium = style(FontWeight.Medium, 14f, 20f),
    bodySmall = style(FontWeight.Medium, 12.5f, 17f, 0.1f),
    labelLarge = style(FontWeight.Bold, 14.5f, 20f, 0.1f),         // кнопки
    labelMedium = style(FontWeight.SemiBold, 12f, 16f, 0.2f),
    labelSmall = style(FontWeight.SemiBold, 10.5f, 14f, 0.4f)
)

/** Цвета макронутриентов (семантические, по всему приложению). */
@Immutable
data class MacroPalette(val protein: Color, val fat: Color, val carbs: Color)

private val MacroLight = MacroPalette(
    protein = Color(0xFF2F6FD0),
    fat = Color(0xFFD9620F),
    carbs = Color(0xFF7D5FD6)
)

private val MacroDark = MacroPalette(
    protein = Color(0xFF7FB0F5),
    fat = Color(0xFFFDBA74),
    carbs = Color(0xFFB9A3F5)
)

/**
 * Расширенные токены, которых нет в Material ColorScheme: градиенты бренда,
 * вода, тени, «стекло» дока. Всё зависит от темы — экраны не держат hex.
 */
@Immutable
data class NutriColors(
    val brandFrom: Color,
    val brandTo: Color,
    val onBrand: Color,
    val warnFrom: Color,
    val warnTo: Color,
    val dangerFrom: Color,
    val dangerTo: Color,
    val water: Color,
    val waterDeep: Color,
    val success: Color,
    val shadow: Color,
    val glass: Color,
    val glassBorder: Color,
    val hairline: Color,
    val isDark: Boolean
) {
    val brandGradient: Brush get() = Brush.linearGradient(listOf(brandFrom, brandTo))
    val warnGradient: Brush get() = Brush.linearGradient(listOf(warnFrom, warnTo))
    val dangerGradient: Brush get() = Brush.linearGradient(listOf(dangerFrom, dangerTo))
    val waterGradient: Brush get() = Brush.horizontalGradient(listOf(water, waterDeep))
}

private val ExtrasLight = NutriColors(
    brandFrom = Color(0xFF12A174),
    brandTo = Color(0xFF07684F),
    onBrand = Color(0xFFFFFFFF),
    warnFrom = Color(0xFFFB923C),
    warnTo = Color(0xFFDD5A0B),
    dangerFrom = Color(0xFFEF4444),
    dangerTo = Color(0xFFB91C1C),
    water = Color(0xFF3B9BD9),
    waterDeep = Color(0xFF1F6FAF),
    success = Color(0xFF0A7F5A),
    shadow = Color(0xFF0D1411),
    glass = Color(0xF2FFFFFF),
    glassBorder = Color(0x1A0D1411),
    hairline = Color(0x140D1411),
    isDark = false
)

private val ExtrasDark = NutriColors(
    brandFrom = Color(0xFF4BE3A0),
    brandTo = Color(0xFF1AAE76),
    onBrand = Color(0xFF032E1E),
    warnFrom = Color(0xFFFDBA74),
    warnTo = Color(0xFFF97316),
    dangerFrom = Color(0xFFF87171),
    dangerTo = Color(0xFFDC2626),
    water = Color(0xFF6CC0F5),
    waterDeep = Color(0xFF3A93D6),
    success = Color(0xFF3DDC97),
    shadow = Color(0xFF000000),
    glass = Color(0xEB161E1A),
    glassBorder = Color(0x1FFFFFFF),
    hairline = Color(0x14FFFFFF),
    isDark = true
)

private val LocalNutriColors = staticCompositionLocalOf { ExtrasLight }
private val LocalMacroPalette = staticCompositionLocalOf { MacroLight }

/** Точка доступа к расширенным токенам: `NutriTheme.colors.brandGradient`. */
object NutriTheme {
    val colors: NutriColors
        @Composable @ReadOnlyComposable get() = LocalNutriColors.current
}

@Composable
@ReadOnlyComposable
fun macroPalette(): MacroPalette = LocalMacroPalette.current

@Composable
fun NutriLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalNutriColors provides if (darkTheme) ExtrasDark else ExtrasLight,
        LocalMacroPalette provides if (darkTheme) MacroDark else MacroLight,
        LocalReducedMotion provides rememberReducedMotion()
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) EmeraldNight else IvoryLight,
            shapes = NutriShapes,
            typography = NutriTypography,
            content = content
        )
    }
}
