package com.blanoir.moons.client.utils.rotation.smooth;

import net.minecraft.util.Mth;

/**
 * K: Existing one-axis acceleration/braking step. Numeric inputs only; random envelope
 * sampling is separate in AimSamplingA. No history or random generator is owned here.
 */
public final class SmoothK {
    private SmoothK() {}

    public static float acceleratedAxisStep(
            float demand,
            float previousStep,
            double acceleration,
            double constantError,
            float deadZone,
            float maxStep) {
        if (Math.abs(demand) <= deadZone && Math.abs(previousStep) <= deadZone * 3.0F) {
            return 0.0F;
        }

        double brakingSpeed = Math.sqrt(2.0D * acceleration * Math.abs(demand));
        float desiredStep =
                (float)
                        Math.copySign(
                                Math.min(Math.abs(demand), Math.min(maxStep, brakingSpeed)),
                                demand);
        double errorBlend = Mth.clamp(Math.abs(demand) / 5.0D, 0.18D, 1.0D);
        desiredStep =
                Mth.clamp(desiredStep + (float) (constantError * errorBlend), -maxStep, maxStep);
        return previousStep
                + (float) Mth.clamp(desiredStep - previousStep, -acceleration, acceleration);
    }
}
