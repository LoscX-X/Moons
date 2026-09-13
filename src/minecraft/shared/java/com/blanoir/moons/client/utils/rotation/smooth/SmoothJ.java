package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;

import net.minecraft.util.Mth;

/**
 * J: Existing FullLock angle-cap stepping and smoothing scale.
 * Sensitivity only sets the deadzone; final quantization and packet history stay outside.
 */
public final class SmoothJ {
    private SmoothJ() {}

    public static Rotation fullLock(
            Rotation base,
            Rotation desired,
            int configuredAngleStep,
            double configuredSmoothing,
            double sensitivity) {
        float angleStep = (float) Mth.clamp(configuredAngleStep, 0.0D, 180.0D);
        float yawDemand = MathUtils.wrappedAngleDifference(base.yaw(), desired.yaw());
        float pitchDemand = desired.pitch() - base.pitch();
        float deadzone =
                (float) Math.min(0.06D, QuantizerA.mouseSensitivityGcd(sensitivity) * 0.5D);
        float yawStep = fullLockAxisStep(yawDemand, angleStep, configuredSmoothing, deadzone);
        float pitchStep = fullLockAxisStep(pitchDemand, angleStep, configuredSmoothing, deadzone);
        return new Rotation(
                base.yaw() + yawStep, Mth.clamp(base.pitch() + pitchStep, -90.0F, 90.0F));
    }

    private static float fullLockAxisStep(
            float demand, float angleStep, double smoothing, float deadzone) {
        if (Math.abs(demand) <= deadzone) return 0.0F;
        double scale = 1.0D - 0.5D * Mth.clamp(smoothing, 0.0D, 1.0D);
        return (float) (Mth.clamp(demand, -angleStep, angleStep) * scale);
    }
}
