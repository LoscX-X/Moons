package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleCategories
import com.blanoir.moons.client.module.framework.ModuleRegistry
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Floating category-panel ClickGUI root. */
@Composable
internal fun MoonsPanelClickGui(
    bindingModuleId: String?,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit,
    onEditHudLayout: () -> Unit,
    onClose: () -> Unit,
    modulesOverride: List<ModuleRegistry.Module>? = null,
) {
    val allModules =
        remember(modulesOverride) {
            (modulesOverride ?: ModuleRegistry.modules()).filterNot {
                it.id() in CLIENT_SETTINGS_MODULE_IDS
            }
        }
    val categories =
        remember(allModules) {
            val preferred = ModuleCategories.ordered()
            val available = allModules.map { it.category() }.distinct()
            preferred.filter(available::contains) + available.filterNot(preferred::contains)
        }
    val positions = remember { mutableStateMapOf<String, Offset>() }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var activePanel by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val filteredModules =
        remember(allModules, search) {
            allModules.filter { matchesSearch(it, search) }.groupBy { it.category() }
        }
    var openSections by remember { mutableStateOf(loadOpenSections()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var styleRevision by remember { mutableIntStateOf(0) }
    styleRevision
    val sections = remember {
        listOf(
            ControlSection("combat", "Combat", ModuleCategories.COMBAT, "combat"),
            ControlSection("movement", "Movement", ModuleCategories.MOVEMENT, "movement"),
            ControlSection("render", "Render", ModuleCategories.RENDER, "render"),
            ControlSection("misc", "Misc", ModuleCategories.MISC, "misc"),
            ControlSection("experiment", "Experiment", ModuleCategories.EXPERIMENT, "experiment"),
            ControlSection("world", "World", ModuleCategories.WORLD, "world"),
            ControlSection("player", "Player", ModuleCategories.PLAYER, "player"),
            ControlSection("network", "Network", ModuleCategories.NETWORK, "network"),
        )
    }
    val openCategories = sections.filter { it.id in openSections }.map { it.category }.toSet()
    val visibleCategories =
        when {
            search.isNotBlank() -> categories
            else -> categories.filter { it in openCategories }
        }

    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = PanelStyle.accent,
                surface = PanelStyle.panel,
                background = Color.Transparent,
                onSurface = PanelStyle.text,
                onPrimary = Color(0xFFF0F4F1),
            )
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = PanelFontFamily,
                fontSize = 9.sp,
                lineHeight = 1.2.em,
                letterSpacing = 0.sp,
                lineHeightStyle =
                    LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            )
        ) {
            val root = Modifier.fillMaxSize().background(Color(0x66303436))
            BoxWithConstraints(root) {
                val logicalWidth = maxWidth.value
                val logicalHeight = maxHeight.value
                val contentWidth =
                    (logicalWidth - CONTROL_WIDTH).coerceAtLeast(PANEL_WIDTH + PANEL_MARGIN * 2f)
                val columns =
                    min(
                        visibleCategories.size.coerceAtLeast(1),
                        max(
                            1,
                            floor(
                                    (contentWidth - PANEL_MARGIN * 2f + PANEL_GAP) /
                                        (PANEL_WIDTH + PANEL_GAP)
                                )
                                .toInt(),
                        ),
                    )
                val occupiedWidth = columns * PANEL_WIDTH + (columns - 1) * PANEL_GAP
                val gridStartX =
                    CONTROL_WIDTH +
                        ((contentWidth - occupiedWidth) / 2f).coerceAtLeast(PANEL_MARGIN)

                visibleCategories.forEachIndexed { index, category ->
                    val initial =
                        Offset(
                            gridStartX + (index % columns) * (PANEL_WIDTH + PANEL_GAP),
                            18f + (index / columns) * 250f,
                        )
                    val savedPosition = remember(category) { loadPanelPosition(category) }
                    val position =
                        positions[category]
                            ?: clampPanelPosition(
                                savedPosition ?: initial,
                                logicalWidth,
                                logicalHeight,
                            )
                    val modules = filteredModules[category].orEmpty()
                    CategoryPanel(
                        category = category,
                        modules = modules,
                        position = position,
                        maxBodyHeight = (logicalHeight - position.y - 16f).coerceIn(120f, 430f).dp,
                        active = activePanel == category,
                        collapsed = collapsed[category] == true,
                        expanded = expanded,
                        bindingModuleId = bindingModuleId,
                        onActivate = { activePanel = category },
                        onMoveBy = { delta ->
                            val current = positions[category] ?: position
                            positions[category] =
                                clampPanelPosition(
                                    current + delta,
                                    logicalWidth,
                                    logicalHeight,
                                )
                        },
                        onMoveFinished = {
                            savePanelPosition(category, positions[category] ?: position)
                        },
                        onCollapse = { collapsed[category] = !(collapsed[category] == true) },
                        onExpandModule = { id ->
                            val opening = expanded[id] != true
                            modules.forEach { expanded.remove(it.id()) }
                            if (opening) expanded[id] = true
                        },
                        onBindingModuleChange = onBindingModuleChange,
                        onMutated = onMutated,
                    )
                }

                ControlMenu(
                    sections = sections,
                    openSections = openSections,
                    settingsOpen = settingsOpen,
                    bindingModuleId = bindingModuleId,
                    search = search,
                    moduleCount = allModules.size,
                    onSelectSection = {
                        search = ""
                        when (it) {
                            "home" -> settingsOpen = false
                            "settings" -> settingsOpen = true
                            "all" -> {
                                settingsOpen = false
                                val allIds = sections.map(ControlSection::id).toSet()
                                val next =
                                    if (openSections.containsAll(allIds)) {
                                        emptySet()
                                    } else {
                                        allIds
                                    }
                                openSections = next
                                saveOpenSections(next)
                            }
                            else -> {
                                settingsOpen = false
                                val next =
                                    if (it in openSections) {
                                        openSections - it
                                    } else {
                                        openSections + it
                                    }
                                openSections = next
                                saveOpenSections(next)
                            }
                        }
                    },
                    onSearchChange = { search = it.take(48) },
                    onBindingModuleChange = onBindingModuleChange,
                    onThemeChange = { value ->
                        Settings.setString(GUI_THEME_KEY, value)
                        styleRevision++
                        onMutated()
                    },
                    onMutated = onMutated,
                    onEditHudLayout = onEditHudLayout,
                    onClose = onClose,
                    modifier =
                        Modifier.align(Alignment.CenterStart).padding(start = 12.dp).zIndex(100f),
                )
            }
        }
    }
}
