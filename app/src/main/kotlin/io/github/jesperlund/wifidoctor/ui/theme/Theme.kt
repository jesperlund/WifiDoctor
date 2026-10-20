package io.github.jesperlund.wifidoctor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF545F71),
    tertiary = Color(0xFF00696E),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF004788),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBCC7DC),
    tertiary = Color(0xFF4FD8DF),
    error = Color(0xFFFFB4AB),
)

/** Status colours. Always paired with an icon and text, never used alone. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val fail: Color, val neutral: Color)

private val LightStatus = StatusColors(ok = Color(0xFF1B7F3A), warn = Color(0xFF9A6200), fail = Color(0xFFBA1A1A), neutral = Color(0xFF6F7785))
private val DarkStatus = StatusColors(ok = Color(0xFF7DDC93), warn = Color(0xFFFFC66E), fail = Color(0xFFFFB4AB), neutral = Color(0xFFA9B1BF))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun WifiDoctorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
