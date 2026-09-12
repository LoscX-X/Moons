package com.blanoir.moons.client.ui.clickgui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.ClientBranding
import com.blanoir.moons.client.module.framework.ModuleCategories
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.utils.ui.ColorEditor
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
    val shape = PanelStyle.cardShape
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
                        ControlHeaderImageButton("back", "Back to modules") {
                            onSelectSection("home")
                        }
                    }
                    Text(
                        "Settings",
                        color = PanelStyle.text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        ControlHeaderImageButton("close", "Close settings") {
                            onSelectSection("home")
                        }
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
                ControlHeaderImageButton("settings", "Settings") {
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
            PanelSearch(
                value = search,
                onChange = onSearchChange,
                compact = true,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )

            Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                sections.forEach { section ->
                    ControlMenuRow(
                        label = section.label,
                        selected = section.id in openSections && search.isEmpty(),
                        icon = section.iconId,
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
                icon = "modules",
                onClick = { onSelectSection("all") },
            )
            ControlMenuRow(
                label = "Configs",
                selected = false,
                icon = "configs",
                onClick = {
                    onBindingModuleChange(null)
                    com.blanoir.moons.client.config.Settings.setString(
                        "clickgui.settings.page",
                        "Configs",
                    )
                    ModuleGui.setLayout(null, "settings")
                    onMutated()
                },
            )
            ControlMenuRow(
                label = "Close",
                selected = false,
                icon = "close",
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
            "#22c55e",
            "#ff4050",
            "#c84cff",
            "#6c7cff",
            "#27b8ff",
            "#22c98b",
            "#a4a6a9",
            "#f06aa6",
        )
    val clientModules = remember {
        ModuleRegistry.modules().filter { it.id() in CLIENT_SETTINGS_MODULE_IDS }
    }
    val expandedClientModules = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxWidth()) {
        SettingsSectionHeader("APPEARANCE")
        ControlMenuRow(
            label = "Settings layout",
            selected = false,
            trailing = "Open",
            onClick = {
                onBindingModuleChange(null)
                ModuleGui.setLayout(null, "settings")
                onMutated()
            },
        )
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
                                if (guiThemeValue().equals(hex, ignoreCase = true)) 2.dp else 1.dp,
                                if (guiThemeValue().equals(hex, ignoreCase = true)) PanelStyle.text
                                else PanelStyle.border,
                                CircleShape,
                            )
                            .clickable { onThemeChange(hex) }
                    )
                }
            }
            ColorEditor(guiThemeValue(), onThemeChange)
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
private fun ControlHeaderImageButton(
    icon: String?,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(22.dp)
            .clip(PanelStyle.controlShape)
            .background(if (hover) PanelStyle.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            SettingsIcon(
                icon,
                Modifier.size(15.dp).semantics { this.contentDescription = contentDescription },
            )
        }
    }
}

@Composable
private fun ControlMenuRow(
    label: String,
    selected: Boolean,
    trailing: String = "›",
    icon: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val background by
        animateColorAsState(
            if (selected) PanelStyle.selected
            else if (hover) PanelStyle.hover else Color.Transparent,
            animationSpec = tween(100),
        )
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .height(24.dp)
            .clip(PanelStyle.controlShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIcon(
                icon,
                Modifier.size(14.dp),
                if (selected) PanelStyle.text else PanelStyle.muted,
            )
            Spacer(Modifier.width(11.dp))
        }
        Text(
            label,
            color = if (selected) PanelStyle.text else PanelStyle.muted,
            fontSize = 9.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing == "›") SettingsIcon("chevron", Modifier.size(12.dp), PanelStyle.dim)
        else Text(trailing, color = PanelStyle.dim, fontSize = 8.sp)
    }
}
