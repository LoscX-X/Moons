package com.blanoir.moons.client.compat.input;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.*;

/** Stable Moons binding names over LWJGL 2 scan codes; never changes host key bindings. */
public final class InputConstants {
    public static final int PRESS = 1,
            RELEASE = 0,
            REPEAT = 2,
            KEY_ESCAPE = Keyboard.KEY_ESCAPE,
            KEY_BACKSPACE = Keyboard.KEY_BACK,
            KEY_DELETE = Keyboard.KEY_DELETE;
    public static final int MOUSE_BUTTON_LEFT = 0, MOUSE_BUTTON_RIGHT = 1;
    public static final Key UNKNOWN = new Key(Type.KEYSYM, -1);
    private static final Map<String, Integer> NAMES = new HashMap<>();
    private static final Map<Integer, String> CANONICAL = new HashMap<>();

    static {
        for (int code = 1; code < 256; code++) {
            String name = Keyboard.getKeyName(code);
            if (name != null) {
                name = name.toLowerCase(Locale.ROOT);
                NAMES.put(name, code);
                CANONICAL.put(code, name);
            }
        }
        alias("right.shift", Keyboard.KEY_RSHIFT);
        alias("left.shift", Keyboard.KEY_LSHIFT);
        alias("right.control", Keyboard.KEY_RCONTROL);
        alias("left.control", Keyboard.KEY_LCONTROL);
        alias("right.alt", Keyboard.KEY_RMENU);
        alias("left.alt", Keyboard.KEY_LMENU);
        alias("enter", Keyboard.KEY_RETURN);
        alias("backspace", Keyboard.KEY_BACK);
        alias("delete", Keyboard.KEY_DELETE);
        alias("escape", Keyboard.KEY_ESCAPE);
        alias("page.up", Keyboard.KEY_PRIOR);
        alias("page.down", Keyboard.KEY_NEXT);
        alias("grave.accent", Keyboard.KEY_GRAVE);
        alias("caps.lock", Keyboard.KEY_CAPITAL);
    }

    private InputConstants() {}

    private static void alias(String name, int code) {
        NAMES.put(name, code);
        CANONICAL.put(code, name);
    }

    public enum Type {
        KEYSYM,
        SCANCODE,
        MOUSE;

        public Key getOrCreate(int code) {
            return this == MOUSE
                    ? code >= 0 && code < 16 ? new Key(this, code) : UNKNOWN
                    : fromKeyCode(code);
        }
    }

    public record Key(Type type, int value) {
        public Type getType() {
            return type;
        }

        public int getValue() {
            return value;
        }

        public String getName() {
            return value < 0
                    ? "key.keyboard.unknown"
                    : type == Type.MOUSE
                            ? "key.mouse." + (value + 1)
                            : "key.keyboard." + CANONICAL.getOrDefault(value, "unknown");
        }

        public IChatComponent getDisplayName() {
            return new ChatComponentText(
                    value < 0
                            ? "None"
                            : type == Type.MOUSE
                                    ? "Mouse " + (value + 1)
                                    : Keyboard.getKeyName(value));
        }
    }

    public static Key fromKeyCode(int code) {
        return code > 0 && code < 256 ? new Key(Type.KEYSYM, code) : UNKNOWN;
    }

    public static Key getKey(KeyEvent event) {
        return event == null ? UNKNOWN : fromKeyCode(event.key());
    }

    public static Key getKey(String name) {
        if (name == null) return UNKNOWN;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("key.mouse.")) {
            String suffix = normalized.substring(10);
            return switch (suffix) {
                case "left" -> Type.MOUSE.getOrCreate(0);
                case "right" -> Type.MOUSE.getOrCreate(1);
                case "middle" -> Type.MOUSE.getOrCreate(2);
                default -> {
                    try {
                        yield Type.MOUSE.getOrCreate(Integer.parseInt(suffix) - 1);
                    } catch (NumberFormatException e) {
                        yield UNKNOWN;
                    }
                }
            };
        }
        return fromKeyCode(NAMES.getOrDefault(normalized.replace("key.keyboard.", ""), -1));
    }

    public static boolean isKeyDown(Key key) {
        if (key == null || key.value < 0) return false;
        return key.type == Type.MOUSE
                ? Mouse.isCreated()
                        && key.value < Mouse.getButtonCount()
                        && Mouse.isButtonDown(key.value)
                : Keyboard.isCreated() && Keyboard.isKeyDown(key.value);
    }
}
