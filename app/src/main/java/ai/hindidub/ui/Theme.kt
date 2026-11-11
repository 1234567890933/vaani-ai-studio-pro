package ai.hindidub.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Glass palette.
 *
 * The dark scheme is the one that matters: a near-black navy backdrop with cyan and amber
 * accents is what makes translucent cards read as glass rather than as grey boxes. Light mode
 * keeps the same structure so the glass cards still separate from the background.
 */
private val Ink = Color(0xFF070B14)
private val Glass = Color(0xFF141B2B)
private val Cyan = Color(0xFF5FE3F0)
private val Amber = Color(0xFFFFC46B)
private val Violet = Color(0xFF9D8CFF)

private val DarkScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00272C),
    primaryContainer = Color(0xFF123B44),
    onPrimaryContainer = Color(0xFFC9F6FC),
    secondary = Amber,
    onSecondary = Color(0xFF2A1A00),
    secondaryContainer = Color(0xFF3A2A10),
    onSecondaryContainer = Color(0xFFFFE7C2),
    tertiary = Violet,
    background = Ink,
    onBackground = Color(0xFFE9EDF6),
    surface = Glass,
    onSurface = Color(0xFFE9EDF6),
    surfaceVariant = Color(0xFF1B2334),
    onSurfaceVariant = Color(0xFFA9B4C9),
    error = Color(0xFFFF9A94),
    outline = Color(0xFF2C3648)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B6E7F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF2F8),
    secondary = Color(0xFF8A5A00),
    tertiary = Color(0xFF5B4BD6),
    background = Color(0xFFF4F6FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7ECF5),
    outline = Color(0xFFC7D0E0)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

@Composable
fun HindiDubberTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        // Dynamic colour is deliberately off: the glass look needs the fixed deep palette to
        // stay readable, and wallpaper-derived colours wash the translucency out.
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** Accent gradient used by the splash, the hero card and the nav indicator. */
val BrandGradient: List<Color> = listOf(Cyan, Violet, Amber)
