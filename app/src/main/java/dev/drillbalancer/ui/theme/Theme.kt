package dev.drillbalancer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Level status colours, as on the device: on target / near / off. */
@Immutable
data class StatusColors(
    val onTarget: Color = Green,
    val near: Color = Amber,
    val off: Color = White,
)

private val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

// Always dark: black background like the AMOLED screen on the drill
private val colors = darkColorScheme(
    primary = Green,
    onPrimary = Color.Black,
    secondary = Amber,
    onSecondary = Color.Black,
    tertiary = Blue,
    background = Color.Black,
    onBackground = White,
    surface = Color.Black,
    onSurface = White,
    surfaceVariant = Button,
    onSurfaceVariant = Grey,
    surfaceContainer = Button,
    outline = Dark,
    error = Red,
    onError = Color.Black,
)

@Composable
fun DrillBalancerTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides StatusColors()) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

object DrillBalancerTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
