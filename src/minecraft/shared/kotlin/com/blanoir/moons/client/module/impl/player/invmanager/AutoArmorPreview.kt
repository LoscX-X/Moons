package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.module.impl.player.AutoArmor
import com.blanoir.moons.client.ui.clickgui.PanelStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.minecraft.client.Minecraft

@Composable
internal fun AutoArmorPreview() {
    var armor by remember { mutableStateOf(AutoArmor.preview(Minecraft.getInstance())) }
    LaunchedEffect(Unit) {
        while (isActive) {
            armor = AutoArmor.preview(Minecraft.getInstance())
            delay(150)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Open inventory to equip. Old armor stays in your inventory.",
            color = PanelStyle.muted,
            fontSize = 9.sp,
        )
        armor.forEach { part ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ItemIcon(part.item(), InventoryArmor.label(part.index()), Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(InventoryArmor.label(part.index()), fontSize = 10.sp)
                    Text(part.status(), color = PanelStyle.muted, fontSize = 9.sp)
                    if (!part.candidate().isEmpty)
                        Text("Next: ${part.candidate().hoverName.string}", fontSize = 9.sp)
                }
            }
        }
    }
}
