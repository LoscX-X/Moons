package com.blanoir.moons.client.event.input;

/** Boundaries around one accumulated mouse-motion update. */
public final class MouseMotionEvent {
    private MouseMotionEvent() {}

    public record Pre(Object handler) {}

    public record Post(Object handler) {}
}
