package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.utils.rotation.Rotation;

/**
 * H: Tick-target linear presentation interpolation with wrapped yaw.
 * The caller owns previous/target samples and bounded progress.
 */
public final class TickAngleInterpolation {
    private TickAngleInterpolation() {}

    public static Rotation interpolate(Rotation previous, Rotation target, float progress) {
        return new Rotation(
                previous.yaw() + Mth.wrapDegrees(target.yaw() - previous.yaw()) * progress,
                Mth.lerp(progress, previous.pitch(), target.pitch()));
    }
}
