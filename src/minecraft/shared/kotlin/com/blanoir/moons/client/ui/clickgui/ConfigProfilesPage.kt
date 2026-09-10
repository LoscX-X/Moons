package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.ConfigProfiles

/** Local profile selection with explicit create, save and load actions. */
@Composable
internal fun ConfigProfilesPage(onMutated: () -> Unit) {
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var profiles by remember {
        mutableStateOf(
            runCatching { ConfigProfiles.list() }
                .getOrElse {
                    message = it.message ?: "Could not read configs"
                    failed = true
                    emptyList()
                }
        )
    }
    var selected by remember {
        mutableStateOf(ConfigProfiles.selected().takeIf { it in profiles }.orEmpty())
    }
    var name by remember { mutableStateOf("") }
    fun perform(success: String, action: () -> Unit) {
        try {
            action()
            profiles = ConfigProfiles.list()
            message = success
            failed = false
            onMutated()
        } catch (failure: Exception) {
            message = failure.message ?: "Config operation failed"
            failed = true
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Create config", fontWeight = FontWeight.SemiBold)
        val nameStyle =
            TextStyle(
                fontFamily = PanelFontFamily,
                color = PanelStyle.text,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                name,
                { name = it.take(48) },
                singleLine = true,
                textStyle = nameStyle,
                cursorBrush = SolidColor(PanelStyle.text),
                modifier =
                    Modifier.weight(1f)
                        .height(34.dp)
                        .clip(PanelStyle.controlShape)
                        .background(PanelStyle.field)
                        .border(1.dp, PanelStyle.border, PanelStyle.controlShape)
                        .semantics { contentDescription = "Config name" },
                decorationBox = { input ->
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty())
                            Text("Config name", style = nameStyle, color = PanelStyle.muted)
                        input()
                    }
                },
            )
            SettingsAction("Create", enabled = name.isNotBlank()) {
                val requested = name.trim()
                perform("Created $requested") {
                    ConfigProfiles.create(requested)
                    selected = requested
                    name = ""
                }
            }
        }
        Text(
            "Create captures your current modules, options and key bindings.",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = PanelStyle.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsAction("Save", enabled = selected.isNotEmpty()) {
                perform("Saved $selected") { ConfigProfiles.save(selected) }
            }
            SettingsAction("Load", enabled = selected.isNotEmpty()) {
                perform("Loaded $selected") { ConfigProfiles.load(selected) }
            }
            SettingsAction("Refresh") { perform("Config list refreshed") {} }
        }
        if (message.isNotEmpty())
            Text(
                message,
                color = if (failed) PanelStyle.danger else PanelStyle.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        Text(
            "Configs  ${profiles.size}",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (profiles.isEmpty())
            Text(
                "No configs yet. Enter a name and choose Create.",
                color = PanelStyle.muted,
                fontSize = 12.sp,
            )
        profiles.forEach { profile ->
            Row(
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .clip(PanelStyle.cardShape)
                    .background(if (selected == profile) PanelStyle.selected else PanelStyle.row)
                    .border(
                        1.dp,
                        if (selected == profile) PanelStyle.muted else PanelStyle.border,
                        PanelStyle.cardShape,
                    )
                    .clickable { selected = profile }
                    .semantics { contentDescription = "Select config $profile" }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIcon("configs", Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    profile,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected == profile)
                    Text("Selected", fontSize = 10.sp, color = PanelStyle.muted)
            }
        }
    }
}
