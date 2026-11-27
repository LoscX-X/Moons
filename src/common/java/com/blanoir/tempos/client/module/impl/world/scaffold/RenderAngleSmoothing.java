package com.blanoir.tempos.client.module.impl.world.scaffold;

import com.blanoir.tempos.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/** Exponential frame interpolation of a presented angle. Tick interpolation is TickAngleInterpolation; body integration is BodyYawIntegration. */
public final class RenderAngleSmoothing {
    private RenderAngleSmoothing() {}

    public static Rotation render(
            Rotation current, Rotation target, double rawDelta, double response) {
        double delta = Math.clamp(rawDelta, 0.0D, 0.05D);
        float blend = (float) (1.0D - Math.exp(-response * delta));
        float yaw = current.yaw();
        float pitch = current.pitch();
        yaw += Mth.wrapDegrees(target.yaw() - yaw) * blend;
        pitch += (target.pitch() - pitch) * blend;
        return new Rotation(yaw, pitch);
    }
}
