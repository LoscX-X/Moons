package com.blanoir.moons.client.event.input;

import com.blanoir.moons.client.event.Cancellable;

/** Raw mouse-scroll callback, before vanilla changes the selected slot or UI. */
public final class MouseScrollEvent implements Cancellable {
    private final Object handler;
    private final long window;
    private final double horizontal;
    private final double vertical;
    private boolean cancelled;

    public MouseScrollEvent(Object handler, long window, double horizontal, double vertical) {
        this.handler = handler;
        this.window = window;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    public Object handler() {
        return handler;
    }

    public long window() {
        return window;
    }

    public double horizontal() {
        return horizontal;
    }

    public double vertical() {
        return vertical;
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
