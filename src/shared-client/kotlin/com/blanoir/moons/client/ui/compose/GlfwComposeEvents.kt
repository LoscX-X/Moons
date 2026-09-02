package com.blanoir.moons.client.ui.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.lwjgl.glfw.GLFW
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent as AwtMouseEvent
import java.awt.event.MouseWheelEvent

/** Event translation used by the windowless ComposeScene. */
internal object GlfwComposeEvents {
    private val source = object : Component() {}

    fun modifiers(window: Long): Int {
        var result = 0
        if (pressedMouse(window, GLFW.GLFW_MOUSE_BUTTON_1)) result = result or InputEvent.BUTTON1_DOWN_MASK
        if (pressedMouse(window, GLFW.GLFW_MOUSE_BUTTON_2)) result = result or InputEvent.BUTTON2_DOWN_MASK
        if (pressedMouse(window, GLFW.GLFW_MOUSE_BUTTON_3)) result = result or InputEvent.BUTTON3_DOWN_MASK
        if (pressedKey(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            result = result or InputEvent.CTRL_DOWN_MASK
        }
        if (pressedKey(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            result = result or InputEvent.SHIFT_DOWN_MASK
        }
        if (pressedKey(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
            result = result or InputEvent.ALT_DOWN_MASK
        }
        return result
    }

    fun mouse(x: Int, y: Int, modifiers: Int, button: Int, id: Int): AwtMouseEvent =
        AwtMouseEvent(
            source, id, System.currentTimeMillis(), modifiers, x, y, 1, false,
            when (button) {
                GLFW.GLFW_MOUSE_BUTTON_2 -> AwtMouseEvent.BUTTON2
                GLFW.GLFW_MOUSE_BUTTON_3 -> AwtMouseEvent.BUTTON3
                else -> AwtMouseEvent.BUTTON1
            }
        )

    fun wheel(x: Int, y: Int, amount: Double, modifiers: Int): MouseWheelEvent =
        MouseWheelEvent(
            source, AwtMouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
            x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (-amount).toInt()
        )

    @OptIn(InternalComposeUiApi::class)
    fun key(window: Long, id: Int, keyCode: Int, codePoint: Int = 0): KeyEvent {
        val awtCode = glfwToAwtKey(keyCode)
        val character = if (codePoint == 0) AwtKeyEvent.CHAR_UNDEFINED else codePoint.toChar()
        val mods = modifiers(window)
        return KeyEvent(
            key = Key(awtCode, AwtKeyEvent.KEY_LOCATION_STANDARD),
            type = when (id) {
                AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
                AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
                else -> KeyEventType.Unknown
            },
            codePoint = codePoint,
            nativeEvent = AwtKeyEvent(
                source, id, System.currentTimeMillis(), mods, awtCode, character,
                if (id == AwtKeyEvent.KEY_TYPED) AwtKeyEvent.KEY_LOCATION_UNKNOWN
                else AwtKeyEvent.KEY_LOCATION_STANDARD
            ),
            isCtrlPressed = mods and InputEvent.CTRL_DOWN_MASK != 0,
            isAltPressed = mods and InputEvent.ALT_DOWN_MASK != 0,
            isShiftPressed = mods and InputEvent.SHIFT_DOWN_MASK != 0
        )
    }

    private fun pressedMouse(window: Long, button: Int) =
        GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS

    private fun pressedKey(window: Long, left: Int, right: Int) =
        GLFW.glfwGetKey(window, left) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, right) == GLFW.GLFW_PRESS

    private fun glfwToAwtKey(key: Int): Int = when (key) {
        in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> AwtKeyEvent.VK_A + key - GLFW.GLFW_KEY_A
        in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> AwtKeyEvent.VK_0 + key - GLFW.GLFW_KEY_0
        GLFW.GLFW_KEY_SPACE -> AwtKeyEvent.VK_SPACE
        GLFW.GLFW_KEY_APOSTROPHE -> AwtKeyEvent.VK_QUOTE
        GLFW.GLFW_KEY_COMMA -> AwtKeyEvent.VK_COMMA
        GLFW.GLFW_KEY_MINUS -> AwtKeyEvent.VK_MINUS
        GLFW.GLFW_KEY_PERIOD -> AwtKeyEvent.VK_PERIOD
        GLFW.GLFW_KEY_SLASH -> AwtKeyEvent.VK_SLASH
        GLFW.GLFW_KEY_SEMICOLON -> AwtKeyEvent.VK_SEMICOLON
        GLFW.GLFW_KEY_EQUAL -> AwtKeyEvent.VK_EQUALS
        GLFW.GLFW_KEY_LEFT_BRACKET -> AwtKeyEvent.VK_OPEN_BRACKET
        GLFW.GLFW_KEY_BACKSLASH -> AwtKeyEvent.VK_BACK_SLASH
        GLFW.GLFW_KEY_RIGHT_BRACKET -> AwtKeyEvent.VK_CLOSE_BRACKET
        GLFW.GLFW_KEY_GRAVE_ACCENT -> AwtKeyEvent.VK_BACK_QUOTE
        GLFW.GLFW_KEY_ESCAPE -> AwtKeyEvent.VK_ESCAPE
        GLFW.GLFW_KEY_ENTER -> AwtKeyEvent.VK_ENTER
        GLFW.GLFW_KEY_TAB -> AwtKeyEvent.VK_TAB
        GLFW.GLFW_KEY_BACKSPACE -> AwtKeyEvent.VK_BACK_SPACE
        GLFW.GLFW_KEY_INSERT -> AwtKeyEvent.VK_INSERT
        GLFW.GLFW_KEY_DELETE -> AwtKeyEvent.VK_DELETE
        GLFW.GLFW_KEY_RIGHT -> AwtKeyEvent.VK_RIGHT
        GLFW.GLFW_KEY_LEFT -> AwtKeyEvent.VK_LEFT
        GLFW.GLFW_KEY_DOWN -> AwtKeyEvent.VK_DOWN
        GLFW.GLFW_KEY_UP -> AwtKeyEvent.VK_UP
        GLFW.GLFW_KEY_PAGE_UP -> AwtKeyEvent.VK_PAGE_UP
        GLFW.GLFW_KEY_PAGE_DOWN -> AwtKeyEvent.VK_PAGE_DOWN
        GLFW.GLFW_KEY_HOME -> AwtKeyEvent.VK_HOME
        GLFW.GLFW_KEY_END -> AwtKeyEvent.VK_END
        GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> AwtKeyEvent.VK_SHIFT
        GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> AwtKeyEvent.VK_CONTROL
        GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> AwtKeyEvent.VK_ALT
        in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F24 -> AwtKeyEvent.VK_F1 + key - GLFW.GLFW_KEY_F1
        else -> AwtKeyEvent.VK_UNDEFINED
    }
}
