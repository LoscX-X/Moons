package com.blanoir.moons.client.ui.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent as AwtMouseEvent
import java.awt.event.MouseWheelEvent
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse

/** Event translation used by the windowless ComposeScene. */
internal object LwjglComposeEvents {
    private val source = object : Component() {}

    fun modifiers(window: Long): Int {
        var result = 0
        if (pressedMouse(window, 0))
            result = result or InputEvent.BUTTON1_DOWN_MASK
        if (pressedMouse(window, 1))
            result = result or InputEvent.BUTTON2_DOWN_MASK
        if (pressedMouse(window, 2))
            result = result or InputEvent.BUTTON3_DOWN_MASK
        if (pressedKey(window, Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL)) {
            result = result or InputEvent.CTRL_DOWN_MASK
        }
        if (pressedKey(window, Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT)) {
            result = result or InputEvent.SHIFT_DOWN_MASK
        }
        if (pressedKey(window, Keyboard.KEY_LMENU, Keyboard.KEY_RMENU)) {
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
                1 -> AwtMouseEvent.BUTTON3
                2 -> AwtMouseEvent.BUTTON2
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
    fun key(window: Long, id: Int, keyCode: Int, codePoint: Int = 0): KeyEvent {
        val awtCode = if (id == AwtKeyEvent.KEY_TYPED) AwtKeyEvent.VK_UNDEFINED else glfwToAwtKey(keyCode)
        val character = if (codePoint == 0) AwtKeyEvent.CHAR_UNDEFINED else codePoint.toChar()
        val mods = modifiers(window)
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

    private fun pressedMouse(window: Long, button: Int) =
        Mouse.isButtonDown(button)

    private fun pressedKey(window: Long, left: Int, right: Int) =
        Keyboard.isKeyDown(left) ||
            Keyboard.isKeyDown(right)

    private fun glfwToAwtKey(key: Int): Int =
        when (key) {


            in Keyboard.KEY_F1..Keyboard.KEY_F10 -> AwtKeyEvent.VK_F1 + key - Keyboard.KEY_F1
            Keyboard.KEY_F11 -> AwtKeyEvent.VK_F11
            Keyboard.KEY_F12 -> AwtKeyEvent.VK_F12
            Keyboard.KEY_F13 -> AwtKeyEvent.VK_F13
            Keyboard.KEY_F14 -> AwtKeyEvent.VK_F14
            Keyboard.KEY_F15 -> AwtKeyEvent.VK_F15
            Keyboard.KEY_NUMPAD0 -> AwtKeyEvent.VK_NUMPAD0
            Keyboard.KEY_NUMPAD1 -> AwtKeyEvent.VK_NUMPAD1
            Keyboard.KEY_NUMPAD2 -> AwtKeyEvent.VK_NUMPAD2
            Keyboard.KEY_NUMPAD3 -> AwtKeyEvent.VK_NUMPAD3
            Keyboard.KEY_NUMPAD4 -> AwtKeyEvent.VK_NUMPAD4
            Keyboard.KEY_NUMPAD5 -> AwtKeyEvent.VK_NUMPAD5
            Keyboard.KEY_NUMPAD6 -> AwtKeyEvent.VK_NUMPAD6
            Keyboard.KEY_NUMPAD7 -> AwtKeyEvent.VK_NUMPAD7
            Keyboard.KEY_NUMPAD8 -> AwtKeyEvent.VK_NUMPAD8
            Keyboard.KEY_NUMPAD9 -> AwtKeyEvent.VK_NUMPAD9
            Keyboard.KEY_NUMPADENTER -> AwtKeyEvent.VK_ENTER
            Keyboard.KEY_DECIMAL -> AwtKeyEvent.VK_DECIMAL
            Keyboard.KEY_ADD -> AwtKeyEvent.VK_ADD
            Keyboard.KEY_SUBTRACT -> AwtKeyEvent.VK_SUBTRACT
            Keyboard.KEY_MULTIPLY -> AwtKeyEvent.VK_MULTIPLY
            Keyboard.KEY_DIVIDE -> AwtKeyEvent.VK_DIVIDE
            Keyboard.KEY_SPACE -> AwtKeyEvent.VK_SPACE
            Keyboard.KEY_APOSTROPHE -> AwtKeyEvent.VK_QUOTE
            Keyboard.KEY_COMMA -> AwtKeyEvent.VK_COMMA
            Keyboard.KEY_MINUS -> AwtKeyEvent.VK_MINUS
            Keyboard.KEY_PERIOD -> AwtKeyEvent.VK_PERIOD
            Keyboard.KEY_SLASH -> AwtKeyEvent.VK_SLASH
            Keyboard.KEY_SEMICOLON -> AwtKeyEvent.VK_SEMICOLON
            Keyboard.KEY_EQUALS -> AwtKeyEvent.VK_EQUALS
            Keyboard.KEY_LBRACKET -> AwtKeyEvent.VK_OPEN_BRACKET
            Keyboard.KEY_BACKSLASH -> AwtKeyEvent.VK_BACK_SLASH
            Keyboard.KEY_RBRACKET -> AwtKeyEvent.VK_CLOSE_BRACKET
            Keyboard.KEY_GRAVE -> AwtKeyEvent.VK_BACK_QUOTE
            Keyboard.KEY_ESCAPE -> AwtKeyEvent.VK_ESCAPE
            Keyboard.KEY_RETURN -> AwtKeyEvent.VK_ENTER
            Keyboard.KEY_TAB -> AwtKeyEvent.VK_TAB
            Keyboard.KEY_BACK -> AwtKeyEvent.VK_BACK_SPACE
            Keyboard.KEY_INSERT -> AwtKeyEvent.VK_INSERT
            Keyboard.KEY_DELETE -> AwtKeyEvent.VK_DELETE
            Keyboard.KEY_RIGHT -> AwtKeyEvent.VK_RIGHT
            Keyboard.KEY_LEFT -> AwtKeyEvent.VK_LEFT
            Keyboard.KEY_DOWN -> AwtKeyEvent.VK_DOWN
            Keyboard.KEY_UP -> AwtKeyEvent.VK_UP
            Keyboard.KEY_PRIOR -> AwtKeyEvent.VK_PAGE_UP
            Keyboard.KEY_NEXT -> AwtKeyEvent.VK_PAGE_DOWN
            Keyboard.KEY_HOME -> AwtKeyEvent.VK_HOME
            Keyboard.KEY_END -> AwtKeyEvent.VK_END
            Keyboard.KEY_LSHIFT,
            Keyboard.KEY_RSHIFT -> AwtKeyEvent.VK_SHIFT
            Keyboard.KEY_LCONTROL,
            Keyboard.KEY_RCONTROL -> AwtKeyEvent.VK_CONTROL
            Keyboard.KEY_LMENU,
            Keyboard.KEY_RMENU -> AwtKeyEvent.VK_ALT

            else -> Keyboard.getKeyName(key)?.singleOrNull()?.let { AwtKeyEvent.getExtendedKeyCodeForChar(it.code) } ?: AwtKeyEvent.VK_UNDEFINED
        }
}
