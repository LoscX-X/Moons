package com.blanoir.moons.client.ui.clickgui

import androidx.compose.runtime.Composable
import com.blanoir.moons.client.module.framework.ModuleRegistry

/** One route for the game screen and CPU warmup; the saved layout can change while open. */
@Composable
internal fun MoonsClickGui(
    bindingModuleId: String?,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit,
    onEditHudLayout: () -> Unit,
    onClose: () -> Unit,
    modulesOverride: List<ModuleRegistry.Module>? = null,
) {
    ClickGuiRevision.intValue
    if (ModuleGui.layout() == "panels") {
        MoonsPanelClickGui(
            bindingModuleId,
            onBindingModuleChange,
            onMutated,
            onEditHudLayout,
            onClose,
            modulesOverride,
        )
    } else {
        MoonsSettingsClickGui(
            bindingModuleId,
            onBindingModuleChange,
            onMutated,
            onEditHudLayout,
            onClose,
            modulesOverride,
        )
    }
}
