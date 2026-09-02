package com.blanoir.moons.client.event.input;

import com.blanoir.moons.client.event.Cancellable;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

/** Raw keyboard callback, dispatched synchronously on the client thread. */
public final class KeyInputEvent implements Cancellable {
    private final KeyboardHandler handler;
    private final long window;
    private final int action;
    private final KeyEvent key;
    private boolean cancelled;

    public KeyInputEvent(KeyboardHandler handler, long window, int action, KeyEvent key) {
        this.handler = handler;
        this.window = window;
        this.action = action;
        this.key = key;
    }

    public KeyboardHandler handler() { return handler; }
    public long window() { return window; }
    public int action() { return action; }
    public KeyEvent key() { return key; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void cancel() { cancelled = true; }
}
