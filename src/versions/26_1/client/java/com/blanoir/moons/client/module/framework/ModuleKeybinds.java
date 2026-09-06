package com.blanoir.moons.client.module.framework;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central owner for Moons keyboard and mouse bindings.
 *
 * <p>Moons bindings are stored only in its own config and are dispatched from
 * raw press callbacks. They never register, rewrite, or save host
 * {@code KeyMapping}s. Changing a host mapping to
 * {@link InputConstants#UNKNOWN} leaves it with GLFW key {@code -1}, which some
 * clients still poll every render frame.</p>
 */
public final class ModuleKeybinds {
    public static final int GUI_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private static final String DEFAULT_GUI_KEY_NAME = "key.keyboard.right.shift";
    private static final String PREFIX = "keybind.";
    private static final String ACTION_PREFIX = "keybind.action.";
    private static final String GUI_KEY_CONFIG = "keybind.gui";
    private static final String DEFAULT_BINDING_MIGRATION =
            "keybind.migration.allModuleDefaultsRemoved";
    private static final String HOST_BINDING_RESTORE_MIGRATION =
            "keybind.migration.displacedHostMappingsRestored";
    private static final Set<String> LEGACY_CLAIMED_KEYS = Set.of(
            "key.keyboard.right.shift", "key.keyboard.g", "key.keyboard.j",
            "key.keyboard.k", "key.keyboard.l", "key.keyboard.p",
            "key.keyboard.m", "key.keyboard.n", "key.keyboard.b",
            "key.keyboard.c", "key.keyboard.o", "key.keyboard.r",
            "key.keyboard.u", "key.keyboard.v", "key.keyboard.x",
            "key.keyboard.z", "key.keyboard.i", "key.keyboard.h");
    private static final Map<String, Runnable> ACTIONS = new LinkedHashMap<>();
    private static boolean initialized;

    private ModuleKeybinds() {
    }

    /** Result of routing one physical press through the binding system. */
    public enum Dispatch {
        NONE,
        GUI,
        BINDING;

        public boolean consumed() {
            return this != NONE;
        }
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        removeObsoleteGeneratedDefaults();
        migrateXrayScanBinding();
        restoreLegacyDisplacedHostMappings();
    }

    private static void migrateXrayScanBinding() {
        String previous = Settings.getString(PREFIX + "xrayscan", "");
        if (previous.isBlank()) return;
        if (Settings.getString(PREFIX + "xray", "").isBlank()) {
            Settings.setString(PREFIX + "xray", previous);
        }
        Settings.remove(PREFIX + "xrayscan");
    }

    /**
     * Old builds silently assigned a large set of letter keys. Remove only
     * those exact generated values once; user-selected bindings are preserved.
     */
    private static void removeObsoleteGeneratedDefaults() {
        if (Settings.getBoolean(DEFAULT_BINDING_MIGRATION, false)) {
            return;
        }
        Map.ofEntries(
                Map.entry("xrayscan", "key.keyboard.g"),
                Map.entry("xraydisplay", "key.keyboard.j"),
                Map.entry("triggerbot", "key.keyboard.k"),
                Map.entry("aimassist", "key.keyboard.l"),
                Map.entry("critical", "key.keyboard.p"),
                Map.entry("nametags", "key.keyboard.m"),
                Map.entry("jumpreset", "key.keyboard.n"),
                Map.entry("clip", "key.keyboard.b"),
                Map.entry("caver", "key.keyboard.c"),
                Map.entry("backtrack", "key.keyboard.o"),
                Map.entry("sprint", "key.keyboard.r"),
                Map.entry("autototem", "key.keyboard.u"),
                Map.entry("autotool", "key.keyboard.v"),
                Map.entry("autosword", "key.keyboard.x"),
                Map.entry("autoweb", "key.keyboard.z"),
                Map.entry("autolava", "key.keyboard.i")
        ).forEach(ModuleKeybinds::removeIfExact);
        if ("key.keyboard.h".equals(Settings.getString(ACTION_PREFIX + "clearscan", ""))) {
            Settings.remove(ACTION_PREFIX + "clearscan");
        }
        Settings.setBoolean(DEFAULT_BINDING_MIGRATION, true);
    }

    private static void removeIfExact(String moduleId, String generatedValue) {
        if (generatedValue.equals(Settings.getString(PREFIX + moduleId, ""))) {
            Settings.remove(PREFIX + moduleId);
        }
    }

    /**
     * Early self-owned-key builds persisted every conflicting host mapping as
     * UNKNOWN. Restore only an unbound mapping whose own default was one of the
     * exact keys claimed by that build, then rebuild Minecraft's key index.
     */
    private static void restoreLegacyDisplacedHostMappings() {
        if (Settings.getBoolean(HOST_BINDING_RESTORE_MIGRATION, false)) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;

        List<KeyMapping> unknown = GameAccess.keyMappings().get(InputConstants.UNKNOWN);
        boolean changed = false;
        if (unknown != null) {
            for (KeyMapping mapping : new ArrayList<>(unknown)) {
                if (mapping == null || !mapping.isUnbound()) continue;
                InputConstants.Key defaultKey = mapping.getDefaultKey();
                if (defaultKey == null || defaultKey == InputConstants.UNKNOWN
                        || !LEGACY_CLAIMED_KEYS.contains(defaultKey.getName())) continue;
                mapping.setKey(defaultKey);
                changed = true;
            }
        }
        if (changed) {
            KeyMapping.resetMapping();
            client.options.save();
        }
        Settings.setBoolean(HOST_BINDING_RESTORE_MIGRATION, true);
    }

    public static void registerAction(String actionId, Runnable handler) {
        if (actionId == null || actionId.isBlank() || handler == null) {
            return;
        }
        ACTIONS.put(actionId, handler);
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
        if (button < GLFW.GLFW_MOUSE_BUTTON_1 || button > GLFW.GLFW_MOUSE_BUTTON_LAST) {
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

    public static boolean isBound(String moduleId) {
        return isValid(getBoundKey(moduleId));
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
