package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.api.YsmSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Presentation uses shared API values; model loading and rendering stay in the optional module. */
@Composable
internal fun YsmSelectorPage() {
    var snapshot by remember { mutableStateOf(YsmSelectorHost.snapshot()) }
    var selected by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (isActive) {
            snapshot = YsmSelectorHost.snapshot()
            delay(250)
        }
    }
    val current = snapshot
    LaunchedEffect(current?.models(), current?.requested()) {
        if (current == null) selected = ""
        else if (current.models().none { it.id() == selected }) {
            selected =
                current
                    .requested()
                    .takeIf { id -> current.models().any { it.id() == id } }
                    .orEmpty()
        }
    }
    fun perform(action: () -> Unit) {
        actionError = runCatching(action).exceptionOrNull()?.message.orEmpty()
    }
    Column(
        Modifier.fillMaxSize().padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current == null) {
            Text("YSM is unavailable", fontWeight = FontWeight.SemiBold)
            Text(
                "Load the YSM module for your Minecraft version, then return to this page. The current adapter supports 26.1.2.",
                color = PanelStyle.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            return@Column
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(PanelStyle.cardShape)
                .background(PanelStyle.row)
                .border(1.dp, PanelStyle.border, PanelStyle.cardShape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "Active · ${current.active().ifEmpty { "Original player model" }}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                current.message(),
                color =
                    if (current.state() == YsmSelector.State.ERROR) PanelStyle.danger
                    else PanelStyle.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsAction("Apply", enabled = current.models().any { it.id() == selected }) {
                perform { YsmSelectorHost.select(selected) }
            }
            SettingsAction("Refresh") { perform { YsmSelectorHost.refresh() } }
            SettingsAction("Use vanilla") { perform { YsmSelectorHost.reset() } }
        }
        if (actionError.isNotEmpty()) Text(actionError, color = PanelStyle.danger, fontSize = 11.sp)
        SelectionContainer {
            Text(
                "Model folder: ${current.directory()}",
                color = PanelStyle.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PanelSearch(
            search,
            { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Search models",
        )
        val visible = current.models().filter { it.name().contains(search, ignoreCase = true) }
        Text("Local models · ${visible.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (visible.isEmpty()) {
            Text(
                if (current.models().isEmpty())
                    "Add a .ysm, .zip or model folder to this directory, then select Refresh."
                else "No models match your search.",
                color = PanelStyle.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        val scroll = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id() }) { model ->
                    val chosen = model.id() == selected
                    Row(
                        Modifier.fillMaxWidth()
                            .height(54.dp)
                            .clip(PanelStyle.cardShape)
                            .background(if (chosen) PanelStyle.selected else PanelStyle.row)
                            .border(
                                1.dp,
                                if (chosen) PanelStyle.accent else PanelStyle.border,
                                PanelStyle.cardShape,
                            )
                            .clickable { selected = model.id() }
                            .semantics {
                                contentDescription = "Select YSM model ${model.name()}"
                                stateDescription = if (chosen) "Selected" else "Not selected"
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIcon("player", Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.name(),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(model.format(), fontSize = 10.sp, color = PanelStyle.muted)
                        }
                        if (model.id() == current.active())
                            Text("Active", fontSize = 11.sp, color = PanelStyle.accent)
                        else if (
                            model.id() == current.requested() &&
                                current.state() == YsmSelector.State.LOADING
                        )
                            Text("Loading…", fontSize = 11.sp, color = PanelStyle.muted)
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
