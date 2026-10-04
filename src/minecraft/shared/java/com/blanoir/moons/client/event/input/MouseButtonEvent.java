package com.blanoir.moons.client.event.input;

import com.blanoir.moons.client.compat.input.MouseButtonInfo;
import com.blanoir.moons.client.event.Cancellable;

/** Raw mouse-button callback, before vanilla handles the button state. */
public final class MouseButtonEvent implements Cancellable {
    private final Object handler;
    private final long window;
    private final MouseButtonInfo button;
    private final int action;
    private boolean cancelled;

    public MouseButtonEvent(Object handler, long window, MouseButtonInfo button, int action) {
        this.handler = handler;
        this.window = window;
        this.button = button;
        this.action = action;
    }

    public Object handler() {
        return handler;
    }

    public long window() {
        return window;
    }

    public MouseButtonInfo button() {
        return button;
    }

    public int action() {
        return action;
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
