package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.blanoir.moons.client.config.Settings
import org.jetbrains.skia.Image as SkiaImage
import java.util.Base64

/** Shared panel palette, dimensions, fonts and icon loaders. */
internal object PanelStyle {
    val toolbar = Color(0xF20F0F10)
    val panel = Color(0xF5171718)
    val header = Color(0xFC111112)
    val row = Color(0xFF1B1B1D)
    val rowEnabled = Color(0xFF252118)
    val setting = Color(0xFF141415)
    val field = Color(0xFF202022)
    val border = Color(0xFF303033)
    val track = Color(0xFF37373A)
    val text = Color(0xFFF0EEE9)
    val muted = Color(0xFF999791)
    val dim = Color(0xFF69686A)
    val accent: Color get() = parseColor(Settings.getString(GUI_THEME_KEY, DEFAULT_GUI_THEME))
    val accentBright: Color get() = accent
    val accentSoft: Color get() = accent.copy(alpha = 0.22f)
    val danger = Color(0xFFD8776D)
}

internal const val PANEL_WIDTH = 152f
internal const val PANEL_GAP = 9f
internal const val PANEL_MARGIN = 12f
internal const val CONTROL_WIDTH = 176f
internal const val GUI_THEME_KEY = "clickgui.theme.color"
internal const val OPEN_SECTIONS_KEY = "clickgui.openSections"
internal const val DEFAULT_GUI_THEME = "#b29a65"
internal val CLIENT_SETTINGS_MODULE_IDS = setOf("chatprefix", "armorhide", "staticfov")
internal object PanelFontResource
internal object TrimIconResource
internal object GuiIconResource

internal val PanelFontBytes: ByteArray by lazy {
    requireNotNull(
        PanelFontResource::class.java.classLoader
            .getResourceAsStream("assets/moons/font/inter-frozen-medium.otf")
    ) { "Missing embedded ClickGUI font" }.use { it.readAllBytes() }
}

internal val TrimIconImages = mutableMapOf<String, ImageBitmap>()

internal fun trimIcon(id: String): ImageBitmap? = synchronized(TrimIconImages) {
    TrimIconImages[id] ?: runCatching {
        val bytes = requireNotNull(
            TrimIconResource::class.java.classLoader.getResourceAsStream(
                "assets/moons/textures/trim_template/$id.png"
            )
        ).use { it.readAllBytes() }
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()?.also { TrimIconImages[id] = it }
}

internal val GuiIconImages = mutableMapOf<String, ImageBitmap>()

internal fun guiIcon(id: String): ImageBitmap? = synchronized(GuiIconImages) {
    GuiIconImages[id] ?: runCatching {
        val encoded = requireNotNull(
            GuiIconResource::class.java.classLoader.getResourceAsStream(
                "assets/moons/textures/gui/$id.png.b64"
            )
        ).bufferedReader().use { it.readText() }
        SkiaImage.makeFromEncoded(Base64.getMimeDecoder().decode(encoded)).toComposeImageBitmap()
    }.getOrNull()?.also { GuiIconImages[id] = it }
}

@OptIn(ExperimentalTextApi::class)
internal val PanelFontFamily = FontFamily(
    Font("moons-inter-medium", PanelFontBytes, FontWeight.Medium)
)
