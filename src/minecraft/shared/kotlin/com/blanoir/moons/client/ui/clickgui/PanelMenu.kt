package com.blanoir.moons.client.ui.clickgui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.ClientBranding
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleCategories
import com.blanoir.moons.client.module.framework.ModuleRegistry
import java.util.Locale
import kotlin.math.roundToInt

/** Left control menu and client-wide GUI settings. */
internal data class ControlSection(
    val id: String,
    val label: String,
    val category: String,
    val iconId: String? = null,
)

internal fun categoryIconId(category: String): String? =
    when (category) {
        ModuleCategories.COMBAT -> "combat"
        ModuleCategories.MOVEMENT -> "movement"
        ModuleCategories.RENDER -> "render"
        ModuleCategories.MISC -> "misc"
        ModuleCategories.EXPERIMENT -> "experiment"
        ModuleCategories.WORLD -> "world"
        ModuleCategories.PLAYER -> "player"
        ModuleCategories.NETWORK -> "network"
        else -> null
    }

@Composable
internal fun ControlMenu(
    sections: List<ControlSection>,
    openSections: Set<String>,
    settingsOpen: Boolean,
    bindingModuleId: String?,
    search: String,
    moduleCount: Int,
    onSelectSection: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onBindingModuleChange: (String?) -> Unit,
    onThemeChange: (String) -> Unit,
    onMutated: () -> Unit,
    onEditHudLayout: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val density = LocalDensity.current
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier
            .offset {
                IntOffset(
                    (menuOffset.x * density.density).roundToInt(),
                    (menuOffset.y * density.density).roundToInt(),
                )
            }
            .width(PANEL_WIDTH.dp)
            .shadow(18.dp, shape)
            .clip(shape)
            .background(PanelStyle.panel)
            .border(1.dp, PanelStyle.border, shape)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(42.dp)
                .background(PanelStyle.toolbar)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        menuOffset +=
                            Offset(
                                amount.x / density.density,
                                amount.y / density.density,
                            )
                    }
                }
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (settingsOpen) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.CenterStart)) {
                        ControlHeaderButton("‹") { onSelectSection("home") }
                    }
                    Text(
                        "Settings",
                        color = PanelStyle.text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Center).offset(x = (-3).dp),
                    )
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        ControlHeaderButton("×") { onSelectSection("home") }
                    }
                }
            } else {
                Text(
                    ClientBranding.name().uppercase(Locale.ROOT),
                    color = PanelStyle.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ControlHeaderImageButton(guiIcon("settings"), "Settings") {
                    onSelectSection("settings")
                }
            }
        }

        if (settingsOpen) {
            GuiSettings(
                onThemeChange = onThemeChange,
                bindingModuleId = bindingModuleId,
                onBindingModuleChange = onBindingModuleChange,
                onMutated = onMutated,
                onEditHudLayout = onEditHudLayout,
            )
        } else {
            BasicTextField(
                value = search,
                onValueChange = onSearchChange,
                singleLine = true,
                textStyle =
                    TextStyle(
                        color = PanelStyle.text,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        fontFamily = PanelFontFamily,
                    ),
                cursorBrush = SolidColor(PanelStyle.accent),
                modifier = Modifier.fillMaxWidth().height(32.dp).background(PanelStyle.setting),
                decorationBox = { input ->
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchIcon(Modifier.size(10.dp).offset(y = 2.dp))
                        Spacer(Modifier.width(7.dp))
                        Box(
                            Modifier.weight(1f).height(18.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (search.isEmpty()) {
                                Text("Search modules", color = PanelStyle.dim, fontSize = 8.sp)
                            }
                            input()
                        }
                        if (search.isNotEmpty()) {
                            Text(
                                "×",
                                color = PanelStyle.muted,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { onSearchChange("") }.padding(3.dp),
                            )
                        }
                    }
                },
            )

            Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                sections.forEach { section ->
                    ControlMenuRow(
                        label = section.label,
                        selected = section.id in openSections && search.isEmpty(),
                        icon = remember(section.iconId) { section.iconId?.let(::guiIcon) },
                        onClick = { onSelectSection(section.id) },
                    )
                }
            }

            Text(
                "GENERAL",
                color = PanelStyle.dim,
                fontSize = 6.sp,
                letterSpacing = 1.sp,
                modifier =
                    Modifier.fillMaxWidth()
                        .background(PanelStyle.setting)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
            )
            ControlMenuRow(
                label = "All modules",
                selected = sections.all { it.id in openSections } && search.isEmpty(),
                trailing = moduleCount.toString(),
                icon = remember { guiIcon("all_modules") },
                onClick = { onSelectSection("all") },
            )
            ControlMenuRow(
                label = "Close",
                selected = false,
                icon = remember { guiIcon("close") },
                onClick = onClose,
            )
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun GuiSettings(
    onThemeChange: (String) -> Unit,
    bindingModuleId: String?,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit,
    onEditHudLayout: () -> Unit,
) {
    val presets =
        listOf(
            "#b29a65",
            "#ff4050",
            "#c84cff",
            "#6c7cff",
            "#27b8ff",
            "#22c98b",
            "#f0b43c",
            "#f06aa6",
        )
    val savedTheme = Settings.getString(GUI_THEME_KEY, DEFAULT_GUI_THEME)
    val clientModules = remember {
        ModuleRegistry.modules().filter { it.id() in CLIENT_SETTINGS_MODULE_IDS }
    }
    val expandedClientModules = remember { mutableStateMapOf<String, Boolean>() }
    var customTheme by remember { mutableStateOf(savedTheme) }
    LaunchedEffect(savedTheme) { customTheme = savedTheme }
    Column(Modifier.fillMaxWidth()) {
        SettingsSectionHeader("APPEARANCE")
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text("GUI theme", color = PanelStyle.muted, fontSize = 7.5.sp)
            Row(
                Modifier.fillMaxWidth().height(30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                presets.forEach { hex ->
                    val color = parseColor(hex)
                    Box(
                        Modifier.size(13.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                if (
                                    Settings.getString(GUI_THEME_KEY, DEFAULT_GUI_THEME)
                                        .equals(hex, ignoreCase = true)
                                )
                                    2.dp
                                else 1.dp,
                                if (
                                    Settings.getString(GUI_THEME_KEY, DEFAULT_GUI_THEME)
                                        .equals(hex, ignoreCase = true)
                                )
                                    PanelStyle.text
                                else PanelStyle.border,
                                CircleShape,
                            )
                            .clickable { onThemeChange(hex) }
                    )
                }
            }
            BasicTextField(
                value = customTheme,
                onValueChange = { raw ->
                    customTheme = raw.take(7)
                    if (isCompleteColor(customTheme))
                        onThemeChange(customTheme.lowercase(Locale.ROOT))
                },
                singleLine = true,
                textStyle =
                    TextStyle(
                        color = PanelStyle.muted,
                        fontSize = 7.sp,
                        fontFamily = PanelFontFamily,
                    ),
                cursorBrush = SolidColor(PanelStyle.accent),
                modifier =
                    Modifier.fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(PanelStyle.field)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PanelStyle.border))
        SettingsSectionHeader("HUD")
        ControlMenuRow(
            label = "HUD layout",
            selected = false,
            trailing = "Edit",
            onClick = onEditHudLayout,
        )
        Text(
            "Drag components; use the corner or right click to resize.",
            color = PanelStyle.dim,
            fontSize = 6.5.sp,
            lineHeight = 9.sp,
            modifier =
                Modifier.fillMaxWidth()
                    .background(PanelStyle.setting)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
        )
        SettingsSectionHeader("CLIENT")
        clientModules.forEach { module ->
            ModuleRow(
                module = module,
                expanded = expandedClientModules[module.id()] == true,
                bindingModuleId = bindingModuleId,
                onExpand = {
                    expandedClientModules[module.id()] = expandedClientModules[module.id()] != true
                },
                onBindingModuleChange = onBindingModuleChange,
                onMutated = onMutated,
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(label: String) {
    Text(
        label,
        color = PanelStyle.dim,
        fontSize = 6.sp,
        letterSpacing = 1.sp,
        modifier =
            Modifier.fillMaxWidth()
                .background(PanelStyle.setting)
                .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ControlHeaderButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PanelStyle.muted, fontSize = 11.sp)
    }
}

@Composable
private fun ControlHeaderImageButton(
    icon: ImageBitmap?,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(15.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(PanelStyle.muted),
            )
        }
    }
}

@Composable
private fun SearchIcon(modifier: Modifier = Modifier) {
    val icon = remember { guiIcon("search") }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(PanelStyle.dim),
        )
    }
}

@Composable
private fun ControlMenuRow(
    label: String,
    selected: Boolean,
    trailing: String = "›",
    icon: ImageBitmap? = null,
    onClick: () -> Unit,
) {
    val background by
        animateColorAsState(
            if (selected) PanelStyle.accentSoft else Color.Transparent,
            animationSpec = tween(100),
        )
    Row(
        Modifier.fillMaxWidth()
            .height(24.dp)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(if (selected) PanelStyle.text else PanelStyle.muted),
            )
            Spacer(Modifier.width(11.dp))
        }
        Text(
            label,
            color = if (selected) PanelStyle.text else PanelStyle.muted,
            fontSize = 9.sp,
            modifier = Modifier.weight(1f),
        )
        Text(trailing, color = PanelStyle.dim, fontSize = 8.sp)
    }
}
