package com.blanoir.moons.client.module.impl.player.invmanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager

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
                    val registration = InventoryEditorInput.attach(close)
                    onDispose {
                        registration.close()
                        InvManager.cancelKeyBinding()
                    }
                }
                InventoryConfigurationPage(onMutated, close)
            }
        }
    }
}
