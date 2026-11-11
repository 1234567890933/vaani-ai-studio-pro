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
 * Material 3 theme with a real dark mode.
 *
 * On Android 12+ the palette comes from the wallpaper (dynamic colour), which is what makes
 * the app look native. Below 12 a fixed palette is used. The user can force light or dark in
 * Settings — useful for a dubbing app you use at night with the phone on a charger.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF00214E),
    primaryContainer = Color(0xFF16335C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF7FD8C4),
    onSecondary = Color(0xFF00382F),
    tertiary = Color(0xFFF2B8C6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF14181E),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1E242C),
    onSurfaceVariant = Color(0xFFBFC6D1),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF3A424C)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2459C8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF0F7A6C),
    tertiary = Color(0xFF9A4059),
    background = Color(0xFFF8F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F5)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
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
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
