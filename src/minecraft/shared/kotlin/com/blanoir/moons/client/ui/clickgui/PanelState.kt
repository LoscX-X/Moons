package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module
import com.blanoir.moons.client.utils.render.ColorCodec
import com.blanoir.moons.client.utils.text.NumberText
import com.google.gson.JsonArray
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Persisted panel state and value conversion helpers. */
internal fun matchesSearch(module: Module, search: String): Boolean {
    val query = search.trim().lowercase(Locale.ROOT)
    return query.isEmpty() ||
        module.name().lowercase(Locale.ROOT).contains(query) ||
        module.category().lowercase(Locale.ROOT).contains(query) ||
        module.settings().any { it.name().lowercase(Locale.ROOT).contains(query) }
}

internal fun moduleEnabled(module: Module) =
    runCatching { module.enabled().asBoolean }.getOrDefault(false)

internal fun panelPositionKey(category: String) =
    "clickgui.panel.${category.lowercase(Locale.ROOT)}.position"

internal fun loadOpenSections(): Set<String> =
    Settings.getString(OPEN_SECTIONS_KEY, "combat")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

internal fun saveOpenSections(sections: Set<String>) {
    Settings.setString(OPEN_SECTIONS_KEY, sections.sorted().joinToString(","))
}

internal fun loadPanelPosition(category: String): Offset? {
    val parts = Settings.getString(panelPositionKey(category), "").split(',')
    if (parts.size != 2) return null
    val x = parts[0].toFloatOrNull() ?: return null
    val y = parts[1].toFloatOrNull() ?: return null
    return if (x.isFinite() && y.isFinite()) Offset(x, y) else null
}

internal fun savePanelPosition(category: String, position: Offset) {
    Settings.setString(panelPositionKey(category), "${position.x},${position.y}")
}

internal fun clampPanelPosition(position: Offset, width: Float, height: Float): Offset {
    val maxX = (width - PANEL_WIDTH * 0.65f).coerceAtLeast(0f)
    val minX = (CONTROL_WIDTH + PANEL_MARGIN).coerceAtMost(maxX)
    return Offset(
        position.x.coerceIn(minX, maxX),
        position.y.coerceIn(0f, (height - 28f).coerceAtLeast(0f)),
    )
}

internal fun jsonRange(low: Double, high: Double) =
    JsonArray().apply {
        add(low)
        add(high)
    }

internal fun snap(value: Double, min: Double, max: Double, step: Double?): Double {
    val snapped =
        if (step != null && step > 0.0) {
            min + ((value - min) / step).roundToInt() * step
        } else value
    return snapped.coerceIn(min, max)
}

internal fun formatValue(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else NumberText.trimmedDecimal(value, 6)

internal fun isPotentialNumber(value: String): Boolean =
    value.matches(Regex("^[+-]?(?:\\d*(?:\\.\\d*)?)(?:[eE][+-]?\\d*)?$"))

internal fun parseColor(value: String): Color =
    runCatching {
            val raw = value.removePrefix("#")
            when (raw.length) {
                6,
                8 -> Color(ColorCodec.parseRgbOrArgb(raw))
                else -> PanelStyle.dim
            }
        }
        .getOrDefault(PanelStyle.dim)

internal fun isCompleteColor(value: String): Boolean = value.matches(Regex("^#[0-9a-fA-F]{6}$"))
