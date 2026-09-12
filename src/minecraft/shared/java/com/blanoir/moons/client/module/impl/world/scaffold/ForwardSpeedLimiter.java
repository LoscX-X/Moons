package com.blanoir.moons.client.module.impl.world.scaffold;

import net.minecraft.world.entity.player.Input;

/** Releases only requested forward input above the range, and presses again below it. */
public final class ForwardSpeedLimiter {
    private boolean pressing = true;

    public Input filter(Input requested, double forwardBps, double minimum, double maximum) {
        if (!requested.forward() || requested.backward()) {
            reset();
            return requested;
        }
        if (forwardBps >= maximum) pressing = false;
        else if (forwardBps <= minimum) pressing = true;
        if (pressing) return requested;
        return new Input(
                false,
                false,
                requested.left(),
                requested.right(),
                requested.jump(),
                requested.shift(),
                false);
    }

    public void reset() {
        pressing = true;
    }
}
