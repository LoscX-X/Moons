package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.module.impl.player.InvClear
import com.blanoir.moons.client.ui.clickgui.PanelStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.minecraft.client.Minecraft

@Composable
internal fun InvClearPreview() {
    var drops by remember { mutableStateOf(InvClear.preview(Minecraft.getInstance())) }
    LaunchedEffect(Unit) {
        while (isActive) {
            drops = InvClear.preview(Minecraft.getInstance())
            delay(150)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Drops items only while your inventory is open. Item IDs: separate with commas.",
            color = PanelStyle.muted,
            fontSize = 9.sp,
        )
        Text("Cleanup preview · ${drops.size} stacks", fontSize = 10.sp)
        drops.take(8).forEach { drop ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ItemIcon(drop.item(), drop.item().hoverName.string, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("${drop.item().hoverName.string} ×${drop.item().count}", fontSize = 10.sp)
                    Text(
                        "Slot ${drop.source() + 1} · ${drop.reason()}",
                        color = PanelStyle.muted,
                        fontSize = 9.sp,
                    )
                }
            }
        }
        if (drops.size > 8)
            Text("+${drops.size - 8} more stacks", color = PanelStyle.muted, fontSize = 9.sp)
    }
}
