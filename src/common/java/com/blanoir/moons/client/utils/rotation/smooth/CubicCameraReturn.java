package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/**
 * Cubic decay of a silent offset relative to the live camera.
 * Progress and completion timing are supplied by the owner; no pursuit acceleration here.
 */
public final class CubicCameraReturn {
    private CubicCameraReturn() {}

    public static Rotation returnOffset(
            Rotation camera, float yawOffset, float pitchOffset, double linearProgress) {
        double progress = MathUtils.cubicSmoothStep(linearProgress);
        double remaining = 1.0D - progress;
        return new Rotation(
                camera.yaw() + yawOffset * (float) remaining,
                Mth.clamp(camera.pitch() + pitchOffset * (float) remaining, -90.0F, 90.0F));
    }
}
