package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module

private fun moduleHiddenKey(module: Module) = "clickgui.hidden.${module.id()}"

/** Presentation only: never change the registry, enabled state, HUD or key bindings. */
internal fun isModuleHidden(module: Module) = Settings.getBoolean(moduleHiddenKey(module), false)

private fun setModuleHidden(module: Module, hidden: Boolean) {
    if (hidden) Settings.setBoolean(moduleHiddenKey(module), true)
    else Settings.remove(moduleHiddenKey(module))
}

@Composable
internal fun ModuleHidePage(modules: List<Module>, onMutated: () -> Unit) {
    ClickGuiRevision.intValue
    var search by remember { mutableStateOf("") }
    var hiddenOnly by remember { mutableStateOf(false) }
    val hiddenCount = modules.count(::isModuleHidden)
    val filtered = modules.filter {
        matchesSearch(it, search) && (!hiddenOnly || isModuleHidden(it))
    }
    val scroll = rememberLazyListState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelSearch(search, { search = it.take(96) })
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsAction("Hidden only", active = hiddenOnly) { hiddenOnly = !hiddenOnly }
            SettingsAction("Show all", enabled = hiddenCount > 0) {
                Settings.beginBatch()
                try {
                    modules.forEach { setModuleHidden(it, false) }
                } finally {
                    Settings.endBatch()
                }
                onMutated()
            }
            Text("$hiddenCount hidden", color = PanelStyle.muted, fontSize = 12.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (filtered.isEmpty()) {
                Text(
                    if (hiddenOnly && hiddenCount == 0) "No hidden modules" else "No modules found",
                    color = PanelStyle.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(end = 12.dp),
                    state = scroll,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    items(filtered, key = { it.id() }) { module ->
                        val hidden = isModuleHidden(module)
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(PanelStyle.cardShape)
                                .background(if (hidden) PanelStyle.selected else PanelStyle.row)
                                .border(1.dp, PanelStyle.border, PanelStyle.cardShape)
                                .toggleable(value = hidden, role = Role.Checkbox) {
                                    setModuleHidden(module, it)
                                    onMutated()
                                }
                                .semantics {
                                    contentDescription = "Hide ${module.name()} in ClickGUI"
                                    stateDescription = if (hidden) "Hidden" else "Visible"
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    module.name(),
                                    color = PanelStyle.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(module.category(), color = PanelStyle.muted, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (hidden) "Hidden" else "Visible",
                                color = if (hidden) PanelStyle.text else PanelStyle.muted,
                                fontSize = 12.sp,
                            )
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
}

/** The panel layout opens the same manager without changing the saved layout. */
@Composable
internal fun ModuleHideWindow(modules: List<Module>, onMutated: () -> Unit, onBack: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width((maxWidth - 24.dp).coerceIn(0.dp, 600.dp))
                .height((maxHeight - 24.dp).coerceIn(0.dp, 640.dp))
                .clip(PanelStyle.windowShape)
                .background(PanelStyle.panel)
                .border(1.dp, PanelStyle.border, PanelStyle.windowShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Module Hide",
                    color = PanelStyle.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                SettingsAction("Back to modules", onClick = onBack)
            }
            Text(
                "Choose modules to hide in ClickGUI. Enabled states and keybinds stay the same.",
                color = PanelStyle.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            ModuleHidePage(modules, onMutated)
        }
    }
}
