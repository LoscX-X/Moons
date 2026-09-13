package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/**
 * D: Existing bounded Scaffold placement and return steps.
 * The divided and undivided paths retain distinct arithmetic; no time or packet ownership.
 */
public final class SmoothD {
    private SmoothD() {}

    public static Rotation limitedStep(Rotation from, Rotation to, float steps, double degrees) {
        float yawDelta = Mth.wrapDegrees(to.yaw() - from.yaw()) / steps;
        float pitchDelta = (to.pitch() - from.pitch()) / steps;
        return new Rotation(
                from.yaw() + (float) Mth.clamp(yawDelta, -degrees, degrees),
                from.pitch() + (float) Mth.clamp(pitchDelta, -degrees, degrees));
    }

    public static Rotation boundedTurn(Rotation from, Rotation to, double degrees) {
        return new Rotation(
                from.yaw()
                        + (float)
                                Mth.clamp(
                                        Mth.wrapDegrees(to.yaw() - from.yaw()), -degrees, degrees),
                from.pitch() + (float) Mth.clamp(to.pitch() - from.pitch(), -degrees, degrees));
    }
}
