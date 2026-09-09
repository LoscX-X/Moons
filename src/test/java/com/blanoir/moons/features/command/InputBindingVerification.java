package com.blanoir.moons.features.command;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.management.input.ButtonPressState;
import com.blanoir.moons.client.management.input.KeybindInputListener;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Exercises persisted keyboard, mouse and GUI bindings with independent hardware samples. */
public final class InputBindingVerification {
    private InputBindingVerification() {}

    public static void main(String[] arguments) throws Exception {
        Settings.configure(Files.createTempDirectory("moons-mouse-bindings-"));
        AtomicBoolean enabled = new AtomicBoolean();
        AtomicInteger toggles = new AtomicInteger();
        ModuleRegistry.installCatalog(
                () ->
                        ModuleRegistry.add(
                                new ModuleRegistry.Module(
                                        "mouse_verify",
                                        "Mouse verification",
                                        "misc",
                                        enabled::get,
                                        (client, value) -> {
                                            enabled.set(value);
                                            return toggles.incrementAndGet();
                                        },
                                        () -> "",
                                        List.of())));
        InputConstants.Key mouse5 = ModuleKeybinds.fromMouseButton(InputConstants.MOUSE_BUTTON_5);
        require(
                mouse5.equals(BindCommand.parseKey("MOUSE5")),
                "command and callback agree on Mouse 5");
        require(ModuleKeybinds.bind("mouse_verify", mouse5), "Mouse 5 can be bound");
        require(
                mouse5.equals(ModuleKeybinds.getBoundKey("mouse_verify")),
                "saved binding round trip");
        require(
                ModuleKeybinds.fromMouseButton(InputConstants.MOUSE_BUTTON_8 + 1)
                        == InputConstants.UNKNOWN,
                "invalid raw button rejected");

        ButtonPressState state = new ButtonPressState();
        require(!state.sample(false), "initial released sample is not a press");
        poll(state, mouse5, true, true);
        require(enabled.get() && toggles.get() == 1, "missing callback is recovered by polling");
        poll(state, mouse5, true, true);
        callback(state, mouse5, true);
        require(
                toggles.get() == 1 && state.consumed(),
                "held and late callbacks do not toggle twice");
        state.release();
        callback(state, mouse5, true);
        poll(state, mouse5, true, true);
        require(
                !enabled.get() && toggles.get() == 2,
                "callback before polling toggles exactly once");

        poll(state, mouse5, false, true);
        poll(state, mouse5, true, true);
        require(
                enabled.get() && toggles.get() == 3,
                "missing release callback does not leave a stuck key");

        state.release();
        callback(state, mouse5, false);
        poll(state, mouse5, true, true);
        require(
                toggles.get() == 3,
                "closing a screen while holding its click does not toggle a module");
        state.release();
        poll(state, mouse5, true, false);
        poll(state, mouse5, true, true);
        require(toggles.get() == 3, "polling in a screen also absorbs the press");
        poll(state, mouse5, false, true);
        poll(state, mouse5, true, true);
        require(toggles.get() == 4, "new gameplay press after closing the screen works");

        state.reset();
        poll(state, mouse5, true, true);
        require(
                toggles.get() == 4 && !state.consumed(),
                "refocusing with a held button does not trigger");
        poll(state, mouse5, false, true);
        callback(state, mouse5, true);
        require(toggles.get() == 5, "press after refocus and release works");

        state.release();
        require(state.press(), "cancelled callback records its press");
        state.consume();
        poll(state, mouse5, true, true);
        require(toggles.get() == 5, "polling does not revive a cancelled callback");

        ButtonPressState unbound = new ButtonPressState();
        InputConstants.Key mouse4 = ModuleKeybinds.fromMouseButton(InputConstants.MOUSE_BUTTON_4);
        unbound.sample(false);
        poll(unbound, mouse4, true, true);
        callback(unbound, mouse4, true);
        require(
                !unbound.consumed() && toggles.get() == 5,
                "unbound side button stays available to the host");

        AtomicInteger actions = new AtomicInteger();
        ModuleKeybinds.registerAction("mouse_verify", actions::incrementAndGet);
        Settings.setString("keybind.action.mouse_verify", mouse4.getName());
        unbound.release();
        poll(unbound, mouse4, true, true);
        callback(unbound, mouse4, true);
        require(
                actions.get() == 1 && unbound.consumed(),
                "mouse action bindings also recover exactly once");

        require(ModuleKeybinds.bindGuiKey(mouse5), "Mouse 5 can open the GUI");
        require(
                ModuleKeybinds.dispatchPress(mouse5, false) == ModuleKeybinds.Dispatch.GUI,
                "GUI binding retains precedence while a screen is open");
        require(
                ModuleKeybinds.getBoundKey("mouse_verify") == InputConstants.UNKNOWN,
                "GUI binding removes conflicting module binding");
        verifyIndependentListener();
        System.out.println("INPUT_BINDINGS_VERIFIED");
    }

    private static void verifyIndependentListener() {
        ModuleKeybinds.resetGuiKey();
        InputConstants.Key letter = BindCommand.parseKey("r");
        InputConstants.Key function = BindCommand.parseKey("f6");
        InputConstants.Key modifier = BindCommand.parseKey("rctrl");
        InputConstants.Key mouse8 = BindCommand.parseKey("mouse8");
        require(
                mouse8.equals(ModuleKeybinds.fromMouseButton(InputConstants.MOUSE_BUTTON_8)),
                "last mouse button uses this version's numbering");
        require(ModuleKeybinds.bind("mouse_verify", letter), "keyboard module binding");
        Set<InputConstants.Key> snapshot = ModuleKeybinds.boundKeys();
        require(snapshot == ModuleKeybinds.boundKeys(), "unchanged bindings reuse the polling set");
        require(snapshot.contains(letter), "keyboard module is polled");

        KeybindInputListener listener = new KeybindInputListener();
        Set<InputConstants.Key> held = new HashSet<>();
        AtomicInteger bindings = new AtomicInteger();
        AtomicInteger gui = new AtomicInteger();
        AtomicBoolean permitted = new AtomicBoolean(true);
        Predicate<InputConstants.Key> dispatch =
                key -> {
                    if (!permitted.get()) return false;
                    ModuleKeybinds.Dispatch result = ModuleKeybinds.dispatchPress(key, true);
                    if (result == ModuleKeybinds.Dispatch.BINDING) bindings.incrementAndGet();
                    if (result == ModuleKeybinds.Dispatch.GUI) gui.incrementAndGet();
                    return result.consumed();
                };
        Runnable frame =
                () -> listener.poll(ModuleKeybinds.boundKeys(), true, held::contains, dispatch);
        frame.run();
        held.add(letter);
        frame.run();
        require(bindings.get() == 1, "keyboard triggers without any host callback");
        require(listener.press(letter, dispatch), "late keyboard callback remains consumed");
        frame.run();
        require(
                bindings.get() == 1 && listener.consumed(letter),
                "keyboard hold and repeat stay singular");
        held.clear();
        frame.run();
        listener.press(letter, dispatch);
        listener.release(letter);
        frame.run();
        require(bindings.get() == 2, "short callback-only tap between frames is retained");

        InputConstants.Key guiKey = ModuleKeybinds.getGuiKey();
        held.add(guiKey);
        frame.run();
        require(gui.get() == 1, "GUI uses the same independent listener");
        listener.press(guiKey, dispatch);
        frame.run();
        require(gui.get() == 1, "late GUI callback cannot immediately close the GUI");
        held.clear();
        frame.run();
        held.add(guiKey);
        frame.run();
        require(gui.get() == 2, "a fresh GUI press works without release callbacks");

        held.clear();
        frame.run();
        permitted.set(false);
        held.add(letter);
        frame.run();
        permitted.set(true);
        frame.run();
        require(bindings.get() == 2, "typing in another screen cannot replay after it closes");
        listener.poll(
                ModuleKeybinds.boundKeys(),
                false,
                key -> {
                    throw new AssertionError("unfocused listener read hardware");
                },
                dispatch);
        frame.run();
        require(bindings.get() == 2, "held key is suppressed after regaining focus");
        held.clear();
        frame.run();
        held.add(letter);
        frame.run();
        require(bindings.get() == 3, "next fresh press after focus recovery works");

        held.add(function);
        require(ModuleKeybinds.bind("mouse_verify", function), "rebind to a function key");
        require(
                !ModuleKeybinds.boundKeys().contains(letter),
                "old keyboard binding leaves the polling set");
        require(
                ModuleKeybinds.boundKeys().contains(function),
                "new function key immediately joins polling");
        frame.run();
        require(bindings.get() == 3, "rebinding an already held key does not toggle");
        held.clear();
        frame.run();
        held.add(function);
        frame.run();
        require(bindings.get() == 4, "function key triggers independently");
        require(
                !listener.press(letter, dispatch),
                "unbound keyboard input passes through to the host");

        AtomicInteger action = new AtomicInteger();
        Settings.setString("keybind.action.late_input_verify", modifier.getName());
        require(
                !ModuleKeybinds.boundKeys().contains(modifier),
                "unregistered action has no listener");
        ModuleKeybinds.registerAction("late_input_verify", action::incrementAndGet);
        require(
                ModuleKeybinds.boundKeys().contains(modifier),
                "new action invalidates the polling cache");
        frame.run();
        held.add(modifier);
        frame.run();
        listener.press(modifier, dispatch);
        require(action.get() == 1, "modifier action triggers once without a host callback");

        held.clear();
        frame.run();
        listener.suppress(function);
        held.add(function);
        frame.run();
        require(bindings.get() == 5, "cancelled keyboard press is not revived by polling");

        held.add(mouse8);
        ModuleKeybinds.bindGuiKey(mouse8);
        frame.run();
        require(gui.get() == 2, "rebinding the GUI while held does not activate it");
        held.clear();
        frame.run();
        held.add(mouse8);
        frame.run();
        listener.press(mouse8, dispatch);
        require(
                gui.get() == 3,
                "mouse GUI binding uses the same independent listener exactly once");
    }

    private static void poll(
            ButtonPressState state, InputConstants.Key key, boolean down, boolean gameplay) {
        if (state.sample(down) && ModuleKeybinds.dispatchPress(key, gameplay).consumed()) {
            state.consume();
        }
    }

    private static void callback(ButtonPressState state, InputConstants.Key key, boolean gameplay) {
        if (state.press() && ModuleKeybinds.dispatchPress(key, gameplay).consumed()) {
            state.consume();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
