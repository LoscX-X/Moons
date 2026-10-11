package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/** Existing packet-domain acceleration stepping only. Sampling, random envelopes,
 * tick caching, final quantization and sent-angle history stay with the owner. */
public final class PacketRotationStep {
    private static final float YAW_DEADZONE = 0.06F;
    private static final float PITCH_DEADZONE = 0.045F;
    private static final float MAX_YAW_STEP = 48.0F;
    private static final float OVERLAP_MAX_YAW_STEP = 18.0F;
    private static final float MAX_PITCH_STEP = 32.0F;

    private PacketRotationStep() {}

    public static Rotation step(
            Rotation base,
            float yawDemand,
            float pitchDemand,
            float previousYawStep,
            float previousPitchStep,
            double yawAcceleration,
            double pitchAcceleration,
            boolean limitOverlap) {
        Rotation result =
                step(
                        base,
                        yawDemand,
                        pitchDemand,
                        previousYawStep,
                        previousPitchStep,
                        yawAcceleration,
                        pitchAcceleration,
                        MAX_YAW_STEP,
                        MAX_PITCH_STEP);
        if (!limitOverlap) return result;
        float yawStep =
                Mth.clamp(result.yaw() - base.yaw(), -OVERLAP_MAX_YAW_STEP, OVERLAP_MAX_YAW_STEP);
        return new Rotation(base.yaw() + yawStep, result.pitch());
    }

    /**
     * Plans the next ordinary movement packet toward a target using the last
     * movement packet's real step. A reduced speed cap is approached through
     * the same acceleration envelope; it is never applied as a post-step snap.
     */
    public static Rotation step(
            Rotation base,
            float yawDemand,
            float pitchDemand,
            float previousYawStep,
            float previousPitchStep,
            double yawAcceleration,
            double pitchAcceleration,
            double maxYawStep,
            double maxPitchStep) {
        float yawStep =
                AcceleratedAxisStep.acceleratedAxisStep(
                        yawDemand,
                        previousYawStep,
                        yawAcceleration,
                        0.0D,
                        YAW_DEADZONE,
                        (float) Math.max(0.0D, maxYawStep));
        float pitchStep =
                AcceleratedAxisStep.acceleratedAxisStep(
                        pitchDemand,
                        previousPitchStep,
                        pitchAcceleration,
                        0.0D,
                        PITCH_DEADZONE,
                        (float) Math.max(0.0D, maxPitchStep));
        // Point-space noise is already present in demand; do not add synthetic pitch counts.
        return new Rotation(
                base.yaw() + yawStep, Mth.clamp(base.pitch() + pitchStep, -90.0F, 90.0F));
    }

    /**
     * Receding-horizon variant: choose the next velocity that would close the
     * remaining angle in {@code planningTicks} packets under constant acceleration,
     * then clamp it to the real per-packet acceleration and speed envelopes.
     */
    public static Rotation plan(
            Rotation base,
            float yawDemand,
            float pitchDemand,
            float previousYawStep,
            float previousPitchStep,
            double yawAcceleration,
            double pitchAcceleration,
            double maxYawStep,
            double maxPitchStep,
            int planningTicks) {
        int horizon = Mth.clamp(planningTicks, 1, 5);
        float yawStep =
                plannedAxisStep(
                        yawDemand,
                        previousYawStep,
                        yawAcceleration,
                        YAW_DEADZONE,
                        (float) Math.max(0.0D, maxYawStep),
                        horizon);
        float pitchStep =
                plannedAxisStep(
                        pitchDemand,
                        previousPitchStep,
                        pitchAcceleration,
                        PITCH_DEADZONE,
                        (float) Math.max(0.0D, maxPitchStep),
                        horizon);
        return new Rotation(
                base.yaw() + yawStep, Mth.clamp(base.pitch() + pitchStep, -90.0F, 90.0F));
    }

    static float plannedAxisStep(
            float demand,
            float previousStep,
            double acceleration,
            float deadZone,
            float maxStep,
            int planningTicks) {
        if (Math.abs(demand) <= deadZone && Math.abs(previousStep) <= deadZone * 3.0F) {
            return 0.0F;
        }
        int horizon = Math.max(1, planningTicks);
        double requiredAcceleration =
                2.0D * (demand - horizon * previousStep) / (horizon * (horizon + 1.0D));
        double next =
                previousStep
                        + Mth.clamp(requiredAcceleration, -acceleration, acceleration);
        double capped = Mth.clamp(next, -maxStep, maxStep);
        // A newly reduced cap (for example the 18-degree overlap cap) is itself
        // approached through the acceleration envelope instead of snapping.
        return previousStep
                + (float) Mth.clamp(capped - previousStep, -acceleration, acceleration);
    }
}
