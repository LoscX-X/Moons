package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.utils.io.EmbeddedResources
import org.jetbrains.skia.Image as SkiaImage

/** Shared panel palette, dimensions, fonts and icon loaders. */
internal object PanelStyle {
    val toolbar = Color(0xFF2B2B2D)
    val panel = Color(0xFF2B2B2D)
    val header = Color(0xFF2B2B2D)
    val row = Color(0xFF343537)
    val rowEnabled = row
    val setting = Color(0xFF303133)
    val field = Color(0xFF252627)
    val border = Color(0xFF494B4D)
    val track = Color(0xFF4A4D4F)
    val text = Color(0xFFE9EAEC)
    val muted = Color(0xFFA4A6A9)
    val dim = Color(0xFF85888D)
    val hover = Color(0xFF3A3C3E)
    val selected = Color(0xFF454749)
    val controlActive = Color(0xFFEDEEEF)
    val controlInactive = Color(0xFF171819)
    val controlSoft = Color(0xFF454749)
    val windowShape = RoundedCornerShape(20.dp)
    val cardShape = RoundedCornerShape(10.dp)
    val controlShape = RoundedCornerShape(8.dp)

    private data class ThemeColor(val value: String, val color: Color)

    @Volatile private var cachedTheme: ThemeColor? = null
    val accent: Color
        get() {
            val theme = guiThemeValue()
            val cached = cachedTheme
            if (cached != null && theme == cached.value) return cached.color
            return parseColor(theme).also { cachedTheme = ThemeColor(theme, it) }
        }

    val accentBright: Color
        get() = accent

    val accentSoft: Color
        get() = accent.copy(alpha = 0.22f)

    val danger = Color(0xFFD8776D)
}

internal const val PANEL_WIDTH = 152f
internal const val PANEL_GAP = 9f
internal const val PANEL_MARGIN = 12f
internal const val CONTROL_WIDTH = 176f
internal const val GUI_THEME_KEY = "clickgui.theme.color"
internal const val OPEN_SECTIONS_KEY = "clickgui.openSections"
internal const val DEFAULT_GUI_THEME = "#22c55e"

internal fun guiThemeValue(): String =
    Settings.getString(GUI_THEME_KEY, DEFAULT_GUI_THEME).let {
        if (it.lowercase() in setOf("#b29a65", "#f0b43c")) DEFAULT_GUI_THEME else it
    }

internal val CLIENT_SETTINGS_MODULE_IDS = setOf("chatprefix", "armorhide", "staticfov")

internal object PanelFontResource

internal object TrimIconResource

internal object GuiIconResource

internal val PanelFontBytes: ByteArray by lazy {
    EmbeddedResources.readRequiredBytes(
        PanelFontResource::class.java.classLoader,
        "assets/moons/font/inter-frozen-medium.otf",
        "Missing embedded ClickGUI font",
    )
}

internal val TrimIconImages = mutableMapOf<String, ImageBitmap>()

internal fun trimIcon(id: String): ImageBitmap? =
    synchronized(TrimIconImages) {
        TrimIconImages[id]
            ?: runCatching {
                    val bytes =
                        EmbeddedResources.readRequiredBytes(
                            TrimIconResource::class.java.classLoader,
                            "assets/moons/textures/trim_template/$id.png",
                            "Required value was null.",
                        )
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                }
                .getOrNull()
                ?.also { TrimIconImages[id] = it }
    }

internal val GuiIconImages = mutableMapOf<String, ImageBitmap>()

internal fun guiIcon(id: String): ImageBitmap? =
    synchronized(GuiIconImages) {
        GuiIconImages[id]
            ?: runCatching {
                    val bytes =
                        EmbeddedResources.readMimeBase64(
                            GuiIconResource::class.java.classLoader,
                            "assets/moons/textures/gui/$id.png.b64",
                            "Required value was null.",
                        )
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                }
                .getOrNull()
                ?.also { GuiIconImages[id] = it }
    }

@OptIn(ExperimentalTextApi::class)
internal val PanelFontFamily =
    FontFamily(Font("moons-inter-medium", PanelFontBytes, FontWeight.Medium))
