package com.blanoir.moons.client.input;

import com.blanoir.moons.client.compat.input.InputConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns keyboard and mouse press state independently of the host's key mappings. */
public final class KeyBindingInputState {
    private final Map<InputConstants.Key, ButtonPressState> states = new LinkedHashMap<>();

    private final Consumer<InputConstants.Key> release;

    public KeyBindingInputState(Consumer<InputConstants.Key> release) {
        this.release = release;
    }

    public void reset() {
        var keys = new ArrayList<>(states.keySet());
        states.clear();
        keys.forEach(release);
    }

    public boolean down(InputConstants.Key key) {
        ButtonPressState state = states.get(key);
        return state != null && state.down();
    }

    public void poll(
            Set<InputConstants.Key> keys,
            boolean focused,
            Predicate<InputConstants.Key> hardwareDown,
            Predicate<InputConstants.Key> dispatch) {
        if (!focused) {
            reset();
            return;
        }
        for (InputConstants.Key key : new ArrayList<>(states.keySet())) {
            if (!keys.contains(key)) {
                release(key);
                states.remove(key);
            }
        }
        for (InputConstants.Key key : keys) {
            if (!InputKeys.isValid(key)) continue;
            ButtonPressState state = state(key);
            boolean wasDown = state.down();
            boolean pressed = hardwareDown.test(key);
            if (state.sample(pressed) && dispatch.test(key)) state.consume();
            if (wasDown && !pressed) release.accept(key);
        }
    }

    /** Also records presses in screens, so closing a screen cannot replay a held key. */
    public boolean press(InputConstants.Key key, Predicate<InputConstants.Key> dispatch) {
        if (!InputKeys.isValid(key)) return false;
        ButtonPressState state = state(key);
        if (state.press() && dispatch.test(key)) state.consume();
        return state.consumed();
    }

    public void release(InputConstants.Key key) {
        ButtonPressState state = states.get(key);
        if (state != null) state.release();
        release.accept(key);
    }

    public void suppress(InputConstants.Key key) {
        if (!InputKeys.isValid(key)) return;
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
