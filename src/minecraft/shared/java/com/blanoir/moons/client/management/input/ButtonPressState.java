package com.blanoir.moons.client.management.input;

/** Shares physical press edges between input callbacks and hardware polling. */
public final class ButtonPressState {
    private boolean observed;
    private boolean down;
    private boolean consumed;

    public boolean press() {
        observed = true;
        if (down) return false;
        down = true;
        consumed = false;
        return true;
    }

    public void release() {
        observed = true;
        down = false;
        consumed = false;
    }

    public boolean sample(boolean pressed) {
        // Attaching or regaining focus while held must not create a new press.
        if (!observed) {
            observed = true;
            down = pressed;
            return false;
        }
        if (pressed) return press();
        release();
        return false;
    }

    public void consume() {
        consumed = true;
    }

    public boolean consumed() {
        return consumed;
    }

    public void reset() {
        observed = false;
        down = false;
        consumed = false;
    }
}
