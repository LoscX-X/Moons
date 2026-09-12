package com.blanoir.moons.client.module.impl.render.xray

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.ui.clickgui.PanelStyle
import com.blanoir.moons.client.utils.plugin.PluginBlockSelector
import com.blanoir.moons.client.utils.ui.ColorEditor
import com.blanoir.moons.client.utils.ui.SettingInput
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft

/** Server-owned choices are deliberately outside the global module-preset settings. */
@Composable
internal fun PluginBlocksSetting(onMutated: () -> Unit) {
    var revision by remember { mutableLongStateOf(PluginXrayTargets.revision()) }
    var indexing by remember { mutableStateOf(PluginXrayTargets.isIndexing()) }
    LaunchedEffect(Unit) {
        while (true) {
            PluginXrayTargets.updateContext(Minecraft.getInstance())
            revision = PluginXrayTargets.revision()
            indexing = PluginXrayTargets.isIndexing()
            delay(250)
        }
    }
    val scope = PluginXrayTargets.scope()
    val entries = remember(scope, revision) { PluginXrayTargets.blocks() }
    var expanded by remember { mutableStateOf(false) }
    var query by remember(scope) { mutableStateOf("") }
    var editing by remember(scope) { mutableStateOf<String?>(null) }
    var error by remember(scope) { mutableStateOf<String?>(null) }
    val matches =
        remember(entries, query) {
            entries.filter {
                it.name().contains(query, true) ||
                    it.detail().contains(query, true) ||
                    (it.preview()?.let { state ->
                        PluginBlockSelector.capture(state).key().contains(query, true)
                    } == true)
            }
        }
    fun edit(
        entry: PluginXrayTargets.BlockEntry,
        selected: Boolean = entry.enabled(),
        rgb: Int = entry.rgb(),
    ) {
        runCatching {
                PluginXrayTargets.editBlock(
                    Minecraft.getInstance(),
                    scope,
                    entry.id(),
                    selected,
                    rgb,
                )
            }
            .onSuccess {
                error = null
                revision = PluginXrayTargets.revision()
                onMutated()
            }
            .onFailure { error = it.message ?: "Could not save server configuration" }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(PanelStyle.controlShape)
            .background(PanelStyle.row)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Plugin Blocks (${entries.count { it.enabled() }}/${entries.size})",
                color = PanelStyle.text,
                fontSize = 7.sp,
            )
            Text(if (expanded) "−" else "+", color = PanelStyle.muted, fontSize = 9.sp)
        }
        if (expanded) {
            Text(
                scope.ifEmpty { "Join a server first" },
                color = PanelStyle.muted,
                fontSize = 6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !PluginXrayTargets.isEnabled() ->
                        "Enable Plugin compatibility to scan and display."
                    indexing -> "Reading resource-pack models…"
                    else -> "Only checked blocks are highlighted. New blocks start unchecked."
                },
                color = PanelStyle.muted,
                fontSize = 7.sp,
            )
            SettingInput(query, { query = it.take(128) }, "Search model name or block state")
            if (matches.isEmpty())
                Text(
                    if (entries.isEmpty()) "No custom blocks discovered yet." else "No matches",
                    color = PanelStyle.muted,
                    fontSize = 7.sp,
                )
            else
                LazyColumn(Modifier.fillMaxWidth().height((matches.size.coerceAtMost(5) * 34).dp)) {
                    items(matches, key = { it.id() }) { entry ->
                        DisposableEffect(entry.preview()) {
                            onDispose { PluginBlockPreviews.cancelPending(entry.preview()) }
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .height(34.dp)
                                .background(
                                    if (editing == entry.id()) PanelStyle.field else PanelStyle.row
                                )
                                .padding(horizontal = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                Modifier.size(14.dp)
                                    .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
                                    .clickable { edit(entry, !entry.enabled()) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (entry.enabled())
                                    Text("✓", color = PanelStyle.controlActive, fontSize = 9.sp)
                            }
                            Box(
                                Modifier.size(23.dp).clickable { editing = entry.id() },
                                contentAlignment = Alignment.Center,
                            ) {
                                val icon = PluginBlockPreviews.get(entry.preview())
                                if (icon != null) Image(icon, entry.name(), Modifier.fillMaxSize())
                                else
                                    Text(
                                        if (PluginBlockPreviews.unavailable(entry.preview())) "?"
                                        else "…",
                                        color = PanelStyle.muted,
                                        fontSize = 9.sp,
                                    )
                            }
                            Column(
                                Modifier.weight(1f).clickable {
                                    editing = if (editing == entry.id()) null else entry.id()
                                }
                            ) {
                                Text(
                                    entry.name(),
                                    color = PanelStyle.text,
                                    fontSize = 7.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (entry.preview() == null)
                                        "Unavailable in current resource pack"
                                    else "${entry.stateCount()} state(s)",
                                    color = PanelStyle.muted,
                                    fontSize = 6.sp,
                                    maxLines = 1,
                                )
                            }
                            Box(
                                Modifier.size(16.dp)
                                    .clip(PanelStyle.controlShape)
                                    .background(Color(0xff000000.toInt() or entry.rgb()))
                                    .clickable {
                                        editing = if (editing == entry.id()) null else entry.id()
                                    }
                            )
                        }
                    }
                }
            val focused = entries.firstOrNull { it.id() == editing }
            if (focused != null)
                key(scope, focused.id()) {
                    Text(focused.detail(), color = PanelStyle.muted, fontSize = 7.sp)
                    focused.preview()?.let { state ->
                        Text(
                            PluginBlockSelector.capture(state).key(),
                            color = PanelStyle.muted,
                            fontSize = 6.sp,
                        )
                    }
                    ColorEditor("#%06x".format(focused.rgb())) {
                        edit(focused, rgb = it.removePrefix("#").toInt(16))
                    }
                }
            error?.let { Text(it, color = Color(0xffff7c7c), fontSize = 7.sp) }
        }
    }
}
