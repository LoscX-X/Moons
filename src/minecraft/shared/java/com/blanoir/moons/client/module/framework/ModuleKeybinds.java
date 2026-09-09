package com.blanoir.moons.client.module.framework;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.input.KeyEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central owner for Moons keyboard and mouse bindings.
 *
 * <p>Moons bindings are stored only in its own config. An independent input listener polls
 * keyboard and mouse state and merges raw callbacks into the same press edges.
 * Bindings never register, rewrite, or save host
 * {@code KeyMapping}s. Changing a host mapping to
 * {@link InputConstants#UNKNOWN} leaves it with the platform's unknown key code, which some
 * clients still poll every render frame.</p>
 */
public final class ModuleKeybinds {

    private static final String DEFAULT_GUI_KEY_NAME = "key.keyboard.right.shift";
    private static final String PREFIX = "keybind.";
    private static final String ACTION_PREFIX = "keybind.action.";
    private static final String GUI_KEY_CONFIG = "keybind.gui";
    private static final Map<String, Runnable> ACTIONS = new LinkedHashMap<>();
    private static long boundKeysRevision = Long.MIN_VALUE;
    private static Set<InputConstants.Key> boundKeysSnapshot = Set.of();

    private ModuleKeybinds() {}

    /** Result of routing one physical press through the binding system. */
    public enum Dispatch {
        NONE,
        GUI,
        BINDING;

        public boolean consumed() {
            return this != NONE;
        }
    }

    public static void registerAction(String actionId, Runnable handler) {
        if (actionId == null || actionId.isBlank() || handler == null) {
            return;
        }
        ACTIONS.put(actionId, handler);
        boundKeysRevision = Long.MIN_VALUE;
    }

    /** All configured inputs, with the GUI key first and shared keys visited only once. */
    public static Set<InputConstants.Key> boundKeys() {
        long revision = Settings.revision();
        if (revision == boundKeysRevision) return boundKeysSnapshot;
        Set<InputConstants.Key> keys = new LinkedHashSet<>();
        keys.add(getGuiKey());
        for (Module module : ModuleRegistry.modules()) {
            InputConstants.Key key = getBoundKey(module.id());
            if (isValid(key)) keys.add(key);
        }
        for (String actionId : ACTIONS.keySet()) {
            InputConstants.Key key = parse(Settings.getString(ACTION_PREFIX + actionId, ""));
            if (isValid(key)) keys.add(key);
        }
        boundKeysSnapshot = Collections.unmodifiableSet(keys);
        boundKeysRevision = revision;
        return boundKeysSnapshot;
    }

    /** Converts a keyboard callback to a validated binding key. */
    public static InputConstants.Key fromEvent(KeyEvent event) {
        if (event == null) {
            return InputConstants.UNKNOWN;
        }
        try {
            return validOrUnknown(InputConstants.getKey(event));
        } catch (RuntimeException ignored) {
            return InputConstants.UNKNOWN;
        }
    }

    /** Converts a raw mouse button to a validated binding key. */
    public static InputConstants.Key fromMouseButton(int button) {
        if (button < InputConstants.MOUSE_BUTTON_LEFT || button > InputConstants.MOUSE_BUTTON_8) {
            return InputConstants.UNKNOWN;
        }
        return validOrUnknown(InputConstants.Type.MOUSE.getOrCreate(button));
    }

    public static boolean isValid(InputConstants.Key key) {
        return key != null && key != InputConstants.UNKNOWN && key.getValue() >= 0;
    }

    private static InputConstants.Key parse(String name) {
        if (name == null || name.isBlank()) {
            return InputConstants.UNKNOWN;
        }
        try {
            return validOrUnknown(InputConstants.getKey(name));
        } catch (RuntimeException ignored) {
            return InputConstants.UNKNOWN;
        }
    }

    private static InputConstants.Key validOrUnknown(InputConstants.Key key) {
        return isValid(key) ? key : InputConstants.UNKNOWN;
    }

    /** The configured ClickGUI key, falling back safely to Right Shift. */
    public static InputConstants.Key getGuiKey() {
        InputConstants.Key configured = parse(Settings.getString(GUI_KEY_CONFIG, ""));
        return isValid(configured) ? configured : parse(DEFAULT_GUI_KEY_NAME);
    }

    public static boolean isGuiKey(InputConstants.Key key) {
        return isValid(key) && key.equals(getGuiKey());
    }

    public static boolean bindGuiKey(InputConstants.Key key) {
        key = validOrUnknown(key);
        if (!isValid(key)) {
            return false;
        }
        for (Module module : modulesFor(key)) {
            unbind(module.id());
        }
        Settings.setString(GUI_KEY_CONFIG, key.getName());
        return true;
    }

    public static void resetGuiKey() {
        Settings.remove(GUI_KEY_CONFIG);
    }

    public static InputConstants.Key getBoundKey(String moduleId) {
        if ("clickgui".equals(moduleId)) {
            return getGuiKey();
        }
        if (moduleId == null || moduleId.isBlank()) {
            return InputConstants.UNKNOWN;
        }
        return parse(Settings.getString(PREFIX + moduleId, ""));
    }

    /** Modules bound to this key. ClickGUI is routed before module bindings. */
    public static List<Module> modulesFor(InputConstants.Key key) {
        key = validOrUnknown(key);
        if (!isValid(key)) {
            return List.of();
        }
        InputConstants.Key requested = key;
        return ModuleRegistry.modules().stream()
                .filter(module -> !"clickgui".equals(module.id()))
                .filter(module -> getBoundKey(module.id()).equals(requested))
                .toList();
    }

    /** Binds a module without changing any host key mapping. */
    public static boolean bind(String moduleId, InputConstants.Key key) {
        if (moduleId == null || moduleId.isBlank()) {
            return false;
        }
        if ("clickgui".equals(moduleId)) {
            return bindGuiKey(key);
        }
        key = validOrUnknown(key);
        if (!isValid(key) || isGuiKey(key)) {
            return false;
        }
        Settings.setString(PREFIX + moduleId, key.getName());
        return true;
    }

    public static void unbind(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        if ("clickgui".equals(moduleId)) {
            resetGuiKey();
            return;
        }
        Settings.setString(PREFIX + moduleId, "");
    }

    /**
     * Routes one press with GUI precedence. Gameplay bindings are ignored while
     * another screen is open, but the GUI key may still close ClickGUI.
     */
    public static Dispatch dispatchPress(InputConstants.Key key, boolean allowGameplayBindings) {
        key = validOrUnknown(key);
        if (!isValid(key)) {
            return Dispatch.NONE;
        }
        if (isGuiKey(key)) {
            return Dispatch.GUI;
        }
        if (!allowGameplayBindings) {
            return Dispatch.NONE;
        }

        boolean consumed = false;
        List<Module> modules = modulesFor(key);
        if (!modules.isEmpty()) {
            boolean enableGroup = modules.stream().noneMatch(ModuleKeybinds::enabled);
            for (Module module : modules) {
                if (enabled(module) != enableGroup) {
                    ModuleRegistry.setEnabled(module.id(), enableGroup);
                }
            }
            consumed = true;
        }

        for (Map.Entry<String, Runnable> entry : ACTIONS.entrySet()) {
            if (parse(Settings.getString(ACTION_PREFIX + entry.getKey(), "")).equals(key)) {
                entry.getValue().run();
                consumed = true;
            }
        }
        return consumed ? Dispatch.BINDING : Dispatch.NONE;
    }

    private static boolean enabled(Module module) {
        try {
            return module.enabled().getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
