package com.blanoir.moons.client.ui.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import kotlinx.coroutines.awaitCancellation

/** Lets the host window produce characters only while Compose is editing text. */
@OptIn(InternalComposeUiApi::class)
internal class ComposeTextInputContext(private val onInputFocus: (Boolean) -> Unit) :
    PlatformContext by PlatformContext.Empty() {
    private var session: Any? = null

    val isEditing: Boolean
        get() = session != null

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        val current = Any()
        session = current
        onInputFocus(true)
        try {
            awaitCancellation()
        } finally {
            // A cancelled old session must not disable a newly focused field.
            if (session === current) stopInput()
        }
    }

    fun stopInput() {
        if (session == null) return
        session = null
        onInputFocus(false)
    }
}
