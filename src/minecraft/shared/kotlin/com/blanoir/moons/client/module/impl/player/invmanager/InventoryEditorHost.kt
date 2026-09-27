package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Keeps the originating layout mounted, preserving selection and scroll position on return. */
@Composable
internal fun InventoryEditorHost(
    onMutated: () -> Unit,
    onBeforeOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    CompositionLocalProvider(
        LocalOpenInventoryEditor provides
            {
                focus.clearFocus()
                onBeforeOpen()
                open = true
            }
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (open) {
                val close = {
                    open = false
                    InvManager.cancelKeyBinding()
                }
                DisposableEffect(Unit) {
                    onDispose { InvManager.cancelKeyBinding() }
                }
                Popup(
                    onDismissRequest = close,
                    onPreviewKeyEvent = {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                            close()
                            true
                        } else false
                    },
                    properties =
                        PopupProperties(
                            focusable = true,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = false,
                        ),
                ) {
                    InventoryConfigurationPage(onMutated, close)
                }
            }
        }
    }
}
