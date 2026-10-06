package com.blanoir.moons.client.module.framework;

import static com.blanoir.moons.client.input.InputKeys.isValid;
import static com.blanoir.moons.client.input.InputKeys.parse;
import static com.blanoir.moons.client.input.InputKeys.validOrUnknown;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.input.BindActivationMode;
import com.blanoir.moons.client.input.HeldBindingState;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.blanoir.moons.client.module.impl.misc.FreeLook;
import com.mojang.blaze3d.platform.InputConstants;

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
    private static final String MODE_PREFIX = "keybind.mode.";
    private static final HeldBindingState<InputConstants.Key> HELD =
            new HeldBindingState<>(ModuleKeybinds::enabledById, ModuleRegistry::setEnabled);
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
        return parse(
                Settings.getString(
                        PREFIX + moduleId,
                        "freelook".equals(moduleId) ? "key.keyboard.left.alt" : ""));
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
        HELD.releaseTarget(moduleId);
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
        HELD.releaseTarget(moduleId);
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
            List<Module> toggled =
                    modules.stream()
                            .filter(
                                    module ->
                                            activationMode(module.id())
                                                    == BindActivationMode.TOGGLE)
                            .toList();
            boolean enableGroup = toggled.stream().noneMatch(ModuleKeybinds::enabled);
            for (Module module : toggled) {
                if (enabled(module) != enableGroup) {
                    ModuleRegistry.setEnabled(module.id(), enableGroup);
                }
            }
            HELD.press(
                    key,
                    modules.stream()
                            .filter(
                                    module ->
                                            activationMode(module.id()) == BindActivationMode.HOLD)
                            .map(Module::id)
                            .toList());
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

    public static BindActivationMode activationMode(String moduleId) {
        if ("freelook".equals(moduleId))
            return FreeLook.isHoldMode() ? BindActivationMode.HOLD : BindActivationMode.TOGGLE;
        return "hold".equals(Settings.getString(MODE_PREFIX + moduleId, "toggle"))
                ? BindActivationMode.HOLD
                : BindActivationMode.TOGGLE;
    }

    public static void setActivationMode(String moduleId, BindActivationMode mode) {
        HELD.releaseTarget(moduleId);
        if ("freelook".equals(moduleId))
            FreeLook.setHold(
                    net.minecraft.client.Minecraft.getInstance(), mode == BindActivationMode.HOLD);
        else
            Settings.setString(
                    MODE_PREFIX + moduleId, mode == BindActivationMode.HOLD ? "hold" : "toggle");
    }

    public static void dispatchRelease(InputConstants.Key key) {
        HELD.release(key);
    }

    public static void releaseHeldBindings() {
        HELD.reset();
    }

    public static void reconcileHeldBindings() {
        HELD.retain(
                (key, id) ->
                        activationMode(id) == BindActivationMode.HOLD
                                && key.equals(getBoundKey(id)));
    }

    private static boolean enabledById(String id) {
        return ModuleRegistry.modules().stream()
                .filter(module -> module.id().equals(id))
                .anyMatch(ModuleKeybinds::enabled);
    }

    private static boolean enabled(Module module) {
        try {
            return module.enabled().getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
