package com.blanoir.moons.client.utils.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.ui.clickgui.PanelStyle
import com.blanoir.moons.client.utils.registry.RegistryLists
import com.blanoir.moons.client.utils.render.NativeItemIcons
import com.google.gson.JsonArray
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem

private class RegistryEntry(val id: String, val name: String, iconFactory: () -> ItemStack) {
    val icon by lazy(iconFactory)
}

private fun registryEntry(type: String, id: String): RegistryEntry {
    val key =
        net.minecraft.resources.Identifier.tryParse(id)
            ?: return RegistryEntry(id, id) { ItemStack.EMPTY }
    return when (type) {
        "item_list" ->
            BuiltInRegistries.ITEM.getOptional(key)
                .map { item ->
                    RegistryEntry(id, item.getName(item.defaultInstance).string) {
                        item.defaultInstance
                    }
                }
                .orElse(null)
        "block_list" ->
            BuiltInRegistries.BLOCK.getOptional(key)
                .map { block ->
                    RegistryEntry(id, block.name.string) { block.asItem().defaultInstance }
                }
                .orElse(null)
        else ->
            BuiltInRegistries.ENTITY_TYPE.getOptional(key)
                .map { entity ->
                    RegistryEntry(id, entity.description.string) {
                        SpawnEggItem.byId(entity)
                            .map { it.value().defaultInstance }
                            .orElse(ItemStack.EMPTY)
                    }
                }
                .orElse(null)
    } ?: RegistryEntry(id, id) { ItemStack.EMPTY }
}

private fun registryEntries(type: String): List<RegistryEntry> {
    val keys =
        when (type) {
            "item_list" -> BuiltInRegistries.ITEM.keySet()
            "block_list" -> BuiltInRegistries.BLOCK.keySet()
            else -> BuiltInRegistries.ENTITY_TYPE.keySet()
        }
    return keys
        .asSequence()
        .map { it.toString() }
        .filter { type != "mob_list" || it != "minecraft:player" }
        .filter { type != "item_list" || it != "minecraft:air" }
        .map { registryEntry(type, it) }
        .sortedWith(compareBy({ it.name }, { it.id }))
        .toList()
}

@Composable
internal fun RegistryListSetting(
    module: ModuleRegistry.Module,
    setting: ModuleRegistry.Setting,
    onMutated: () -> Unit,
) {
    val language = Language.getInstance()
    val value = setting.value().get().asJsonArray
    val selected = value.map { it.asJsonObject }
    val selectedIds = selected.map { it.get("id").asString }.toSet()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    // No full registry traversal until the user opens the browser or starts a search.
    val entries =
        if (browsing) remember(setting.type(), language) { registryEntries(setting.type()) }
        else emptyList()
    val matches =
        remember(entries, query, selectedIds) {
            entries.filter {
                it.id !in selectedIds &&
                    (it.id.contains(query, true) || it.name.contains(query, true))
            }
        }
    fun apply(next: JsonArray) {
        ModuleRegistry.setValue(module.id(), setting.id(), next)
        onMutated()
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(PanelStyle.controlShape)
            .background(PanelStyle.row)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${setting.name()} (${selected.size})", color = PanelStyle.muted, fontSize = 7.sp)
        SettingInput(
            query,
            {
                query = it.take(128)
                browsing = true
            },
            "Name or namespace:id",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (browsing) "Hide registry" else "Browse registry",
                color = PanelStyle.text,
                fontSize = 7.sp,
                modifier = Modifier.clickable { browsing = !browsing }.padding(vertical = 4.dp),
            )
            if (browsing) Text("${matches.size}", color = PanelStyle.muted, fontSize = 7.sp)
        }
        if (browsing) {
            if (matches.isEmpty()) Text("No matches", color = PanelStyle.muted, fontSize = 7.sp)
            else
                LazyColumn(Modifier.fillMaxWidth().height(112.dp)) {
                    items(matches, key = { it.id }) { entry ->
                        RegistryEntryRow(entry, "+", false) {
                            val next = value.deepCopy()
                            next.add(
                                RegistryLists.entry(
                                    entry.id,
                                    if (
                                        setting.type() == "item_list" ||
                                            setting.type() == "mob_list"
                                    )
                                        null
                                    else "#00dcff",
                                )
                            )
                            editing = entry.id
                            apply(next)
                        }
                    }
                }
        }
        if (selected.isNotEmpty()) {
            Text("Selected", color = PanelStyle.muted, fontSize = 7.sp)
            LazyColumn(
                Modifier.fillMaxWidth()
                    .heightIn(max = 144.dp)
                    .height((selected.size.coerceAtMost(4) * 32).dp)
            ) {
                items(selected, key = { it.get("id").asString }) { item ->
                    val id = item.get("id").asString
                    val entry =
                        remember(setting.type(), id, language) { registryEntry(setting.type(), id) }
                    RegistryEntryRow(entry, if (editing == id) "−" else "", editing == id) {
                        editing = if (editing == id) null else id
                    }
                }
            }
        }
        val focused = selected.firstOrNull { it.get("id").asString == editing }
        if (focused != null) {
            if (setting.type() == "block_list") {
                val enabled = focused.get("enabled").asBoolean
                Text(
                    if (enabled) "Enabled" else "Disabled",
                    color = PanelStyle.text,
                    fontSize = 7.sp,
                    modifier =
                        Modifier.clickable {
                                val next = value.deepCopy()
                                next
                                    .first { it.asJsonObject.get("id").asString == editing }
                                    .asJsonObject
                                    .addProperty("enabled", !enabled)
                                apply(next)
                            }
                            .padding(vertical = 4.dp),
                )
            }
            if (focused.has("color")) {
                ColorEditor(focused.get("color").asString) { color ->
                    val next = value.deepCopy()
                    next
                        .first { it.asJsonObject.get("id").asString == editing }
                        .asJsonObject
                        .addProperty("color", color)
                    apply(next)
                }
            }
            Text(
                "Remove selected",
                color = PanelStyle.text,
                fontSize = 7.sp,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                            val next = JsonArray()
                            value
                                .filter { it.asJsonObject.get("id").asString != editing }
                                .forEach { next.add(it.deepCopy()) }
                            editing = null
                            apply(next)
                        }
                        .padding(vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun RegistryEntryRow(
    entry: RegistryEntry,
    action: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DisposableEffect(entry.icon.item) {
        onDispose { NativeItemIcons.cancelPending(entry.icon) }
    }
    Row(
        Modifier.fillMaxWidth()
            .height(32.dp)
            .background(if (selected) PanelStyle.field else PanelStyle.row)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(18.dp)) {
            NativeItemIcons.get(entry.icon)?.let { Image(it, entry.name, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                color = PanelStyle.text,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.id,
                color = PanelStyle.muted,
                fontSize = 6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(action, color = PanelStyle.text, fontSize = 9.sp)
    }
}
