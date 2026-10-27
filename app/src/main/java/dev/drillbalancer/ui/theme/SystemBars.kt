package dev.drillbalancer.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge to edge with light status and navigation bar icons whatever the system's theme: the app
 * is always dark (black like the drill's screen), so the system's light-theme icons (dark)
 * would not show on it.
 */
fun ComponentActivity.enableDarkEdgeToEdge() {
    enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
}
