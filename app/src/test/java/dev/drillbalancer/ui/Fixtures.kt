package dev.drillbalancer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.protocol.FirmwareParseResult
import dev.drillbalancer.protocol.put16
import dev.drillbalancer.protocol.put32
import dev.drillbalancer.ui.theme.DrillBalancerTheme

// Shared test data and wrappers for the screenshot and ViewModel tests.

/** The narrowest common phone: everything must fit 360 dp. */
const val PHONE = "w360dp-h780dp-xhdpi"

val SHELL = ShellState(
    showMain = true,
    deviceName = "DrillBalancer-1A2B",
    bonded = true,
    batteryPct = 87,
)

/**
 * A synthetic app image (esp_image_header_t, segment header, esp_app_desc_t). The header
 * fields can be broken for the parser's negative cases; an image shorter than the
 * descriptor gets none.
 */
fun firmwareBytes(
    version: String = "0.2.0",
    project: String = "drillbalancer",
    size: Int = 1_337_344,
    magic: Int = 0xE9,
    chip: Int = 0x0009,                              // ESP32-S3
    descMagic: Long = 0xABCD5432L,
): ByteArray = ByteArray(size).also {
    it[0] = magic.toByte()
    it[1] = 3                                        // segment count: 3 empty segments, so
                                                     // FirmwareImage sees a 64-byte image + trailing data
    it.put16(12, chip)
    if (size >= 32 + 256) {
        it.put32(32, descMagic)
        version.toByteArray().copyInto(it, 32 + 16)
        project.toByteArray().copyInto(it, 32 + 48)
        "21:07:13".toByteArray().copyInto(it, 32 + 80)
        "Oct 25 2026".toByteArray().copyInto(it, 32 + 96)
        "v5.5.1".toByteArray().copyInto(it, 32 + 112)
    }
}

fun firmware(version: String = "0.2.0", size: Int = 1_337_344): FirmwareImage =
    (FirmwareImage.parse(firmwareBytes(version, size = size)) as FirmwareParseResult.Ok).image

/** The theme, optionally with a larger font scale (Settings > Display > Font size). */
@Composable
fun Themed(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    // Robolectric (SDK 37) does not redraw a window after its first frame unless something
    // invalidates it again: screens without a running animation came out blank. One
    // invalidation after the first frame fixes that.
    var frames by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { withFrameNanos { frames++ } }
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
        Box(Modifier.drawBehind { frames }) { DrillBalancerTheme(content) }
    }
}

/** A tab's screen inside the connected frame, as in the app. */
@Composable
fun InFrame(tab: Tab?, shell: ShellState = SHELL, title: String? = null, screen: @Composable (Modifier) -> Unit) {
    // A sub-screen (no tab) has a back arrow
    ConnectedFrame(shell, tab, onBack = if (tab == null) ({}) else null, title = title) { padding ->
        screen(Modifier.padding(padding))
    }
}
