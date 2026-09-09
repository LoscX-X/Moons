package com.blanoir.moons.client.management.input;

import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Owns keyboard and mouse press state independently of the host's key mappings. */
public final class KeybindInputListener {
    private final Map<InputConstants.Key, ButtonPressState> states = new LinkedHashMap<>();

    public void poll(
            Set<InputConstants.Key> keys,
            boolean focused,
            Predicate<InputConstants.Key> hardwareDown,
            Predicate<InputConstants.Key> dispatch) {
        if (!focused) {
            states.clear();
            return;
        }
        states.keySet().retainAll(keys);
        for (InputConstants.Key key : keys) {
            if (!ModuleKeybinds.isValid(key)) continue;
            ButtonPressState state = state(key);
            if (state.sample(hardwareDown.test(key)) && dispatch.test(key)) state.consume();
        }
    }

    /** Also records presses in screens, so closing a screen cannot replay a held key. */
    public boolean press(InputConstants.Key key, Predicate<InputConstants.Key> dispatch) {
        if (!ModuleKeybinds.isValid(key)) return false;
        ButtonPressState state = state(key);
        if (state.press() && dispatch.test(key)) state.consume();
        return state.consumed();
    }

    public void release(InputConstants.Key key) {
        ButtonPressState state = states.get(key);
        if (state != null) state.release();
    }

    public void suppress(InputConstants.Key key) {
        if (!ModuleKeybinds.isValid(key)) return;
        ButtonPressState state = state(key);
        state.press();
        state.consume();
    }

    public boolean consumed(InputConstants.Key key) {
        ButtonPressState state = states.get(key);
        return state != null && state.consumed();
    }

    private ButtonPressState state(InputConstants.Key key) {
        return states.computeIfAbsent(key, ignored -> new ButtonPressState());
    }
}
