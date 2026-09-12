package com.blanoir.moons.client.ui.clickgui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.ClientBranding
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleCategories
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module
import com.blanoir.moons.client.utils.ui.ColorEditor

/** Settings-window layout sharing the existing module and setting mutation paths. */
@Composable
internal fun MoonsSettingsClickGui(
    bindingModuleId: String?,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit,
    onEditHudLayout: () -> Unit,
    onClose: () -> Unit,
    modulesOverride: List<Module>? = null,
    onToggle: (Module) -> Unit = { module ->
        ModuleRegistry.setEnabled(module.id(), !moduleEnabled(module))
        onMutated()
    },
) {
    ClickGuiRevision.intValue
    val allModules = remember(modulesOverride) { modulesOverride ?: ModuleRegistry.modules() }
    val categories =
        remember(allModules) {
            val available = allModules.map { it.category() }.distinct()
            ModuleCategories.ordered().filter { it in available } +
                available.filterNot { it in ModuleCategories.ordered() }
        }
    var category by remember {
        mutableStateOf(
            Settings.getString("clickgui.settings.page", "All modules")
                .takeIf { it == "Configs" }
                .orEmpty()
                .ifEmpty { "All modules" }
        )
    }
    var search by remember { mutableStateOf("") }
    var configuration by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val selected = allModules.firstOrNull { it.id() == selectedId }
    val modules =
        remember(allModules, category, search) {
            allModules.filter {
                it.id() !in CLIENT_SETTINGS_MODULE_IDS &&
                    (category == "All modules" || it.category() == category) &&
                    matchesSearch(it, search)
            }
        }
    val selectModule: (Module) -> Unit = {
        focus.clearFocus()
        onBindingModuleChange(null)
        if (category == "General") category = "All modules"
        selectedId = it.id()
        configuration = true
    }

    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = PanelStyle.accent,
                surface = PanelStyle.panel,
                onSurface = PanelStyle.text,
            )
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = PanelFontFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
                lineHeightStyle =
                    LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                letterSpacing = 0.sp,
                color = PanelStyle.text,
            )
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Color(0x66303436)),
                contentAlignment = Alignment.Center,
            ) {
                val windowWidth = (maxWidth - 24.dp).coerceAtMost(880.dp).coerceAtLeast(0.dp)
                val windowHeight = (maxHeight - 24.dp).coerceAtMost(640.dp).coerceAtLeast(0.dp)
                val compact = windowWidth < 650.dp
                Row(
                    Modifier.width(windowWidth)
                        .height(windowHeight)
                        .clip(PanelStyle.windowShape)
                        .background(PanelStyle.panel)
                        .border(1.dp, PanelStyle.border.copy(alpha = .5f), PanelStyle.windowShape)
                ) {
                    Column(
                        Modifier.width(if (compact) 54.dp else 190.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp)
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(64.dp)
                                .padding(start = if (compact) 10.dp else 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (compact) SettingsIcon("settings")
                            else Text("Settings", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            (listOf("General", "Configs", "All modules") + categories).forEach {
                                item ->
                                val icon =
                                    when (item) {
                                        "General" -> "settings"
                                        "Configs" -> "configs"
                                        "All modules" -> "modules"
                                        else -> categoryIconId(item) ?: "modules"
                                    }
                                SettingsNavigation(item, icon, category == item, compact) {
                                    focus.clearFocus()
                                    onBindingModuleChange(null)
                                    category = item
                                    Settings.setString("clickgui.settings.page", item)
                                    search = ""
                                    configuration = false
                                }
                            }
                        }
                        if (!compact)
                            Text(
                                ClientBranding.name(),
                                fontSize = 11.sp,
                                color = PanelStyle.dim,
                                modifier = Modifier.padding(12.dp, 16.dp),
                            )
                    }
                    Column(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .padding(start = if (compact) 12.dp else 26.dp, end = 24.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(56.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsAction(
                                if (compact) "Config file" else "Open configuration file"
                            ) {
                                fileError = ModuleGui.openConfigurationFile()
                            }
                            Spacer(Modifier.width(12.dp))
                            SettingsIconButton("close", "Close ClickGUI", onClose)
                        }
                        Text(
                            if (category == "All modules") "Modules" else category,
                            fontSize = 20.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(9.dp))
                        Text(
                            if (category == "Configs")
                                "Create, save and load your local configurations."
                            else if (category == "General")
                                "Manage your interface and client preferences."
                            else "Configure and inspect the modules in your client.",
                            color = PanelStyle.muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        if (fileError.isNotEmpty())
                            Text(
                                fileError,
                                color = PanelStyle.danger,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        Spacer(Modifier.height(18.dp))
                        if (category == "Configs") {
                            ConfigProfilesPage {
                                onBindingModuleChange(null)
                                onMutated()
                            }
                        } else if (category == "General") {
                            GeneralSettingsPage(
                                allModules,
                                onMutated,
                                onEditHudLayout,
                                selectModule,
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth().height(30.dp),
                                horizontalArrangement = Arrangement.spacedBy(26.dp),
                            ) {
                                SettingsTab(
                                    if (compact) "Configuration" else "Module configuration",
                                    configuration,
                                ) {
                                    focus.clearFocus()
                                    configuration = true
                                }
                                SettingsTab(
                                    if (compact) "Modules" else "Module list",
                                    !configuration,
                                ) {
                                    focus.clearFocus()
                                    onBindingModuleChange(null)
                                    configuration = false
                                }
                            }
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(1.dp)
                                    .background(PanelStyle.border.copy(alpha = .65f))
                            )
                            Spacer(Modifier.height(14.dp))
                            if (configuration) {
                                ModuleConfiguration(
                                    selected,
                                    bindingModuleId,
                                    onBindingModuleChange,
                                    onMutated,
                                    onToggle,
                                )
                            } else {
                                PanelSearch(search, { search = it.take(96) })
                                Row(
                                    Modifier.padding(top = 16.dp, bottom = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Module list",
                                        fontSize = 13.sp,
                                        lineHeight = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        modules.size.toString(),
                                        color = PanelStyle.muted,
                                        fontSize = 12.sp,
                                    )
                                }
                                key(category, search) {
                                    SettingsModuleList(
                                        modules,
                                        compact,
                                        onToggle,
                                        selectModule,
                                        Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigation(
    label: String,
    icon: String,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .height(42.dp)
            .clip(PanelStyle.cardShape)
            .background(
                if (selected) PanelStyle.selected
                else if (hover) PanelStyle.hover else Color.Transparent
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(
            icon,
            Modifier.size(17.dp),
            if (selected) PanelStyle.text else PanelStyle.muted,
        )
        if (!compact) {
            Spacer(Modifier.width(10.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun SettingsAction(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    Box(
        Modifier.height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (active) PanelStyle.selected
                else if (hover) PanelStyle.hover else Color.Transparent
            )
            .border(1.dp, PanelStyle.border, RoundedCornerShape(15.dp))
            .hoverable(interaction, enabled = enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            color = if (enabled) PanelStyle.text else PanelStyle.dim,
        )
    }
}

@Composable
private fun SettingsIconButton(icon: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(PanelStyle.controlShape).clickable(onClick = onClick).semantics {
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) {
        SettingsIcon(icon, Modifier.size(17.dp), PanelStyle.text)
    }
}

@Composable
private fun SettingsTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(IntrinsicSize.Max).fillMaxHeight().clickable(onClick = onClick),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            color = if (selected) PanelStyle.text else PanelStyle.muted,
        )
        Box(
            Modifier.fillMaxWidth()
                .height(2.dp)
                .background(if (selected) PanelStyle.text else Color.Transparent, CircleShape)
        )
    }
}

@Composable
private fun SettingsModuleList(
    modules: List<Module>,
    compact: Boolean,
    onToggle: (Module) -> Unit,
    onSelect: (Module) -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberLazyListState()
    Box(modifier.fillMaxWidth()) {
        if (modules.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SettingsIcon("search", Modifier.size(26.dp))
                Spacer(Modifier.height(12.dp))
                Text("No modules found", fontSize = 14.sp)
                Text(
                    "Try another name or category.",
                    fontSize = 12.sp,
                    color = PanelStyle.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(end = 10.dp),
                state = scroll,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 22.dp),
            ) {
                items(modules.chunked(if (compact) 1 else 2), key = { it.first().id() }) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { module ->
                            SettingsModuleCard(
                                module,
                                { onToggle(module) },
                                { onSelect(module) },
                                Modifier.weight(1f),
                            )
                        }
                        if (!compact && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SettingsModuleCard(
    module: Module,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickGuiRevision.intValue
    val enabled = moduleEnabled(module)
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val background by
        animateColorAsState(if (hover) PanelStyle.hover else PanelStyle.row, tween(90))
    Row(
        modifier
            .height(56.dp)
            .clip(PanelStyle.cardShape)
            .background(background)
            .border(1.dp, PanelStyle.border, PanelStyle.cardShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(start = 15.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            module.name(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription = "Toggle ${module.name()}"
                    stateDescription = if (enabled) "Enabled" else "Disabled"
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (enabled) {
                Box(Modifier.size(6.dp).background(PanelStyle.controlActive, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                if (enabled) "Enabled" else "Disabled",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = if (enabled) PanelStyle.controlInactive else PanelStyle.controlActive,
                modifier =
                    Modifier.background(
                            if (enabled) PanelStyle.controlActive else PanelStyle.controlInactive,
                            RoundedCornerShape(5.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        SettingsIcon("chevron", Modifier.size(14.dp))
    }
}

@Composable
private fun ModuleConfiguration(
    module: Module?,
    bindingModuleId: String?,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit,
    onToggle: (Module) -> Unit,
) {
    if (module == null) {
        Text(
            "Select a module from the list to configure it.",
            color = PanelStyle.muted,
            modifier = Modifier.padding(top = 20.dp),
        )
        return
    }
    ClickGuiRevision.intValue
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsModuleCard(module, { onToggle(module) }, {})
            Text(module.category(), color = PanelStyle.muted, fontSize = 12.sp)
            val density = LocalDensity.current
            // Enlarge labels without scaling the shared one-dp strokes or corner radii.
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale * 1.45f)
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(PanelStyle.controlShape)
                        .background(PanelStyle.setting)
                        .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KeybindSetting(module, bindingModuleId, onBindingModuleChange)
                    module
                        .settings()
                        .filter { it.isVisible }
                        .forEach { setting ->
                            key(module.id(), setting.id()) {
                                CompactSetting(module, setting, onMutated)
                            }
                        }
                }
            }
            if (module.settings().none { it.isVisible })
                Text(
                    "This module has no additional options.",
                    fontSize = 12.sp,
                    color = PanelStyle.muted,
                )
            if (bindingModuleId == module.id())
                Text(
                    "Press a key or mouse button. Esc cancels; Backspace clears the binding.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = PanelStyle.muted,
                )
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun GeneralSettingsPage(
    modules: List<Module>,
    onMutated: () -> Unit,
    onEditHudLayout: () -> Unit,
    onSelect: (Module) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Appearance", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsAction("Settings layout", active = true) {}
            SettingsAction("Panel layout") {
                ModuleGui.setLayout(null, "panels")
                onMutated()
            }
        }
        Text("Accent color", color = PanelStyle.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("#22c55e", "#a4a6a9", "#5d9cec", "#ae8de6").forEach { hex ->
                Box(
                    Modifier.size(24.dp)
                        .clip(CircleShape)
                        .background(parseColor(hex))
                        .border(
                            if (guiThemeValue() == hex) 2.dp else 1.dp,
                            if (guiThemeValue() == hex) PanelStyle.text else PanelStyle.border,
                            CircleShape,
                        )
                        .clickable {
                            Settings.setString(GUI_THEME_KEY, hex)
                            onMutated()
                        }
                        .semantics { contentDescription = "Accent $hex" }
                )
            }
        }
        Box(Modifier.widthIn(max = 280.dp)) {
            ColorEditor(guiThemeValue()) {
                Settings.setString(GUI_THEME_KEY, it)
                onMutated()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PanelStyle.border))
        Text("HUD", fontWeight = FontWeight.SemiBold)
        SettingsAction("Edit HUD layout", onClick = onEditHudLayout)
        Text("Client preferences", fontWeight = FontWeight.SemiBold)
        modules
            .filter { it.id() in CLIENT_SETTINGS_MODULE_IDS }
            .forEach { module ->
                SettingsModuleCard(
                    module,
                    {
                        ModuleRegistry.setEnabled(module.id(), !moduleEnabled(module))
                        onMutated()
                    },
                    { onSelect(module) },
                )
            }
    }
}
