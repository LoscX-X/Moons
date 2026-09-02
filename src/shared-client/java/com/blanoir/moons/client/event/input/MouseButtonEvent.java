package com.blanoir.moons.client.event.input;

import com.blanoir.moons.client.event.Cancellable;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

/** Raw mouse-button callback, before vanilla handles the button state. */
public final class MouseButtonEvent implements Cancellable {
    private final MouseHandler handler;
    private final long window;
    private final MouseButtonInfo button;
    private final int action;
    private boolean cancelled;

    public MouseButtonEvent(
            MouseHandler handler,
            long window,
            MouseButtonInfo button,
            int action
    ) {
        this.handler = handler;
        this.window = window;
        this.button = button;
        this.action = action;
    }

    public MouseHandler handler() { return handler; }
    public long window() { return window; }
    public MouseButtonInfo button() { return button; }
    public int action() { return action; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void cancel() { cancelled = true; }
}
