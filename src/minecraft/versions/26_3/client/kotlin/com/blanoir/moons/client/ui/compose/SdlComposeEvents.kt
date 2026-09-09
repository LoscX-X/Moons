package com.blanoir.moons.client.ui.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import com.mojang.blaze3d.platform.InputConstants
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent as AwtMouseEvent
import java.awt.event.MouseWheelEvent
import org.lwjgl.sdl.SDLMouse

/** Event translation used by the windowless ComposeScene. */
internal object SdlComposeEvents {
    private val source = object : Component() {}

    fun modifiers(): Int {
        var result = 0
        if (pressedMouse(InputConstants.MOUSE_BUTTON_LEFT))
            result = result or InputEvent.BUTTON1_DOWN_MASK
        if (pressedMouse(InputConstants.MOUSE_BUTTON_MIDDLE))
            result = result or InputEvent.BUTTON2_DOWN_MASK
        if (pressedMouse(InputConstants.MOUSE_BUTTON_RIGHT))
            result = result or InputEvent.BUTTON3_DOWN_MASK
        if (pressedKey(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL)) {
            result = result or InputEvent.CTRL_DOWN_MASK
        }
        if (pressedKey(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT)) {
            result = result or InputEvent.SHIFT_DOWN_MASK
        }
        if (pressedKey(InputConstants.KEY_LALT, InputConstants.KEY_RALT)) {
            result = result or InputEvent.ALT_DOWN_MASK
        }
        return result
    }

    fun mouse(x: Int, y: Int, modifiers: Int, button: Int, id: Int): AwtMouseEvent =
        AwtMouseEvent(
            source,
            id,
            System.currentTimeMillis(),
            modifiers,
            x,
            y,
            1,
            false,
            when (button) {
                InputConstants.MOUSE_BUTTON_MIDDLE -> AwtMouseEvent.BUTTON2
                InputConstants.MOUSE_BUTTON_RIGHT -> AwtMouseEvent.BUTTON3
                else -> AwtMouseEvent.BUTTON1
            },
        )

    fun wheel(x: Int, y: Int, amount: Double, modifiers: Int): MouseWheelEvent =
        MouseWheelEvent(
            source,
            AwtMouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            modifiers,
            x,
            y,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            (-amount).toInt(),
        )

    @OptIn(InternalComposeUiApi::class)
    fun key(id: Int, keyCode: Int, codePoint: Int = 0): KeyEvent {
        val awtCode = sdlToAwtKey(keyCode)
        val character = if (codePoint == 0) AwtKeyEvent.CHAR_UNDEFINED else codePoint.toChar()
        val mods = modifiers()
        return KeyEvent(
            key = Key(awtCode, AwtKeyEvent.KEY_LOCATION_STANDARD),
            type =
                when (id) {
                    AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
                    AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
                    else -> KeyEventType.Unknown
                },
            codePoint = codePoint,
            nativeEvent =
                AwtKeyEvent(
                    source,
                    id,
                    System.currentTimeMillis(),
                    mods,
                    awtCode,
                    character,
                    if (id == AwtKeyEvent.KEY_TYPED) AwtKeyEvent.KEY_LOCATION_UNKNOWN
                    else AwtKeyEvent.KEY_LOCATION_STANDARD,
                ),
            isCtrlPressed = mods and InputEvent.CTRL_DOWN_MASK != 0,
            isAltPressed = mods and InputEvent.ALT_DOWN_MASK != 0,
            isShiftPressed = mods and InputEvent.SHIFT_DOWN_MASK != 0,
        )
    }

    fun pointerButton(button: Int) =
        PointerButton(
            when (button) {
                InputConstants.MOUSE_BUTTON_LEFT -> 0
                InputConstants.MOUSE_BUTTON_RIGHT -> 1
                InputConstants.MOUSE_BUTTON_MIDDLE -> 2
                else -> button - 1
            }
        )

    private fun pressedMouse(button: Int) =
        SDLMouse.nSDL_GetMouseState(0L, 0L) and (1 shl (button - 1)) != 0

    private fun pressedKey(left: Int, right: Int) =
        InputConstants.isKeyDown(left) || InputConstants.isKeyDown(right)

    private fun sdlToAwtKey(key: Int): Int =
        when (key) {
            in InputConstants.KEY_A..InputConstants.KEY_Z ->
                AwtKeyEvent.VK_A + key - InputConstants.KEY_A
            InputConstants.KEY_0 -> AwtKeyEvent.VK_0
            in InputConstants.KEY_1..InputConstants.KEY_9 ->
                AwtKeyEvent.VK_1 + key - InputConstants.KEY_1
            InputConstants.KEY_SPACE -> AwtKeyEvent.VK_SPACE
            InputConstants.KEY_APOSTROPHE -> AwtKeyEvent.VK_QUOTE
            InputConstants.KEY_COMMA -> AwtKeyEvent.VK_COMMA
            InputConstants.KEY_MINUS -> AwtKeyEvent.VK_MINUS
            InputConstants.KEY_PERIOD -> AwtKeyEvent.VK_PERIOD
            InputConstants.KEY_SLASH -> AwtKeyEvent.VK_SLASH
            InputConstants.KEY_SEMICOLON -> AwtKeyEvent.VK_SEMICOLON
            InputConstants.KEY_EQUALS -> AwtKeyEvent.VK_EQUALS
            InputConstants.KEY_LBRACKET -> AwtKeyEvent.VK_OPEN_BRACKET
            InputConstants.KEY_BACKSLASH -> AwtKeyEvent.VK_BACK_SLASH
            InputConstants.KEY_RBRACKET -> AwtKeyEvent.VK_CLOSE_BRACKET
            InputConstants.KEY_GRAVE -> AwtKeyEvent.VK_BACK_QUOTE
            InputConstants.KEY_ESCAPE -> AwtKeyEvent.VK_ESCAPE
            InputConstants.KEY_RETURN -> AwtKeyEvent.VK_ENTER
            InputConstants.KEY_TAB -> AwtKeyEvent.VK_TAB
            InputConstants.KEY_BACKSPACE -> AwtKeyEvent.VK_BACK_SPACE
            InputConstants.KEY_INSERT -> AwtKeyEvent.VK_INSERT
            InputConstants.KEY_DELETE -> AwtKeyEvent.VK_DELETE
            InputConstants.KEY_RIGHT -> AwtKeyEvent.VK_RIGHT
            InputConstants.KEY_LEFT -> AwtKeyEvent.VK_LEFT
            InputConstants.KEY_DOWN -> AwtKeyEvent.VK_DOWN
            InputConstants.KEY_UP -> AwtKeyEvent.VK_UP
            InputConstants.KEY_PAGEUP -> AwtKeyEvent.VK_PAGE_UP
            InputConstants.KEY_PAGEDOWN -> AwtKeyEvent.VK_PAGE_DOWN
            InputConstants.KEY_HOME -> AwtKeyEvent.VK_HOME
            InputConstants.KEY_END -> AwtKeyEvent.VK_END
            InputConstants.KEY_LSHIFT,
            InputConstants.KEY_RSHIFT -> AwtKeyEvent.VK_SHIFT
            InputConstants.KEY_LCONTROL,
            InputConstants.KEY_RCONTROL -> AwtKeyEvent.VK_CONTROL
            InputConstants.KEY_LALT,
            InputConstants.KEY_RALT -> AwtKeyEvent.VK_ALT
            in InputConstants.KEY_F1..InputConstants.KEY_F12 ->
                AwtKeyEvent.VK_F1 + key - InputConstants.KEY_F1
            in InputConstants.KEY_F13..InputConstants.KEY_F24 ->
                AwtKeyEvent.VK_F13 + key - InputConstants.KEY_F13
            else -> AwtKeyEvent.VK_UNDEFINED
        }
}
