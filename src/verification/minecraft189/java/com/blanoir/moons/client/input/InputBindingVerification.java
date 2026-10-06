package com.blanoir.moons.client.input;

import com.blanoir.moons.client.compat.input.InputConstants;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import net.minecraft.init.Bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exercises real callback/poll edges and binding ownership, without a window or input injection. */
public final class InputBindingVerification {
    public static void main(String[] args) {
        Bootstrap.register();
        verifyOwnership();
        verifyInputEdges();
        verifyFocus();
        verifyModuleDispatch();
        System.out.println(
                "MOONS_INPUT_BINDINGS_VERIFIED callback+poll repeat release focus capture hold toggle ownership rebind");
    }

    private static void verifyOwnership() {
        Map<String, Boolean> enabled = new HashMap<>();
        enabled.put("manual", true);
        List<String> changes = new ArrayList<>();
        HeldBindingState<String> held =
                new HeldBindingState<>(
                        id -> enabled.getOrDefault(id, false),
                        (id, value) -> {
                            enabled.put(id, value);
                            changes.add(id + ":" + value);
                        });
        held.press("keyboard", List.of("owned", "manual"));
        held.press("keyboard", List.of("owned"));
        held.press("mouse", List.of("owned"));
        held.release("keyboard");
        require(enabled.get("owned"), "one remaining held source must keep its activation");
        held.release("mouse");
        held.reset();
        require(enabled.get("manual"), "a pre-existing activation belongs to its original owner");
        require(
                changes.equals(List.of("owned:true", "owned:false")),
                "repeats and cleanup must not duplicate transitions");
        held.press("keyboard", List.of("owned"));
        held.retain((key, id) -> false);
        require(!enabled.get("owned"), "reconfiguration must release acquired activation");
        held.press("keyboard", List.of("owned"));
        held.reset();
        require(!enabled.get("owned"), "focus/context reset must release acquired activation");
    }

    private static void verifyInputEdges() {
        InputConstants.Key keyboard = InputKeys.parse("key.keyboard.r");
        InputConstants.Key mouse = InputKeys.fromMouseButton(InputConstants.MOUSE_BUTTON_LEFT);
        Set<InputConstants.Key> keys = Set.of(keyboard, mouse);
        Map<InputConstants.Key, Boolean> hardware = new HashMap<>();
        List<InputConstants.Key> presses = new ArrayList<>();
        List<InputConstants.Key> releases = new ArrayList<>();
        KeyBindingInputState input = new KeyBindingInputState(releases::add);
        hardware.put(keyboard, true);
        input.poll(
                keys,
                true,
                key -> hardware.getOrDefault(key, false),
                key -> {
                    presses.add(key);
                    return true;
                });
        require(presses.isEmpty(), "attach while already held must seed state without activation");
        require(input.down(keyboard), "held state is still readable after seeding");
        hardware.put(keyboard, false);
        input.poll(keys, true, key -> hardware.getOrDefault(key, false), key -> true);
        require(releases.equals(List.of(keyboard)), "hardware release must end the hold");
        require(
                input.press(
                        keyboard,
                        key -> {
                            presses.add(key);
                            return true;
                        }),
                "callback binding consumes its press");
        hardware.put(keyboard, true);
        input.poll(
                keys,
                true,
                key -> hardware.getOrDefault(key, false),
                key -> {
                    presses.add(key);
                    return true;
                });
        input.press(
                keyboard,
                key -> {
                    presses.add(key);
                    return true;
                });
        require(presses.equals(List.of(keyboard)), "callback, polling and repeat share one edge");
        input.suppress(mouse);
        hardware.put(mouse, true);
        input.poll(
                keys,
                true,
                key -> hardware.getOrDefault(key, false),
                key -> {
                    presses.add(key);
                    return true;
                });
        require(
                presses.size() == 1 && input.consumed(mouse),
                "captured input must not activate later through polling");
        input.release(mouse);
        hardware.put(mouse, false);
        input.poll(
                keys,
                false,
                key -> true,
                key -> {
                    presses.add(key);
                    return true;
                });
        input.poll(
                keys,
                true,
                key -> hardware.getOrDefault(key, false),
                key -> {
                    presses.add(key);
                    return true;
                });
        require(presses.size() == 1, "regaining focus while held must not synthesize a press");
        require(
                !InputKeys.isValid(InputKeys.fromMouseButton(InputConstants.MOUSE_BUTTON_LEFT + 8)),
                "out-of-range button must be rejected");
        require(
                !InputKeys.isValid(InputKeys.parse("invalid")),
                "invalid key name must not become a binding");
    }

    private static void verifyFocus() {
        InputFocusState focus = new InputFocusState();
        require(!focus.update(1, true), "first observation is not focus loss");
        require(focus.update(1, false) && !focus.focused(), "focus loss invalidates input");
        require(!focus.update(1, false), "repeated unfocused polling is stable");
        require(!focus.update(1, true), "focus regain uses seeded hardware state");
        require(focus.update(2, true), "window replacement invalidates old holds");
    }

    private static void verifyModuleDispatch() {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        for (String id : List.of("toggleone", "toggletwo", "heldone")) enabled.put(id, false);
        ModuleRegistry.installCatalog(
                () ->
                        enabled.keySet()
                                .forEach(
                                        id ->
                                                ModuleRegistry.add(
                                                        ModuleRegistry.module(
                                                                id,
                                                                id,
                                                                "test",
                                                                () -> enabled.get(id),
                                                                (client, value) -> {
                                                                    enabled.put(id, value);
                                                                    return 1;
                                                                },
                                                                () -> ""))));
        var key = InputKeys.parse("key.keyboard.r");
        enabled.keySet().forEach(id -> ModuleKeybinds.bind(id, key));
        ModuleKeybinds.setActivationMode("heldone", BindActivationMode.HOLD);
        require(
                ModuleKeybinds.dispatchPress(key, true).consumed(),
                "configured press should route");
        require(
                enabled.values().stream().allMatch(Boolean::booleanValue),
                "mixed group enables toggle and held features");
        ModuleKeybinds.dispatchRelease(key);
        require(
                enabled.get("toggleone") && enabled.get("toggletwo") && !enabled.get("heldone"),
                "release affects held mode only");
        enabled.put("heldone", true);
        ModuleKeybinds.dispatchPress(key, true);
        ModuleKeybinds.dispatchRelease(key);
        require(
                !enabled.get("toggleone") && !enabled.get("toggletwo") && enabled.get("heldone"),
                "toggle group and pre-existing hold stay independent");
        enabled.put("heldone", false);
        ModuleKeybinds.dispatchPress(key, true);
        Settings.setString("keybind.heldone", "key.keyboard.t");
        ModuleKeybinds.reconcileHeldBindings();
        require(!enabled.get("heldone"), "configuration reload releases old held binding");
        require(
                ModuleKeybinds.dispatchPress(ModuleKeybinds.getGuiKey(), true)
                        == ModuleKeybinds.Dispatch.GUI,
                "GUI keeps precedence");
        require(
                ModuleKeybinds.dispatchPress(key, false) == ModuleKeybinds.Dispatch.NONE,
                "screen capture blocks gameplay bindings");
        ModuleKeybinds.releaseHeldBindings();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
