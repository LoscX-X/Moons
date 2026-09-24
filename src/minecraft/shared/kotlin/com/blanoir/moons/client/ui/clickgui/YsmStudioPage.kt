package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.api.YsmStudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Independent pages backed by the optional local studio service. */
@Composable
internal fun YsmStudioPage(page: String) {
    var snapshot by remember { mutableStateOf(YsmSelectorHost.studioSnapshot()) }
    var error by remember { mutableStateOf("") }
    var search by remember(page) { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (isActive) {
            snapshot = YsmSelectorHost.studioSnapshot()
            delay(150)
        }
    }
    fun perform(action: () -> Unit) {
        error = runCatching(action).exceptionOrNull()?.message.orEmpty()
    }
    val model = snapshot
    if (model == null) {
        Text("Select a local model on the YSM page to open its controls.", color = PanelStyle.muted)
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(model.model(), fontSize = 12.sp, color = PanelStyle.muted)
        if (error.isNotEmpty()) Text(error, color = PanelStyle.danger, fontSize = 12.sp)
        if (model.result().isNotEmpty())
            SelectionContainer { Text(model.result(), fontSize = 12.sp, color = PanelStyle.accent) }
        when (page) {
            "YSM Parameters" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction("Reset model parameters") {
                        perform { YsmSelectorHost.resetParameters() }
                    }
                }
                Text(
                    "Changes are previewed locally and saved for this model.",
                    fontSize = 12.sp,
                    color = PanelStyle.muted,
                )
                PanelSearch(
                    search,
                    { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Search body, clothing and expression controls",
                )
                StudioList {
                    model
                        .parameters()
                        .groupBy { it.group() }
                        .forEach { (group, parameters) ->
                            val visible = parameters.filter {
                                (it.title() + it.description() + group).contains(search, true)
                            }
                            if (visible.isNotEmpty()) {
                                item("group:$group") {
                                    Text(group, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                                items(visible, key = { it.id() }) { parameter ->
                                    ParameterControl(parameter, model.values()[parameter.id()]) {
                                        value ->
                                        perform { YsmSelectorHost.parameter(parameter.id(), value) }
                                    }
                                }
                            }
                        }
                    if (model.parameters().isEmpty())
                        item {
                            Text(
                                "This model has no authored parameter forms.",
                                color = PanelStyle.muted,
                            )
                        }
                    item { Text("Textures", fontWeight = FontWeight.SemiBold) }
                    items(model.textures(), key = { "texture:$it" }) { texture ->
                        SettingsAction((if (texture == model.texture()) "✓ " else "") + texture) {
                            perform { YsmSelectorHost.texture(texture) }
                        }
                    }
                }
            }
            "YSM Actions" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction(if (model.paused()) "Resume" else "Pause") {
                        perform { YsmSelectorHost.playback(model.speed(), !model.paused()) }
                    }
                    SettingsAction("Restart") {
                        perform { YsmSelectorHost.play(model.animation()) }
                    }
                    SettingsAction("Automatic actions") { perform { YsmSelectorHost.play("") } }
                }
                Text(
                    "${model.animation().ifEmpty { "Automatic" }} · %.2f s".format(model.time()),
                    fontSize = 12.sp,
                )
                StudioSlider("Playback speed", model.speed(), 0.05, 4.0, 0.05) {
                    perform { YsmSelectorHost.playback(it, model.paused()) }
                }
                val selected = model.animations().find { it.id() == model.animation() }
                val duration = selected?.duration()?.takeIf { it > 0 }?.coerceAtMost(300.0) ?: 30.0
                StudioSlider(
                    "Timeline",
                    model.time().coerceIn(0.0, duration),
                    0.0,
                    duration,
                    0.05,
                    commitOnly = true,
                ) {
                    perform { YsmSelectorHost.seek(it) }
                }
                PanelSearch(
                    search,
                    { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Search animations",
                )
                StudioList {
                    val visible =
                        model.animations().filter {
                            (it.label() + it.id() + it.group()).contains(search, true)
                        }
                    items(visible, key = { it.id() }) { animation ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (animation.id() == model.animation()) PanelStyle.selected
                                    else PanelStyle.row,
                                    PanelStyle.cardShape,
                                )
                                .clickable { perform { YsmSelectorHost.play(animation.id()) } }
                                .padding(12.dp)
                        ) {
                            Text(animation.label(), fontSize = 13.sp)
                            Text(
                                "${animation.group()} · ${animation.id()}",
                                fontSize = 11.sp,
                                color = PanelStyle.muted,
                            )
                        }
                    }
                }
            }
            "YSM Pose" -> {
                var bone by
                    remember(model.model()) {
                        mutableStateOf(model.bones().firstOrNull().orEmpty())
                    }
                var expanded by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        SettingsAction(bone.ifEmpty { "Select a bone" }) { expanded = true }
                        DropdownMenu(
                            expanded,
                            { expanded = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            model
                                .bones()
                                .filter { it.contains(search, true) }
                                .forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            bone = name
                                            expanded = false
                                        },
                                    )
                                }
                        }
                    }
                    SettingsAction("Reset bone") { perform { YsmSelectorHost.pose(bone, null) } }
                    SettingsAction("Reset pose") { perform { YsmSelectorHost.resetPose() } }
                }
                PanelSearch(
                    search,
                    { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Filter bone names",
                )
                Text(
                    "Position uses model pixels; rotation uses degrees. Adjustments are saved for this model.",
                    fontSize = 12.sp,
                    color = PanelStyle.muted,
                )
                val current =
                    model.poses()[bone]
                        ?: YsmStudio.BonePose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, false)
                val values =
                    listOf(
                        current.x(),
                        current.y(),
                        current.z(),
                        current.pitch(),
                        current.yaw(),
                        current.roll(),
                        current.scaleX(),
                        current.scaleY(),
                        current.scaleZ(),
                    )
                fun update(index: Int, number: Double, hidden: Boolean = current.hidden()) {
                    val next = values.toMutableList()
                    if (index >= 0) next[index] = number
                    perform {
                        YsmSelectorHost.pose(
                            bone,
                            YsmStudio.BonePose(
                                next[0],
                                next[1],
                                next[2],
                                next[3],
                                next[4],
                                next[5],
                                next[6],
                                next[7],
                                next[8],
                                hidden,
                            ),
                        )
                    }
                }
                StudioList {
                    item("$bone:hidden") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(current.hidden(), { update(-1, 0.0, it) })
                            Text("Hide bone and children")
                        }
                    }
                    listOf(
                            "Position X",
                            "Position Y",
                            "Position Z",
                            "Rotation X",
                            "Rotation Y",
                            "Rotation Z",
                            "Scale X",
                            "Scale Y",
                            "Scale Z",
                        )
                        .forEachIndexed { index, label ->
                            item("$bone:$index") {
                                StudioSlider(
                                    label,
                                    values[index],
                                    if (index < 3) -64.0 else if (index < 6) -180.0 else 0.0,
                                    if (index < 3) 64.0 else if (index < 6) 180.0 else 4.0,
                                    if (index < 3) 0.1 else if (index < 6) 1.0 else 0.01,
                                ) {
                                    update(index, it)
                                }
                            }
                        }
                }
            }
            else -> {
                var expression by remember { mutableStateOf("") }
                OutlinedTextField(
                    expression,
                    { expression = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Local Molang expression") },
                    minLines = 2,
                )
                SettingsAction("Evaluate") { perform { YsmSelectorHost.evaluate(expression) } }
                PanelSearch(
                    search,
                    { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Filter controllers and variables",
                )
                StudioList {
                    item { Text("Active controllers", fontWeight = FontWeight.SemiBold) }
                    items(
                        model.controllers().entries.filter {
                            (it.key + it.value).contains(search, true)
                        },
                        key = { "controller:${it.key}" },
                    ) { entry ->
                        SelectionContainer {
                            Text("${entry.key}\n${entry.value}", fontSize = 12.sp)
                        }
                    }
                    item { Text("Model variables", fontWeight = FontWeight.SemiBold) }
                    items(
                        model.variables().entries.filter { it.key.contains(search, true) },
                        key = { "variable:${it.key}" },
                    ) { entry ->
                        SelectionContainer {
                            Text(
                                "v.${entry.key} = ${entry.value}",
                                fontSize = 12.sp,
                                color = PanelStyle.muted,
                            )
                        }
                    }
                    if (model.diagnostics().isNotEmpty()) {
                        item {
                            Text(
                                "Diagnostics",
                                color = PanelStyle.danger,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(model.diagnostics()) { message ->
                            SelectionContainer {
                                Text(message, fontSize = 12.sp, color = PanelStyle.danger)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.StudioList(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val scroll = rememberLazyListState()
    Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = 14.dp),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun ParameterControl(
    parameter: YsmStudio.Parameter,
    value: Double?,
    onChange: (Double) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(PanelStyle.row, PanelStyle.cardShape).padding(12.dp)
    ) {
        if (parameter.kind() == "checkbox") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(parameter.title(), Modifier.weight(1f), fontSize = 13.sp)
                TriStateCheckbox(
                    if (value == null) androidx.compose.ui.state.ToggleableState.Indeterminate
                    else if (value != 0.0) androidx.compose.ui.state.ToggleableState.On
                    else androidx.compose.ui.state.ToggleableState.Off,
                    { onChange(if (value == null || value == 0.0) 1.0 else 0.0) },
                )
            }
        } else if (parameter.kind() == "radio" && parameter.labels().isNotEmpty()) {
            Text(parameter.title(), fontSize = 13.sp)
            parameter.labels().forEach { (id, label) ->
                val number = id.toDoubleOrNull()
                if (number != null)
                    Row(
                        Modifier.fillMaxWidth().clickable { onChange(number) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(value == number, { onChange(number) })
                        Text(label, fontSize = 12.sp)
                    }
            }
        } else
            StudioSlider(
                parameter.title(),
                value ?: parameter.min(),
                parameter.min(),
                parameter.max(),
                parameter.step(),
                onChange = onChange,
            )
        if (value == null) Text("Using model default", color = PanelStyle.muted, fontSize = 11.sp)
        if (parameter.description().isNotBlank())
            Text(parameter.description(), color = PanelStyle.muted, fontSize = 11.sp)
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    commitOnly: Boolean = false,
    onChange: (Double) -> Unit,
) {
    if (max <= min) return
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(value.toFloat()) }
    LaunchedEffect(value) { if (!dragging) local = value.coerceIn(min, max).toFloat() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), fontSize = 12.sp)
        Text(
            "%.3f".format(local),
            fontSize = 12.sp,
            color = PanelStyle.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
    Slider(
        local.coerceIn(min.toFloat(), max.toFloat()),
        { next ->
            dragging = true
            local =
                (if (step > 0) min + kotlin.math.round((next - min) / step) * step
                    else next.toDouble())
                    .coerceIn(min, max)
                    .toFloat()
            if (!commitOnly) onChange(local.toDouble())
        },
        valueRange = min.toFloat()..max.toFloat(),
        onValueChangeFinished = {
            dragging = false
            onChange(local.toDouble())
        },
    )
}
