package com.blanoir.moons.client.utils.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.ui.clickgui.PanelFontFamily
import com.blanoir.moons.client.ui.clickgui.PanelStyle
import java.awt.Color as AwtColor

/** Shared by text settings, registry searches and hexadecimal color entry. */
@Composable
internal fun SettingInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle =
            TextStyle(color = PanelStyle.text, fontSize = 7.sp, fontFamily = PanelFontFamily),
        cursorBrush = SolidColor(PanelStyle.controlActive),
        modifier = modifier.fillMaxWidth().height(22.dp),
        decorationBox = { input ->
            Box(
                Modifier.fillMaxSize()
                    .clip(PanelStyle.controlShape)
                    .background(PanelStyle.field)
                    .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = PanelStyle.muted, fontSize = 7.sp)
                input()
            }
        },
    )
}

@Composable
internal fun ColorEditor(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    val rgb = value.removePrefix("#").toIntOrNull(16) ?: 0xffffff
    val initial =
        remember(rgb) {
            AwtColor.RGBtoHSB(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255, null)
        }
    // Keep hue when saturation/value reach zero; RGB alone cannot retain it.
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var brightness by remember { mutableFloatStateOf(initial[2]) }
    LaunchedEffect(rgb) {
        if (initial[1] > 0f && initial[2] > 0f) hue = initial[0]
        if (initial[2] > 0f) saturation = initial[1]
        brightness = initial[2]
    }
    fun update(h: Float = hue, s: Float = saturation, b: Float = brightness) {
        hue = h
        saturation = s
        brightness = b
        onChange("#%06x".format(AwtColor.HSBtoRGB(h, s, b) and 0xffffff))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier.size(22.dp)
                    .clip(PanelStyle.controlShape)
                    .background(Color(0xff000000.toInt() or rgb))
                    .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
                    .clickable { expanded = !expanded }
            )
            SettingInput(
                text,
                {
                    text = it.take(7)
                    if (text.matches(Regex("#[0-9a-fA-F]{6}"))) onChange(text)
                },
                "#RRGGBB",
                Modifier.weight(1f),
            )
        }
        if (expanded) {
            Canvas(
                Modifier.fillMaxWidth().height(80.dp).colorDrag { x, y ->
                    update(s = x, b = 1f - y)
                }
            ) {
                drawRect(Color.hsv(hue * 360f, 1f, 1f))
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val point = Offset(saturation * size.width, (1f - brightness) * size.height)
                drawCircle(Color.Black, 4.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White, 3.dp.toPx(), point, style = Stroke(1.dp.toPx()))
            }
            Canvas(Modifier.fillMaxWidth().height(12.dp).colorDrag { x, _ -> update(h = x) }) {
                drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }))
                val x = hue * size.width
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun Modifier.colorDrag(onChange: (Float, Float) -> Unit): Modifier {
    val latest by rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun change(position: Offset) {
                latest(
                    (position.x / size.width).coerceIn(0f, 1f),
                    (position.y / size.height).coerceIn(0f, 1f),
                )
            }
            down.consume()
            change(down.position)
            do {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!pointer.pressed) break
                pointer.consume()
                change(pointer.position)
            } while (true)
        }
    }
}
