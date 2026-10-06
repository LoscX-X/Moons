package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.utils.rotation.Rotation;

/** E: Exponential frame interpolation of a presented angle. Tick interpolation is TickAngleInterpolation; body integration is BodyYawIntegration. */
public final class RenderAngleSmoothing {
    private RenderAngleSmoothing() {}

    public static Rotation render(
            Rotation current, Rotation target, double rawDelta, double response) {
        double delta = Mth.clamp(rawDelta, 0.0D, 0.05D);
        float blend = (float) (1.0D - Math.exp(-response * delta));
        float yaw = current.yaw();
        float pitch = current.pitch();
        yaw += Mth.wrapDegrees(target.yaw() - yaw) * blend;
        pitch += (target.pitch() - pitch) * blend;
        return new Rotation(yaw, pitch);
    }
}
