package com.blanoir.moons.client.module.impl.world.scaffold;

/** Rotation may be delayed within the air window, but never delays its placement deadline. */
public final class TellyTiming {
    private TellyTiming() {}

    public static int rotationStartTick(boolean earlyRotation, int rotationDelay, int airDelay) {
        int deadline = Math.max(0, airDelay);
        return earlyRotation ? Math.clamp(rotationDelay, 0, deadline) : deadline;
    }
}
