package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/** Original SVG-style 24px paths; shared by both layouts with one stroke weight. */
private val settingsIconPaths =
    mapOf(
        "search" to "M10.5 3.5 A7 7 0 1 0 10.5 17.5 A7 7 0 1 0 10.5 3.5 M16 16 L21 21",
        "close" to "M6 6 L18 18 M18 6 L6 18",
        "chevron" to "M8 10 L12 14 L16 10",
        "back" to "M14 6 L8 12 L14 18",
        "modules" to "M4 6 H20 M4 12 H20 M4 18 H20 M8 3 V9 M16 9 V15 M9 15 V21",
        "settings" to
            "M9 3 H15 L16 6 L19 7 L21 10 V14 L18 15 L17 18 L14 21 H10 L9 18 L6 17 L3 14 V10 L6 9 L7 6 Z M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8",
        "combat" to
            "M3 3 H6 L17 14 L14 17 L3 6 Z M12.5 18.5 L18.5 12.5 M16 16 L20.5 20.5 M19 22 L22 19 M14 7 L18 3 H21 V6 L17 10 M7 14 L10 17 M5.5 12.5 L11.5 18.5 M8 16 L3.5 20.5 M2 19 L5 22",
        "movement" to
            "M14 3 A2 2 0 1 0 14 7 A2 2 0 1 0 14 3 M12 9 L9 14 L14 17 L12 22 M12 9 L16 12 H21 M10 10 L6 10 L3 14 M9 14 L6 19 H2",
        "player" to "M12 3 A4 4 0 1 0 12 11 A4 4 0 1 0 12 3 M4 21 V19 C4 12 20 12 20 19 V21",
        "render" to
            "M2 12 C6 3 18 3 22 12 C18 21 6 21 2 12 Z M12 8 A4 4 0 1 0 12 16 A4 4 0 1 0 12 8",
        "world" to
            "M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 M2 12 H22 M12 2 C5 7 5 17 12 22 C19 17 19 7 12 2",
        "network" to
            "M12 3 A3 3 0 1 0 12 9 A3 3 0 1 0 12 3 M5 15 A3 3 0 1 0 5 21 A3 3 0 1 0 5 15 M19 15 A3 3 0 1 0 19 21 A3 3 0 1 0 19 15 M12 9 V12 M5 15 V12 H19 V15",
        "experiment" to "M8 3 H16 M10 3 V9 L4 19 Q3 21 6 21 H18 Q21 21 20 19 L14 9 V3 M7 15 H17",
        "misc" to "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z",
        "hud" to "M3 4 H21 V20 H3 Z M3 9 H21 M9 9 V20",
        "configs" to "M3 6 H10 L12 8 H21 V20 H3 Z M3 6 V4 H10 L12 6 H19 V8",
    )

@Composable
internal fun SettingsIcon(
    id: String,
    modifier: Modifier = Modifier.size(18.dp),
    color: Color = PanelStyle.muted,
) {
    val path =
        remember(id) {
            PathParser()
                .parsePathString(settingsIconPaths[id] ?: settingsIconPaths.getValue("modules"))
                .toPath()
        }
    Canvas(modifier) {
        val scale = min(size.width, size.height) / 24f
        if (scale <= 0f) return@Canvas
        // Keep strokes at whole physical pixels, even at the game's fractional UI densities.
        val strokePixels = 1.25.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        withTransform({
            translate((size.width - 24f * scale) / 2f, (size.height - 24f * scale) / 2f)
            scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero)
        }) {
            drawPath(
                path,
                color,
                style =
                    Stroke(strokePixels / scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
