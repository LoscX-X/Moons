package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
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
import com.blanoir.moons.client.config.ClientBranding
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.utils.io.EmbeddedResources
import org.jetbrains.skia.Image as SkiaImage

/** Shared panel palette, dimensions, fonts and icon loaders. */
internal object PanelStyle {
    val toolbar = Color(0xFF19191C)
    val panel = Color(0xFF1B1B1E)
    val header = Color(0xFF19191C)
    val row = Color(0xFF232326)
    val rowEnabled = row
    val setting = Color(0xFF1F1F22)
    val field = Color(0xFF161619)
    val border = Color(0xFF39393E)
    val track = Color(0xFF45454D)
    val text = Color(0xFFE8E8EC)
    val muted = Color(0xFFA5A5AE)
    val dim = Color(0xFF888891)
    val hover = Color(0xFF2C2C31)
    val selected = Color(0xFF303035)
    val controlActive = Color(0xFFDCDCE2)
    val controlInactive = Color(0xFF242428)
    val controlSoft = Color(0xFF51515A)
    val scrim = Color(0x8809090B)
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
internal const val DEFAULT_GUI_THEME = "#dcdce2"

internal fun guiBrandName(): String =
    ClientBranding.name().let {
        if (it == ClientBranding.DEFAULT_NAME) "moons." else it.trimEnd('.') + "."
    }

/** Include Material controls used by YSM, so they share the ClickGUI palette. */
internal fun panelColorScheme() =
    darkColorScheme(
        primary = PanelStyle.accent,
        onPrimary = PanelStyle.controlInactive,
        primaryContainer = PanelStyle.selected,
        onPrimaryContainer = PanelStyle.text,
        secondary = PanelStyle.muted,
        onSecondary = PanelStyle.controlInactive,
        secondaryContainer = PanelStyle.selected,
        onSecondaryContainer = PanelStyle.text,
        tertiary = PanelStyle.text,
        onTertiary = PanelStyle.controlInactive,
        tertiaryContainer = PanelStyle.selected,
        onTertiaryContainer = PanelStyle.text,
        background = PanelStyle.panel,
        onBackground = PanelStyle.text,
        surface = PanelStyle.panel,
        onSurface = PanelStyle.text,
        surfaceVariant = PanelStyle.row,
        onSurfaceVariant = PanelStyle.muted,
        surfaceTint = Color.Transparent,
        surfaceBright = PanelStyle.hover,
        surfaceDim = PanelStyle.field,
        surfaceContainer = PanelStyle.setting,
        surfaceContainerHigh = PanelStyle.row,
        surfaceContainerHighest = PanelStyle.selected,
        surfaceContainerLow = PanelStyle.header,
        surfaceContainerLowest = PanelStyle.field,
        inverseSurface = PanelStyle.controlActive,
        inverseOnSurface = PanelStyle.controlInactive,
        inversePrimary = PanelStyle.controlInactive,
        outline = PanelStyle.border,
        outlineVariant = PanelStyle.border,
        error = PanelStyle.danger,
        onError = PanelStyle.controlInactive,
    )

internal fun guiThemeValue(): String =
    Settings.getString(GUI_THEME_KEY, DEFAULT_GUI_THEME).let {
        if (it.lowercase() in setOf("#b29a65", "#f0b43c")) DEFAULT_GUI_THEME else it
    }

internal val CLIENT_SETTINGS_MODULE_IDS = setOf("chatprefix", "armorhide", "freelook", "staticfov", "movefix")

internal object PanelFontResource

internal object GuiIconResource

internal val PanelFontBytes: ByteArray by lazy {
    EmbeddedResources.readRequiredBytes(
        PanelFontResource::class.java.classLoader,
        "assets/moons/font/inter-frozen-medium.otf",
        "Missing embedded ClickGUI font",
    )
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
