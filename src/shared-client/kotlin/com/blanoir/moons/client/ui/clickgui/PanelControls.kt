package com.blanoir.moons.client.ui.clickgui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.module.framework.ModuleKeybinds
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module
import com.blanoir.moons.client.module.framework.ModuleRegistry.Setting
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Module rows and setting controls. */
@Composable
internal fun CollapseButton(collapsed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(16.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.width(7.dp).height(1.dp).background(PanelStyle.muted))
        if (collapsed) {
            Box(Modifier.width(1.dp).height(7.dp).background(PanelStyle.muted))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModuleRow(
    module: Module,
    expanded: Boolean,
    bindingModuleId: String?,
    onExpand: () -> Unit,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit
) {
    val registryEnabled = moduleEnabled(module)
    var enabled by remember(module.id()) { mutableStateOf(registryEnabled) }
    var settingsRevision by remember(module.id()) { mutableIntStateOf(0) }
    val refreshSettings = {
        settingsRevision++
        onMutated()
    }
    LaunchedEffect(registryEnabled) { enabled = registryEnabled }
    val background by animateColorAsState(
        if (enabled) PanelStyle.rowEnabled else PanelStyle.row,
        animationSpec = tween(90)
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).background(background)
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Secondary) {
                        event.changes.forEach { it.consume() }
                        onExpand()
                    }
                }
                .clickable {
                    val next = !enabled
                    enabled = next
                    ModuleRegistry.setEnabled(module.id(), next)
                    enabled = moduleEnabled(module)
                    onMutated()
                }.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(2.dp).height(11.dp).clip(CircleShape)
                    .background(if (enabled) PanelStyle.accent else PanelStyle.border)
            )
            Spacer(Modifier.width(7.dp))
            Text(module.name(), color = if (enabled) PanelStyle.text else PanelStyle.muted,
                fontSize = 8.sp, fontWeight = if (enabled) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.width(26.dp).fillMaxHeight().clickable(onClick = onExpand),
                contentAlignment = Alignment.Center
            ) {
                Text(if (expanded) "⌄" else "⋮", color = if (expanded) PanelStyle.accent else PanelStyle.dim,
                    fontSize = if (expanded) 10.sp else 11.sp)
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(160)),
            exit = shrinkVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(120))
        ) {
            Column(
                Modifier.fillMaxWidth().background(PanelStyle.setting)
                    .padding(start = 8.dp, end = 7.dp, top = 4.dp, bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KeybindSetting(module, bindingModuleId, onBindingModuleChange)
                val visibleSettings = settingsRevision.let {
                    module.settings().filter { setting -> setting.isVisible }
                }
                visibleSettings.forEach { setting ->
                    key(setting.id()) {
                        CompactSetting(module, setting, refreshSettings)
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(PanelStyle.border.copy(alpha = 0.55f)))
}

@Composable
private fun KeybindSetting(
    module: Module,
    bindingModuleId: String?,
    onBindingModuleChange: (String?) -> Unit
) {
    val listening = bindingModuleId == module.id()
    val keyName = ModuleKeybinds.getBoundKey(module.id()).let {
        if (it == InputConstants.UNKNOWN) "None" else it.displayName.string
    }
    CompactRow {
        Text("Keybind", color = PanelStyle.muted, fontSize = 7.sp, lineHeight = 7.sp,
            modifier = Modifier.weight(1f))
        ValueButton(
            if (listening) "Press key…" else keyName,
            active = listening,
            onClick = { onBindingModuleChange(if (listening) null else module.id()) }
        )
    }
}

@Composable
private fun CompactSetting(module: Module, setting: Setting, onMutated: () -> Unit) {
    when (setting.type()) {
        "boolean" -> BooleanSetting(module, setting, onMutated)
        "number", "integer" -> NumberSetting(module, setting, onMutated)
        "range" -> RangeSetting(module, setting, onMutated)
        "choice" -> ChoiceSetting(module, setting, onMutated)
        "text", "color" -> TextSetting(module, setting, onMutated)
        else -> CompactRow { Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp) }
    }
}

@Composable
private fun CompactRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp))
            .background(PanelStyle.row).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun BooleanSetting(module: Module, setting: Setting, onMutated: () -> Unit) {
    val checked = runCatching { setting.value().get().asBoolean }.getOrDefault(false)
    var displayedChecked by remember(module.id(), setting.id()) { mutableStateOf(checked) }
    LaunchedEffect(checked) { displayedChecked = checked }
    CompactRow {
        Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompactSwitch(displayedChecked) { next ->
            displayedChecked = next
            ModuleRegistry.setValue(module.id(), setting.id(), JsonPrimitive(next))
            displayedChecked = runCatching { setting.value().get().asBoolean }.getOrDefault(next)
            onMutated()
        }
    }
}

@Composable
private fun NumberSetting(module: Module, setting: Setting, onMutated: () -> Unit) {
    val min = setting.min() ?: 0.0
    val max = setting.max() ?: 1.0
    val value = runCatching { setting.value().get().asDouble }.getOrDefault(min).coerceIn(min, max)
    var displayedValue by remember(module.id(), setting.id()) { mutableStateOf(value) }
    var inputValue by remember(module.id(), setting.id()) { mutableStateOf(formatValue(value)) }
    var inputFocused by remember(module.id(), setting.id()) { mutableStateOf(false) }
    var inputValid by remember(module.id(), setting.id()) { mutableStateOf(true) }
    LaunchedEffect(value) {
        displayedValue = value
        if (!inputFocused) {
            inputValue = formatValue(value)
            inputValid = true
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PanelStyle.row)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = inputValue,
                onValueChange = { nextText ->
                    if (nextText.length > 24 || !isPotentialNumber(nextText)) return@BasicTextField
                    inputValue = nextText
                    val parsed = nextText.toDoubleOrNull()
                    val integerValue = setting.type() == "integer"
                    val valid = parsed != null && parsed.isFinite() && parsed in min..max &&
                        (!integerValue || parsed == parsed.roundToInt().toDouble())
                    inputValid = valid
                    if (valid) {
                        displayedValue = parsed
                        ModuleRegistry.setValue(module.id(), setting.id(),
                            if (integerValue) JsonPrimitive(parsed.roundToInt()) else JsonPrimitive(parsed))
                        onMutated()
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (inputValid) PanelStyle.accentBright else Color(0xFFE57373),
                    fontSize = 7.sp,
                    fontFamily = PanelFontFamily,
                    textAlign = TextAlign.End
                ),
                cursorBrush = SolidColor(PanelStyle.accent),
                modifier = Modifier.width(58.dp).height(18.dp).onFocusChanged { state ->
                    val wasFocused = inputFocused
                    inputFocused = state.isFocused
                    if (wasFocused && !state.isFocused) {
                        inputValue = formatValue(displayedValue)
                        inputValid = true
                    }
                },
                decorationBox = { input ->
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(PanelStyle.field)
                            .border(1.dp, if (inputValid) PanelStyle.border else Color(0xFFE57373), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) { input() }
                }
            )
        }
        Spacer(Modifier.height(2.dp))
        SlimSlider(
            value = displayedValue.toFloat(),
            range = min.toFloat()..max.toFloat(),
            onValueChange = { raw ->
                displayedValue = snap(raw.toDouble(), min, max, setting.step())
                inputValue = formatValue(displayedValue)
                inputValid = true
            },
            onValueChangeFinished = { raw ->
                val next = snap(raw.toDouble(), min, max, setting.step())
                displayedValue = next
                inputValue = formatValue(next)
                inputValid = true
                ModuleRegistry.setValue(module.id(), setting.id(),
                    if (setting.type() == "integer") JsonPrimitive(next.roundToInt()) else JsonPrimitive(next))
                onMutated()
            }
        )
    }
}

@Composable
private fun RangeSetting(module: Module, setting: Setting, onMutated: () -> Unit) {
    val min = setting.min() ?: 0.0
    val max = setting.max() ?: 1.0
    val values = runCatching { setting.value().get().asJsonArray }.getOrElse {
        JsonArray().apply { add(min); add(max) }
    }
    val low = (if (values.size() > 0) values[0].asDouble else min).coerceIn(min, max)
    val high = (if (values.size() > 1) values[1].asDouble else max).coerceIn(low, max)
    var displayedLow by remember(module.id(), setting.id()) { mutableStateOf(low) }
    var displayedHigh by remember(module.id(), setting.id()) { mutableStateOf(high) }
    LaunchedEffect(low, high) {
        displayedLow = low
        displayedHigh = high
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PanelStyle.row)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatValue(displayedLow)}–${formatValue(displayedHigh)}",
                color = PanelStyle.accentBright, fontSize = 7.sp)
        }
        Spacer(Modifier.height(2.dp))
        RangeSliderRow("MIN") {
            SlimSlider(
                value = displayedLow.toFloat(),
                range = min.toFloat()..max.toFloat(),
                onValueChange = { raw ->
                    displayedLow = snap(raw.toDouble(), min, displayedHigh, setting.step())
                },
                onValueChangeFinished = { raw ->
                    displayedLow = snap(raw.toDouble(), min, displayedHigh, setting.step())
                    ModuleRegistry.setValue(module.id(), setting.id(), jsonRange(displayedLow, displayedHigh))
                    onMutated()
                }
            )
        }
        RangeSliderRow("MAX") {
            SlimSlider(
                value = displayedHigh.toFloat(),
                range = min.toFloat()..max.toFloat(),
                onValueChange = { raw ->
                    displayedHigh = snap(raw.toDouble(), displayedLow, max, setting.step())
                },
                onValueChangeFinished = { raw ->
                    displayedHigh = snap(raw.toDouble(), displayedLow, max, setting.step())
                    ModuleRegistry.setValue(module.id(), setting.id(), jsonRange(displayedLow, displayedHigh))
                    onMutated()
                }
            )
        }
    }
}

@Composable
private fun ChoiceSetting(module: Module, setting: Setting, onMutated: () -> Unit) {
    val current = runCatching { setting.value().get().asString }.getOrDefault("")
    val options = setting.options()
    var displayedChoice by remember(module.id(), setting.id()) { mutableStateOf(current) }
    LaunchedEffect(current) { displayedChoice = current }
    if (module.id() == "trimchanger" && setting.id() == "trim") {
        TrimChoiceSetting(module, setting, options, displayedChoice, onMutated) {
            displayedChoice = it
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PanelStyle.row)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp, lineHeight = 8.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        ValueButton(ModuleRegistry.displayChoice(displayedChoice).ifBlank { "Select" }, fullWidth = true) {
            if (options.isNotEmpty()) {
                val index = options.indexOfFirst { it.equals(displayedChoice, ignoreCase = true) }
                val next = options[(index + 1).mod(options.size)]
                displayedChoice = next
                ModuleRegistry.setValue(module.id(), setting.id(), JsonPrimitive(next))
                displayedChoice = runCatching { setting.value().get().asString }.getOrDefault(next)
                onMutated()
            }
        }
    }
}

@Composable
private fun TrimChoiceSetting(
    module: Module,
    setting: Setting,
    options: List<String>,
    current: String,
    onMutated: () -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember(module.id(), setting.id()) { mutableStateOf(false) }
    val selectedIcon = remember(current) { trimIcon(current) }
    val selectedName = ModuleRegistry.displayChoice(current).ifBlank { "Select trim" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PanelStyle.row)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(4.dp))
                .background(PanelStyle.field)
                .border(
                    1.dp,
                    if (expanded) PanelStyle.accent else PanelStyle.border,
                    RoundedCornerShape(4.dp)
                )
                .clickable(enabled = options.isNotEmpty()) { expanded = !expanded }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedIcon != null) {
                Image(
                    bitmap = selectedIcon,
                    contentDescription = current,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Box(Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                selectedName,
                color = PanelStyle.text,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "⌃" else "⌄", color = PanelStyle.muted, fontSize = 9.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(140)) + fadeIn(animationSpec = tween(100)),
            exit = shrinkVertically(animationSpec = tween(120)) + fadeOut(animationSpec = tween(80))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = 218.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.chunked(3).forEach { rowOptions ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowOptions.forEach { option ->
                            val selected = option.equals(current, ignoreCase = true)
                            val icon = remember(option) { trimIcon(option) }
                            Column(
                                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (selected) PanelStyle.accentSoft else PanelStyle.field)
                                    .border(
                                        1.dp,
                                        if (selected) PanelStyle.accent else PanelStyle.border,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        onSelected(option)
                                        ModuleRegistry.setValue(module.id(), setting.id(), JsonPrimitive(option))
                                        expanded = false
                                        onMutated()
                                    }
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = option,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Box(Modifier.size(24.dp))
                                }
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    ModuleRegistry.displayChoice(option),
                                    color = if (selected) PanelStyle.accentBright else PanelStyle.muted,
                                    fontSize = 5.5.sp,
                                    lineHeight = 6.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextSetting(module: Module, setting: Setting, onMutated: () -> Unit) {
    val current = runCatching { setting.value().get().asString }.getOrDefault("")
    var value by remember(setting.id(), current) { mutableStateOf(current) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PanelStyle.row)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(setting.name(), color = PanelStyle.muted, fontSize = 7.sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = {
                value = it.take(128)
                if (setting.type() != "color" || isCompleteColor(value)) {
                    ModuleRegistry.setValue(module.id(), setting.id(), JsonPrimitive(value))
                    onMutated()
                }
            },
            singleLine = true,
            textStyle = TextStyle(
                color = PanelStyle.text,
                fontSize = 7.sp,
                fontFamily = PanelFontFamily
            ),
            cursorBrush = SolidColor(PanelStyle.accent),
            modifier = Modifier.fillMaxWidth().height(22.dp),
            decorationBox = { input ->
                Row(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(PanelStyle.field)
                        .border(1.dp, PanelStyle.border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) { input() }
                    if (setting.type() == "color") {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(parseColor(value)))
                    }
                }
            }
        )
    }
}

@Composable
private fun ValueButton(
    value: String,
    active: Boolean = false,
    fullWidth: Boolean = false,
    onClick: () -> Unit
) {
    val widthModifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 34.dp, max = 74.dp)
    Box(
        widthModifier.height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) PanelStyle.accentSoft else PanelStyle.field)
            .border(1.dp, if (active) PanelStyle.accent else PanelStyle.border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(value, color = if (active) PanelStyle.accentBright else PanelStyle.muted,
            fontSize = 6.5.sp, lineHeight = 6.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompactSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        if (checked) PanelStyle.accent else PanelStyle.track,
        animationSpec = tween(130)
    )
    val thumbX by animateDpAsState(if (checked) 13.dp else 2.dp, animationSpec = tween(130))
    Box(
        Modifier.width(22.dp).height(11.dp).clip(CircleShape).background(track)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.offset(x = thumbX).size(7.dp).clip(CircleShape)
                .background(if (checked) Color(0xFF17130D) else PanelStyle.muted)
        )
    }
}

@Composable
private fun RangeSliderRow(label: String, slider: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = PanelStyle.dim, fontSize = 5.5.sp, lineHeight = 5.5.sp,
            modifier = Modifier.width(20.dp), maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { slider() }
    }
}

@Composable
private fun SlimSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit
) {
    var widthPx by remember { mutableStateOf(1f) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val span = range.endInclusive - range.start
    val fraction = if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)

    fun valueAt(x: Float): Float {
        val amount = (x / widthPx).coerceIn(0f, 1f)
        return range.start + amount * span
    }

    Box(
        Modifier.fillMaxWidth().height(12.dp).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(range, widthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pending = valueAt(down.position.x)
                    currentOnValueChange(pending)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        pending = valueAt(change.position.x)
                        currentOnValueChange(pending)
                        change.consume()
                    }
                    currentOnValueChangeFinished(pending)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(PanelStyle.track))
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).clip(CircleShape).background(PanelStyle.accent))
        }
        Box(
            Modifier.offset {
                IntOffset((fraction * (widthPx - 7.dp.toPx())).roundToInt(), 0)
            }.size(7.dp).clip(CircleShape).background(PanelStyle.accentBright)
                .border(1.dp, PanelStyle.accentSoft, CircleShape)
        )
    }
}
