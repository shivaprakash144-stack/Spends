package app.spendtracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.spendtracker.data.repository.ThemeMode

// Brand: deep ink + emerald, warm paper surfaces, a gold accent for budgets.
private val Emerald = Color(0xFF0F6B53)
private val EmeraldLight = Color(0xFF6FD6AE)
private val Ink = Color(0xFF10201B)
private val Paper = Color(0xFFF6F5F0)
private val NightBg = Color(0xFF0B1411)
private val NightSurface = Color(0xFF121D19)

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE1),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4E635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBE6),
    onSecondaryContainer = Color(0xFF0B1F17),
    tertiary = Color(0xFFB7862B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE7BF),
    onTertiaryContainer = Color(0xFF3B2A00),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E7E0),
    onSurfaceVariant = Color(0xFF5B635E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF6),
    surfaceContainer = Color(0xFFF0EFE9),
    surfaceContainerHigh = Color(0xFFEAE9E3),
    surfaceContainerHighest = Color(0xFFE4E3DD),
    outline = Color(0xFFBFC4BE),
    outlineVariant = Color(0xFFDDDFD8),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F4F3E),
    onPrimaryContainer = Color(0xFFCDEFE1),
    secondary = Color(0xFFB3CCC0),
    onSecondary = Color(0xFF1F352C),
    secondaryContainer = Color(0xFF22322B),
    onSecondaryContainer = Color(0xFFD0E8DC),
    tertiary = Color(0xFFF2C66D),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFDFA0),
    background = NightBg,
    onBackground = Color(0xFFE3EAE6),
    surface = NightBg,
    onSurface = Color(0xFFE3EAE6),
    surfaceVariant = Color(0xFF26322D),
    onSurfaceVariant = Color(0xFFA9B5AF),
    surfaceContainerLowest = Color(0xFF07100D),
    surfaceContainerLow = Color(0xFF0F1915),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = Color(0xFF18241F),
    surfaceContainerHighest = Color(0xFF1F2C27),
    outline = Color(0xFF52605A),
    outlineVariant = Color(0xFF2A3631),
    error = Color(0xFFFFB4AB),
)

/** Colours outside the Material scheme: money semantics. */
@Immutable
data class MoneyColors(val credit: Color, val debit: Color, val budgetWarn: Color, val budgetOver: Color)

val LocalMoneyColors = staticCompositionLocalOf { MoneyColors(Color(0xFF1F9D6B), Color.Unspecified, Color(0xFFB7862B), Color(0xFFC4473A)) }

private val base = Typography()
private val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

/** Amount style with tabular figures so columns of numbers line up. */
val AmountStyle = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum")

@Composable
fun SpendTrackerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColour: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val money = if (dark) {
        MoneyColors(credit = Color(0xFF6FD6AE), debit = scheme.onSurface, budgetWarn = Color(0xFFF2C66D), budgetOver = Color(0xFFFF8A7A))
    } else {
        MoneyColors(credit = Color(0xFF1F8A5E), debit = scheme.onSurface, budgetWarn = Color(0xFFB7862B), budgetOver = Color(0xFFC4473A))
    }
    CompositionLocalProvider(LocalMoneyColors provides money) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
