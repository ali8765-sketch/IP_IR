package com.v2ray.ang.ui.chapar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.v2ray.ang.ui.compose.resolveDarkTheme

// Chapar design tokens (design 1: indigo and saffron). Same values as khorasan.css in the web design (made before the rename, D-039).

private val ChaparLight = lightColorScheme(
    primary = Color(0xFF2E2A6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E5F5),
    onPrimaryContainer = Color(0xFF221F55),
    secondary = Color(0xFFE8892B),
    onSecondary = Color(0xFF1E1C2E),
    secondaryContainer = Color(0xFFFFF2D9),
    onSecondaryContainer = Color(0xFF9A4A07),
    background = Color(0xFFF8F5F0),
    onBackground = Color(0xFF1E1C2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1C2E),
    surfaceVariant = Color(0xFFF2EDE6),
    onSurfaceVariant = Color(0xFF5A5563),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF8F4),
    surfaceContainer = Color(0xFFF2EDE6),
    surfaceContainerHigh = Color(0xFFEDE7DF),
    surfaceContainerHighest = Color(0xFFE8E1D8),
    outline = Color(0xFF8A8178),
    outlineVariant = Color(0xFFE4DCD1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCEBE9),
    onErrorContainer = Color(0xFF8C1D17),
)

private val ChaparDark = darkColorScheme(
    primary = Color(0xFFF2A65A),
    onPrimary = Color(0xFF1E1C2E),
    primaryContainer = Color(0xFF3A2E14),
    onPrimaryContainer = Color(0xFFF8CB94),
    secondary = Color(0xFFF2A65A),
    onSecondary = Color(0xFF1E1C2E),
    secondaryContainer = Color(0xFF3A2E14),
    onSecondaryContainer = Color(0xFFF6B97A),
    background = Color(0xFF121226),
    onBackground = Color(0xFFF4EFE7),
    surface = Color(0xFF1C1C36),
    onSurface = Color(0xFFF4EFE7),
    surfaceVariant = Color(0xFF26264A),
    onSurfaceVariant = Color(0xFFBDB7CB),
    surfaceContainerLowest = Color(0xFF121226),
    surfaceContainerLow = Color(0xFF18182F),
    surfaceContainer = Color(0xFF1C1C36),
    surfaceContainerHigh = Color(0xFF26264A),
    surfaceContainerHighest = Color(0xFF30305A),
    outline = Color(0xFF8A87A6),
    outlineVariant = Color(0xFF35355A),
    error = Color(0xFFFF9A90),
    onError = Color(0xFF1E1C2E),
    errorContainer = Color(0xFF3B1E26),
    onErrorContainer = Color(0xFFFFC9C3),
)

/** Brand colors Material has no slot for. */
@Immutable
data class ChaparColors(
    val sun: Color,
    val glow: Color,
    val ok: Color,
    val onOk: Color,
)

private val LightExtras = ChaparColors(
    sun = Color(0xFFE8892B),
    glow = Color(0xFFFBDCBD),
    ok = Color(0xFF2D6A4F),
    onOk = Color(0xFFFFFFFF),
)

private val DarkExtras = ChaparColors(
    sun = Color(0xFFF2A65A),
    glow = Color(0xFF4A2B36),
    ok = Color(0xFF86D3AA),
    onOk = Color(0xFF10261B),
)

val LocalChaparColors = staticCompositionLocalOf { LightExtras }

/**
 * Chapar's own look. Brand colors replace upstream's palette and dynamic color on purpose;
 * the light/dark choice still follows the app's theme setting.
 */
@Composable
fun ChaparTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalChaparColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (darkTheme) ChaparDark else ChaparLight, content = content)
    }
}
