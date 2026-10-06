package com.blanoir.moons.client.event.input;

import net.minecraft.client.MouseHandler;

/** Boundaries around one accumulated mouse-motion update. */
public final class MouseMotionEvent {
    private MouseMotionEvent() {}

    public record Pre(MouseHandler handler) {}

    public record Post(MouseHandler handler) {}
}
