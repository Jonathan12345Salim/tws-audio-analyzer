package id.twsanalyzer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Cyan = Color(0xFF4DD0E1)
    val Orange = Color(0xFFFFB74D)
    val Red = Color(0xFFFF5252)
    val Green = Color(0xFF69F0AE)
    val Yellow = Color(0xFFFFEE58)
    val Magenta = Color(0xFFFF80AB)
    val Grid = Color(0x22FFFFFF)
    val Label = Color(0x99FFFFFF)
    val PanelBg = Color(0xFF0E141B)
}

private val scheme = darkColorScheme(
    primary = Palette.Cyan,
    onPrimary = Color(0xFF00151A),
    secondary = Palette.Orange,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF111820),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF18212B),
    onSurfaceVariant = Color(0xFFB0BEC5),
    surfaceContainer = Color(0xFF141C25),
    surfaceContainerHigh = Color(0xFF18212B),
    surfaceContainerLow = Color(0xFF111820),
    error = Palette.Red,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
