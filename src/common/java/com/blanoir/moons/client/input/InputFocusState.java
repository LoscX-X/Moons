package com.blanoir.moons.client.input;

/** Window identity and focus determine when tracked holds must be discarded. */
public final class InputFocusState {
    private boolean observed;
    private long window;
    private boolean focused;

    public boolean update(long window, boolean focused) {
        boolean reset = observed && (this.window != window || this.focused && !focused);
        observed = true;
        this.window = window;
        this.focused = focused;
        return reset;
    }

    public boolean focused() {
        return focused;
    }

    public void reset() {
        observed = false;
        focused = false;
    }
}
