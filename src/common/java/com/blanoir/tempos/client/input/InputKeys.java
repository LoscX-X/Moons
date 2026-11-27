package com.blanoir.tempos.client.input;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.input.KeyEvent;

/** Validated platform key identities; configuration and feature policy live with their owners. */
public final class InputKeys {
    private InputKeys() {}

    public static InputConstants.Key fromEvent(KeyEvent event) {
        if (event == null) return InputConstants.UNKNOWN;
        try {
            return validOrUnknown(InputConstants.getKey(event));
        } catch (RuntimeException ignored) {
            return InputConstants.UNKNOWN;
        }
    }

    public static InputConstants.Key fromMouseButton(int button) {
        return isMouseButton(button)
                ? validOrUnknown(InputConstants.Type.MOUSE.getOrCreate(button))
                : InputConstants.UNKNOWN;
    }

    public static boolean isValid(InputConstants.Key key) {
        return key != null
                && key != InputConstants.UNKNOWN
                && key.getValue() >= 0
                && (key.getType() != InputConstants.Type.MOUSE || isMouseButton(key.getValue()));
    }

    public static InputConstants.Key parse(String name) {
        if (name == null || name.isBlank()) return InputConstants.UNKNOWN;
        try {
            return validOrUnknown(InputConstants.getKey(name));
        } catch (RuntimeException ignored) {
            return InputConstants.UNKNOWN;
        }
    }

    public static InputConstants.Key validOrUnknown(InputConstants.Key key) {
        return isValid(key) ? key : InputConstants.UNKNOWN;
    }

    /** Compact keycap text. Saved bindings still use Minecraft's locale-independent key identity. */
    public static String label(InputConstants.Key key) {
        if (!isValid(key)) return "";
        return switch (key.getName()) {
            case "key.keyboard.right.shift" -> "Rshift";
            case "key.keyboard.left.shift" -> "Lshift";
            case "key.keyboard.right.control" -> "Rctrl";
            case "key.keyboard.left.control" -> "Lctrl";
            case "key.keyboard.right.alt" -> "Ralt";
            case "key.keyboard.left.alt" -> "Lalt";
            case "key.keyboard.right.win", "key.keyboard.right.windows", "key.keyboard.right.super" -> "Rwin";
            case "key.keyboard.left.win", "key.keyboard.left.windows", "key.keyboard.left.super" -> "Lwin";
            case "key.keyboard.right.command" -> "Rcmd";
            case "key.keyboard.left.command" -> "Lcmd";
            case "key.keyboard.right.option" -> "Ropt";
            case "key.keyboard.left.option" -> "Lopt";
            case "key.keyboard.right.meta" -> "Rmeta";
            case "key.keyboard.left.meta" -> "Lmeta";
            case "key.keyboard.page.up" -> "PgUp";
            case "key.keyboard.page.down" -> "PgDn";
            case "key.keyboard.caps.lock" -> "Caps";
            case "key.keyboard.num.lock" -> "Num";
            case "key.keyboard.scroll.lock" -> "ScrLk";
            case "key.keyboard.print.screen" -> "PrtSc";
            case "key.keyboard.insert" -> "Ins";
            case "key.keyboard.delete" -> "Del";
            case "key.keyboard.backspace" -> "Bksp";
            case "key.keyboard.escape" -> "Esc";
            case "key.keyboard.keypad.enter" -> "KP Enter";
            case "key.keyboard.keypad.add" -> "KP+";
            case "key.keyboard.keypad.subtract" -> "KP−";
            case "key.keyboard.keypad.multiply" -> "KP×";
            case "key.keyboard.keypad.divide" -> "KP/";
            case "key.keyboard.keypad.decimal" -> "KP.";
            case "key.keyboard.keypad.equal" -> "KP=";
            case "key.keyboard.keypad.0", "key.keyboard.keypad.1", "key.keyboard.keypad.2",
                    "key.keyboard.keypad.3", "key.keyboard.keypad.4", "key.keyboard.keypad.5",
                    "key.keyboard.keypad.6", "key.keyboard.keypad.7", "key.keyboard.keypad.8",
                    "key.keyboard.keypad.9" -> "KP" + key.getName().charAt("key.keyboard.keypad.".length());
            case "key.mouse.left" -> "M1";
            case "key.mouse.right" -> "M2";
            case "key.mouse.middle" -> "M3";
            case "key.mouse.4" -> "M4";
            case "key.mouse.5" -> "M5";
            case "key.mouse.6" -> "M6";
            case "key.mouse.7" -> "M7";
            case "key.mouse.8" -> "M8";
            default -> key.getDisplayName().getString();
        };
    }

    private static boolean isMouseButton(int button) {
        // GLFW starts at 0, SDL at 1. 26.1.2's MOUSE_BUTTON_8 constant incorrectly equals 0.
        int first = InputConstants.MOUSE_BUTTON_LEFT;
        return button >= first && button < first + 8;
    }
}
