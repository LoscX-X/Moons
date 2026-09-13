package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.ui.clickgui.CompactSetting
import com.blanoir.moons.client.ui.clickgui.PanelFontFamily
import com.blanoir.moons.client.ui.clickgui.PanelStyle
import com.blanoir.moons.client.utils.render.NativeItemIcons
import com.mojang.blaze3d.platform.InputConstants
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

/** Shared, responsive editor. All mutations use the existing InvManager settings/actions. */
@Composable
internal fun InventoryConfigurationPage(onMutated: () -> Unit, onBack: () -> Unit) {
    val client = Minecraft.getInstance()
    var view by remember { mutableStateOf(InvManager.preview(client)) }
    var selected by remember { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var keyName by remember { mutableStateOf(InvManagerConfig.onceKey()) }
    var revision by remember { mutableIntStateOf(0) }
    val refresh = {
        revision++
        view = InvManager.preview(client)
        onMutated()
    }
    LaunchedEffect(Unit) {
        while (true) {
            view = InvManager.preview(client)
            binding = InvManager.bindingKey()
            keyName = InvManagerConfig.onceKey()
            delay(150)
        }
    }
    DisposableEffect(Unit) { onDispose { InvManager.cancelKeyBinding() } }
    MaterialTheme(
        colorScheme = darkColorScheme(primary = PanelStyle.accent, onSurface = PanelStyle.text)
    ) {
        ProvideTextStyle(
            TextStyle(
                fontFamily = PanelFontFamily,
                color = PanelStyle.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Color(0xF0202123)).pointerInput(Unit) {
                    // This page covers the underlying panels, including blank space and scrolling.
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach {
                            it.consume()
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                val width = (maxWidth - 24.dp).coerceIn(0.dp, 1000.dp)
                val height = (maxHeight - 24.dp).coerceIn(0.dp, 740.dp)
                Column(
                    Modifier.width(width)
                        .height(height)
                        .clip(PanelStyle.windowShape)
                        .background(PanelStyle.panel)
                        .border(1.dp, PanelStyle.border, PanelStyle.windowShape)
                        .padding(if (width < 500.dp) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("InvManager", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("Inventory configuration", color = PanelStyle.muted)
                        }
                        EditorButton("Back", onClick = onBack)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorButton("Hotbar layout", active = !options) { options = false }
                        EditorButton("Behavior & timing", active = options) { options = true }
                    }
                    val scroll = key(options) { rememberScrollState() }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(end = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (options) {
                                revision
                                BehaviorSettings(refresh)
                            } else {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    if (maxWidth >= 790.dp) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                            Column(
                                                Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                            ) {
                                                HotbarPreview(view, selected) { selected = it }
                                                PlanPreview(view)
                                            }
                                            Column(Modifier.width(310.dp)) {
                                                RoleChooser(view.slots()[selected]) { role ->
                                                    InvManagerConfig.setRole(selected, role)
                                                    refresh()
                                                }
                                                InventoryRuleEditor(
                                                    selected,
                                                    view.slots()[selected].role(),
                                                    refresh,
                                                )
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            HotbarPreview(view, selected) { selected = it }
                                            RoleChooser(view.slots()[selected]) { role ->
                                                InvManagerConfig.setRole(selected, role)
                                                refresh()
                                            }
                                            InventoryRuleEditor(
                                                selected,
                                                view.slots()[selected].role(),
                                                refresh,
                                            )
                                            PlanPreview(view)
                                        }
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            rememberScrollbarAdapter(scroll),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EditorButton("Organize once", Modifier.weight(1f)) {
                                client.execute { InvManager.organizeOnce(client) }
                            }
                            val readable =
                                runCatching { InputConstants.getKey(keyName).displayName.string }
                                    .getOrDefault("None")
                            EditorButton(
                                if (binding) "Press a key…" else "Once key: $readable",
                                Modifier.weight(1f),
                            ) {
                                if (binding) InvManager.cancelKeyBinding()
                                else InvManager.beginKeyBinding()
                                binding = InvManager.bindingKey()
                            }
                        }
                        Text(
                            if (binding) "Esc cancels binding · Backspace clears"
                            else "Changes save automatically. Esc returns to your previous page.",
                            color = PanelStyle.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HotbarPreview(view: InvManager.View, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your hotbar", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Choose a slot, then assign its role. Multiple slots can share the same role.",
            color = PanelStyle.muted,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 400.dp) 5 else 9
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                view.slots().take(9).chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { slot ->
                            SlotTile(slot, selected == slot.index(), Modifier.weight(1f)) {
                                onSelect(slot.index())
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SlotTile(view.slots()[9], selected == 9, Modifier.width(54.dp)) { onSelect(9) }
            Column {
                Text("Offhand · ${view.slots()[9].role().label()}")
                Text(
                    "Free allows moves. Locked keeps the slot untouched.",
                    color = PanelStyle.muted,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            "Manual edits remain protected until the inventory closes.",
            color = PanelStyle.muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SlotTile(
    slot: InvManager.SlotView,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val icon = remember(slot.role()) { slot.role().icon() }
    Column(
        modifier
            .clip(PanelStyle.controlShape)
            .background(if (selected) PanelStyle.accentSoft else PanelStyle.field)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) PanelStyle.accent else PanelStyle.border,
                PanelStyle.controlShape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (slot.index() == 9) "Off" else "${slot.index() + 1}",
            fontSize = 11.sp,
            color = PanelStyle.muted,
        )
        ItemIcon(icon, slot.role().label(), Modifier.size(30.dp))
    }
}

@Composable
private fun RoleChooser(slot: InvManager.SlotView, onChoose: (InventoryRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "${if (slot.index() == 9) "Offhand" else "Slot ${slot.index() + 1}"} · ${InventoryRules.resolve(slot.index(), slot.role()).name()}",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ItemIcon(slot.item(), "Empty", Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (slot.item().isEmpty) "Currently empty" else slot.item().hoverName.string,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (slot.protection().isNotEmpty())
                    Text(slot.protection(), color = PanelStyle.accent, fontSize = 11.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns =
                when {
                    maxWidth >= 520.dp -> 4
                    maxWidth < 280.dp -> 2
                    else -> 3
                }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                InventoryRole.entries.chunked(columns).forEach { roles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        roles.forEach { role ->
                            EditorButton(
                                role.label(),
                                Modifier.weight(1f),
                                role == slot.role(),
                                minHeight = 44,
                            ) {
                                onChoose(role)
                            }
                        }
                        repeat(columns - roles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanPreview(view: InvManager.View) {
    Column(
        Modifier.fillMaxWidth()
            .clip(PanelStyle.cardShape)
            .background(PanelStyle.field)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Next actions", fontWeight = FontWeight.SemiBold)
        Text(view.status(), color = PanelStyle.muted)
        view.plan().forEachIndexed { index, action ->
            Text("${index + 1}. $action", fontSize = 11.sp)
        }
        if (view.plan().isEmpty())
            Text("No slot changes planned.", fontSize = 11.sp, color = PanelStyle.muted)
    }
}

@Composable
private fun BehaviorSettings(onMutated: () -> Unit) {
    val module = ModuleRegistry.modules().firstOrNull { it.id() == "invmanager" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Behavior & timing", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Automatic organizing runs only with your inventory open. Movement, item use and manual actions pause it.",
            color = PanelStyle.muted,
        )
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * 1.6f, density.fontScale)
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                module
                    ?.settings()
                    ?.filter { it.isVisible && !it.id().startsWith("slot_") && it.id() != "hide" }
                    ?.forEach { setting ->
                        key(setting.id()) { CompactSetting(module, setting, onMutated) }
                    }
            }
        }
        Text(
            "This version sorts and refills slots. It never discards items.",
            color = PanelStyle.muted,
        )
    }
}

@Composable
internal fun ItemIcon(stack: ItemStack, fallback: String, modifier: Modifier) {
    DisposableEffect(stack) { onDispose { NativeItemIcons.cancelPending(stack) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = NativeItemIcons.get(stack)
        if (image != null) Image(image, fallback, Modifier.fillMaxSize())
        else
            Text(
                if (stack.isEmpty) "·" else fallback.take(1),
                fontSize = 18.sp,
                color = PanelStyle.muted,
            )
    }
}

@Composable
internal fun EditorButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    minHeight: Int = 38,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = minHeight.dp)
            .clip(PanelStyle.controlShape)
            .background(if (active) PanelStyle.accentSoft else PanelStyle.field)
            .border(
                1.dp,
                if (active) PanelStyle.accent else PanelStyle.border,
                PanelStyle.controlShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
