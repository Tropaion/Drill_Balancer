package dev.drillbalancer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// The few icons the app needs, as 24x24 vectors (Material paths; the level and device
// icons are drawn for this app). The material-icons library is not a dependency.
object DbIcons {
    val Live = icon("Live", stroke = 2f) {
        // A spirit level: vial with bubble between two marks
        add("M3,8h18v8H3z")
        add("M9,8v8M15,8v8")
        fill("M12,10.2a1.8,1.8 0,1 1,0 3.6a1.8,1.8 0,1 1,0 -3.6z")
    }
    val Settings = icon("Settings") {
        fill(
            "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9z" +
                "M21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
        )
    }
    val Calibrate = icon("Calibrate") {
        fill(
            "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 " +
                "-7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2" +
                "v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 " +
                "7,7 -3.13,7 -7,7z",
        )
    }
    val Device = icon("Device", stroke = 2f) {
        // The round AMOLED with its status ring
        add("M12,3a9,9 0,1 1,0 18a9,9 0,1 1,0 -18z")
        add("M8,12h8")
    }
    val Bluetooth = icon("Bluetooth") {
        fill(
            "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 " +
                "4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z",
        )
    }
    val Bolt = icon("Charging") { fill("M7,2v11h3v9l7,-12h-4l4,-8z") }
    val Warning = icon("Warning") { fill("M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z") }
    val Info = icon("Info") {
        fill("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2L11,7h2v2z")
    }
    val Check = icon("Check") { fill("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
    val Back = icon("Back") { fill("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val Chevron = icon("Open") { fill("M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
    val ArrowUp = icon("Up") { fill("M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z") }
    val ArrowDown = icon("Down") { fill("M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z") }
}

private class IconScope(val builder: ImageVector.Builder, val stroke: Float) {
    fun fill(path: String) {
        builder.addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    }

    fun add(path: String) {
        builder.addPath(
            addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
        )
    }
}

private fun icon(name: String, stroke: Float = 0f, paths: IconScope.() -> Unit): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    IconScope(b, stroke).paths()
    return b.build()
}
