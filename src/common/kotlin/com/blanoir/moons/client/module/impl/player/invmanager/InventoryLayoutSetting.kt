package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.ui.clickgui.PanelStyle

internal val LocalOpenInventoryEditor = staticCompositionLocalOf<() -> Unit> { {} }

/** Compact entry only; the editor is hosted outside the category panel's scroll bounds. */
@Composable
internal fun InventoryLayoutSetting() {
    val open = LocalOpenInventoryEditor.current
    Box(
        Modifier.fillMaxWidth()
            .height(34.dp)
            .clip(PanelStyle.controlShape)
            .background(PanelStyle.field)
            .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
            .clickable(onClick = open),
        contentAlignment = Alignment.Center,
    ) {
        Text("Open configuration page  ›", color = PanelStyle.text, fontSize = 8.sp)
    }
}
