package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/** F: Existing packet-domain acceleration stepping only. Sampling, random envelopes,
 * tick caching, final quantization and sent-angle history stay with the owner. */
public final class SmoothF {
    private static final float YAW_DEADZONE = 0.06F;
    private static final float PITCH_DEADZONE = 0.045F;
    private static final float MAX_YAW_STEP = 48.0F;
    private static final float OVERLAP_MAX_YAW_STEP = 18.0F;
    private static final float MAX_PITCH_STEP = 32.0F;

    private SmoothF() {}

    public static Rotation step(
            Rotation base,
            float yawDemand,
            float pitchDemand,
            float previousYawStep,
            float previousPitchStep,
            double yawAcceleration,
            double pitchAcceleration,
            boolean limitOverlap) {
        float yawStep =
                SmoothK.acceleratedAxisStep(
                        yawDemand,
                        previousYawStep,
                        yawAcceleration,
                        0.0D,
                        YAW_DEADZONE,
                        MAX_YAW_STEP);
        // Inside the target box, cap the final step even when history carries a larger one.
        if (limitOverlap) yawStep = Mth.clamp(yawStep, -OVERLAP_MAX_YAW_STEP, OVERLAP_MAX_YAW_STEP);
        float pitchStep =
                SmoothK.acceleratedAxisStep(
                        pitchDemand,
                        previousPitchStep,
                        pitchAcceleration,
                        0.0D,
                        PITCH_DEADZONE,
                        MAX_PITCH_STEP);
        // Point-space noise is already present in demand; do not add synthetic pitch counts.
        return new Rotation(
                base.yaw() + yawStep, Mth.clamp(base.pitch() + pitchStep, -90.0F, 90.0F));
    }
}
