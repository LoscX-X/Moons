package com.blanoir.moons.client.event.input;

import com.blanoir.moons.client.compat.input.KeyEvent;
import com.blanoir.moons.client.event.Cancellable;

/** Raw keyboard callback, dispatched synchronously on the client thread. */
public final class KeyInputEvent implements Cancellable {
    private final Object handler;
    private final long window;
    private final int action;
    private final KeyEvent key;
    private boolean cancelled;

    public KeyInputEvent(Object handler, long window, int action, KeyEvent key) {
        this.handler = handler;
        this.window = window;
        this.action = action;
        this.key = key;
    }

    public Object handler() {
        return handler;
    }

    public long window() {
        return window;
    }

    public int action() {
        return action;
    }

    public KeyEvent key() {
        return key;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }
}
