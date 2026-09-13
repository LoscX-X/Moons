package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.ui.clickgui.PanelStyle

@Composable
internal fun InventoryRuleEditor(slot: Int, role: InventoryRole, refresh: () -> Unit) {
    if (role != InventoryRole.BLOCK && role != InventoryRole.CUSTOM) return
    var revision by remember(slot, role) { mutableIntStateOf(0) }
    var expanded by remember(slot, role) { mutableStateOf(role == InventoryRole.CUSTOM) }
    var newName by remember(slot) { mutableStateOf("") }
    val changed = {
        revision++
        refresh()
    }
    revision
    val error = InventoryRules.error()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (error.isNotEmpty()) {
            Text(error, color = PanelStyle.accent)
            return@Column
        }
        EditorButton(if (expanded) "Hide item rules" else "Edit item rules") {
            expanded = !expanded
        }
        if (!expanded) return@Column
        if (role == InventoryRole.CUSTOM) {
            Text("Reusable item groups", fontWeight = FontWeight.SemiBold)
            InventoryRules.groups().forEach { group ->
                EditorButton(
                    group.name(),
                    Modifier.fillMaxWidth(),
                    active = group.id() == InventoryRules.resolve(slot, role).id(),
                ) {
                    InventoryRules.assign(slot, group.id())
                    changed()
                }
            }
            OutlinedTextField(
                newName,
                { newName = it },
                Modifier.fillMaxWidth(),
                label = { Text("New group name") },
                singleLine = true,
            )
            EditorButton("Create group") {
                val group = InventoryRules.create(newName)
                InventoryRules.assign(slot, group.id())
                newName = ""
                changed()
            }
        }
        val rule = InventoryRules.resolve(slot, role)
        if (role == InventoryRole.CUSTOM && InventoryRules.groups().none { it.id() == rule.id() }) {
            Text(
                "Select or create a group. Missing groups never match other items.",
                color = PanelStyle.muted,
            )
            return@Column
        }
        key(rule.id()) { RuleContents(rule, changed) }
    }
}

private fun InventoryRules.Rule.updated(
    name: String = name(),
    onlyListed: Boolean = onlyListed(),
    preferFirst: Boolean = preferFirst(),
    include: List<InventoryRules.Entry> = include(),
    exclude: List<InventoryRules.Entry> = exclude(),
) = InventoryRules.Rule(id(), name, base(), onlyListed, preferFirst, include, exclude)

@Composable
private fun RuleContents(rule: InventoryRules.Rule, changed: () -> Unit) {
    var name by remember(rule.id(), rule.name()) { mutableStateOf(rule.name()) }
    var query by remember { mutableStateOf("") }
    var inventoryOnly by remember { mutableStateOf(true) }
    var sample by remember { mutableStateOf(false) }
    var exclusions by remember { mutableStateOf(false) }
    var searchRevision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(-1) }
    val save: (InventoryRules.Rule) -> Unit = {
        InventoryRules.save(it)
        changed()
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(rule.name(), fontWeight = FontWeight.SemiBold)
        Text("Shared by every slot using this group.", color = PanelStyle.muted, fontSize = 11.sp)
        if (rule.id() != InventoryRules.BLOCK) {
            OutlinedTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth(),
                label = { Text("Group name") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                EditorButton("Rename") { if (name.isNotBlank()) save(rule.updated(name = name)) }
                EditorButton("Delete group") {
                    InventoryRules.delete(rule.id())
                    changed()
                }
            }
        } else {
            EditorButton(if (rule.onlyListed()) "Only listed items" else "Extend default Blocks") {
                save(rule.updated(onlyListed = !rule.onlyListed()))
            }
            EditorButton("Reset Blocks to defaults") {
                InventoryRules.resetBlocks()
                changed()
            }
        }
        EditorButton(
            if (rule.preferFirst()) "Always prefer first" else "Keep current until used up",
            active = rule.preferFirst(),
        ) {
            save(rule.updated(preferFirst = !rule.preferFirst()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            EditorButton("Allowed (${rule.include().size})", active = !exclusions) {
                exclusions = false
                selected = -1
            }
            EditorButton("Excluded (${rule.exclude().size})", active = exclusions) {
                exclusions = true
                selected = -1
            }
        }
        val entries = if (exclusions) rule.exclude() else rule.include()
        val updateEntries: (List<InventoryRules.Entry>) -> Unit = { list ->
            save(if (exclusions) rule.updated(exclude = list) else rule.updated(include = list))
        }
        entries.forEachIndexed { index, entry ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = remember(entry) { entry.icon() }
                ItemIcon(icon, entry.item(), Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.item(), fontSize = 11.sp)
                    Text(
                        if (!entry.available()) "Unavailable in this version"
                        else if (entry.components().isEmpty()) "Item ID"
                        else "Sample · ${entry.components().size} fields",
                        fontSize = 11.sp,
                        color = PanelStyle.muted,
                    )
                }
                EditorButton("Edit", active = selected == index) {
                    selected = if (selected == index) -1 else index
                }
            }
            if (selected == index) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditorButton("Remove") {
                        updateEntries(entries.filterIndexed { i, _ -> i != index })
                        selected = -1
                    }
                    if (index > 0)
                        EditorButton("Move up") {
                            val list = entries.toMutableList()
                            java.util.Collections.swap(list, index, index - 1)
                            updateEntries(list)
                            selected--
                        }
                }
                if (entry.sample().isNotEmpty()) {
                    Text(
                        "Match these sample fields (quantity and wear are ignored):",
                        fontSize = 11.sp,
                    )
                    entry.sample().keys.sorted().forEach { field ->
                        EditorButton(
                            field.replace('_', ' '),
                            active = entry.components().containsKey(field),
                        ) {
                            val fields = entry.components().toMutableMap()
                            if (fields.containsKey(field)) fields.remove(field)
                            else fields[field] = entry.sample()[field]!!
                            val replacement =
                                InventoryRules.Entry(
                                    entry.item(),
                                    fields,
                                    entry.allowSpecial(),
                                    entry.sample(),
                                )
                            updateEntries(
                                entries.mapIndexed { i, old ->
                                    if (i == index) replacement else old
                                }
                            )
                        }
                    }
                    if (!exclusions) {
                        EditorButton("Allow this special item", active = entry.allowSpecial()) {
                            val replacement =
                                InventoryRules.Entry(
                                    entry.item(),
                                    entry.components(),
                                    !entry.allowSpecial(),
                                    entry.sample(),
                                )
                            updateEntries(
                                entries.mapIndexed { i, old ->
                                    if (i == index) replacement else old
                                }
                            )
                        }
                        Text(
                            "Special items require all name, lore, model, custom data and attribute fields. Manual locks always apply.",
                            color = PanelStyle.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth(),
            label = { Text("Search item name or ID") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorButton("Backpack", active = inventoryOnly) {
                inventoryOnly = true
                searchRevision++
            }
            EditorButton("All items", active = !inventoryOnly) { inventoryOnly = false }
        }
        EditorButton(if (sample) "Match sample features" else "Match item ID", active = sample) {
            sample = !sample
        }
        Text(
            "Click an item to add it to ${if (exclusions) "exclusions" else "allowed items"}. This does not move it.",
            fontSize = 11.sp,
            color = PanelStyle.muted,
        )
        val results =
            remember(query, inventoryOnly, searchRevision) {
                InventoryRules.search(query, inventoryOnly)
            }
        if (results.isEmpty())
            Text(
                "No matching items. Join a world to select backpack samples.",
                color = PanelStyle.muted,
            )
        if (message.isNotEmpty()) Text(message, color = PanelStyle.accent)
        results.forEach { stack ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ItemIcon(stack, stack.hoverName.string, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(stack.hoverName.string, fontSize = 11.sp)
                    Text(rule.reason(stack), color = PanelStyle.muted, fontSize = 10.sp)
                }
                EditorButton("Add") {
                    runCatching {
                            val entry = InventoryRules.entry(stack, sample)
                            if (!entries.contains(entry)) updateEntries(entries + entry)
                            message = "Added ${stack.hoverName.string}"
                        }
                        .onFailure { message = it.message ?: "Could not capture this item" }
                }
            }
        }
        if (results.size == 60)
            Text(
                "Showing the first 60 matches. Refine your search for more.",
                color = PanelStyle.muted,
            )
    }
}
