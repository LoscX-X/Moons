package com.blanoir.moons.client.module.impl.world.scaffold;

/** One timed press per edge encounter, followed by release even if the edge stays exposed. */
public final class GodBridgeSneak {
    private boolean armed = true;
    private boolean sneaking;
    private int sampledTick = Integer.MIN_VALUE;
    private int releaseTick;

    public boolean update(int tick, boolean edge, int duration) {
        if (tick == sampledTick) return sneaking;
        if (tick < sampledTick) reset();
        sampledTick = tick;
        if (!edge) armed = true;
        if (sneaking) {
            if (tick < releaseTick) return true;
            sneaking = false;
            return false;
        }
        if (edge && armed) {
            armed = false;
            sneaking = true;
            releaseTick = tick + Math.clamp(duration, 1, 2);
        }
        return sneaking;
    }

    public boolean sneaking() {
        return sneaking;
    }

    public void reset() {
        armed = true;
        sneaking = false;
        sampledTick = Integer.MIN_VALUE;
        releaseTick = 0;
    }
}
