package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blanoir.moons.client.config.Settings

private const val YSM_PAGE_KEY = "clickgui.ysm.page"
private val ysmPages =
    linkedMapOf(
        "YSM" to "Models",
        "YSM Parameters" to "Parameters",
        "YSM Actions" to "Actions",
        "YSM Pose" to "Pose",
        "YSM Debug" to "Debug",
    )

/** One sidebar destination; old saved studio destinations migrate to an internal tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YsmPage() {
    var page by remember {
        val legacy = Settings.getString("clickgui.settings.page", "YSM")
        val saved = Settings.getString(YSM_PAGE_KEY, "YSM")
        mutableStateOf(
            if (legacy != "YSM" && legacy in ysmPages) legacy
            else saved.takeIf { it in ysmPages } ?: "YSM"
        )
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(page) {
        Settings.beginBatch()
        try {
            Settings.setString(YSM_PAGE_KEY, page)
            Settings.setString("clickgui.settings.page", "YSM")
        } finally {
            Settings.endBatch()
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ysmPages.forEach { (id, label) ->
                Box(Modifier.semantics { selected = page == id }) {
                    SettingsAction(label, active = page == id) {
                        focus.clearFocus()
                        page = id
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(page) {
                if (page == "YSM") YsmSelectorPage() else YsmStudioPage(page)
            }
        }
    }
}
