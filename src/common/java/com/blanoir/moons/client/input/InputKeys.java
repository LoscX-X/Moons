package com.blanoir.moons.client.input;

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

    private static boolean isMouseButton(int button) {
        // GLFW starts at 0, SDL at 1. 26.1.2's MOUSE_BUTTON_8 constant incorrectly equals 0.
        int first = InputConstants.MOUSE_BUTTON_LEFT;
        return button >= first && button < first + 8;
    }
}
