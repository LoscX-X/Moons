package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One line box and center line for the icon, placeholder, input and clear button. */
@Composable
internal fun PanelSearch(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val textStyle =
        TextStyle(
            color = PanelStyle.text,
            fontFamily = PanelFontFamily,
            fontSize = if (compact) 8.sp else 13.sp,
            lineHeight = if (compact) 10.sp else 18.sp,
            letterSpacing = 0.sp,
            lineHeightStyle =
                LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        )
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(PanelStyle.text),
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (compact) 28.dp else 38.dp)
                .clip(PanelStyle.controlShape)
                .background(PanelStyle.field)
                .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
                .semantics { contentDescription = "Search modules" },
        decorationBox = { input ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = if (compact) 9.dp else 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIcon("search", Modifier.size(if (compact) 11.dp else 18.dp))
                Spacer(Modifier.width(if (compact) 7.dp else 9.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty())
                        Text(
                            "Search modules",
                            style = textStyle,
                            color = PanelStyle.muted,
                            maxLines = 1,
                            softWrap = false,
                        )
                    input()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.size(if (compact) 18.dp else 28.dp)
                            .clip(PanelStyle.controlShape)
                            .clickable { onChange("") }
                            .semantics { contentDescription = "Clear search" },
                        contentAlignment = Alignment.Center,
                    ) {
                        SettingsIcon("close", Modifier.size(if (compact) 11.dp else 17.dp))
                    }
                }
            }
        },
    )
}
