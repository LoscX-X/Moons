package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.rotation.Rotation;

/**
 * B: Existing Scaffold Instant path: bounded preparation, exact angle at the placement deadline.
 * Before the deadline it retains the original remaining-delay division and 180-degree cap.
 * The mode decides readiness and retains the exact validated hit at the deadline; quantization
 * applies only to preparation steps in the original flow. No clocks or state are owned here.
 */
public final class InstantB {
    private InstantB() {}

    public static Rotation step(Rotation current, Rotation desired, int remainingAirDelay) {
        if (remainingAirDelay == 0) return InstantA.step(desired);
        return SmoothD.limitedStep(current, desired, remainingAirDelay + 1.0F, 180.0D);
    }
}
