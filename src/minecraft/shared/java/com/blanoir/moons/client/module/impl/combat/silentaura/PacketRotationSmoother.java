package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.HumanAimSimulator;

import net.minecraft.util.Mth;

/**
 * Converts a render-cadence aim into one packet-cadence rotation sample.
 *
 * <p>The target tracker is intentionally free to run at the render FPS. The
 * server, however, only observes movement packets. Sampling frame-randomized
 * exponential motion directly at 20 TPS aliases several frames into a very
 * regular second derivative. This class keeps the packet stream in its own
 * domain and derives every step from the last two rotations actually sent.
 */
public final class PacketRotationSmoother {
    private static final float YAW_DEADZONE = 0.06F;
    private static final float PITCH_DEADZONE = 0.045F;
    private static final float MAX_YAW_STEP = 48.0F;

    /** Limits consecutive yaw deltas to avoid repeated steps above 20 degrees. */
    private static final float OVERLAP_MAX_YAW_STEP = 18.0F;

    private static final float MAX_PITCH_STEP = 32.0F;

    private final boolean lockMode;
    private final boolean fullLockMode;

    private boolean confirmed;
    private float confirmedYaw;
    private float confirmedPitch;
    private float previousYawStep;
    private float previousPitchStep;

    private int sampledTick = Integer.MIN_VALUE;
    private Rotation sampled = new Rotation(0.0F, 0.0F);
    private float sampledBaseYaw;
    private float sampledBasePitch;
    private HumanAimSimulator.PacketMotion previousMotion;

    public PacketRotationSmoother(boolean lockMode) {
        this(lockMode, false);
    }

    public PacketRotationSmoother(boolean lockMode, boolean fullLockMode) {
        this.lockMode = lockMode;
        this.fullLockMode = fullLockMode;
    }

    public Rotation sample(
            int tick,
            float cameraYaw,
            float cameraPitch,
            float desiredYaw,
            float desiredPitch,
            double sensitivity,
            boolean overlappingTarget,
            boolean matrixCompatibility) {
        return sample(
                tick,
                cameraYaw,
                cameraPitch,
                desiredYaw,
                desiredPitch,
                sensitivity,
                overlappingTarget,
                matrixCompatibility,
                90,
                0.0D);
    }

    public Rotation sample(
            int tick,
            float cameraYaw,
            float cameraPitch,
            float desiredYaw,
            float desiredPitch,
            double sensitivity,
            boolean overlappingTarget,
            boolean matrixCompatibility,
            int fullLockAngleStep,
            double fullLockSmoothing) {
        if (sampledTick == tick) return sampled;

        sampledTick = tick;
        sampledBaseYaw = confirmed ? confirmedYaw : cameraYaw;
        sampledBasePitch = confirmed ? confirmedPitch : cameraPitch;
        if (fullLockMode) {
            sampled =
                    sampleFullLock(
                            desiredYaw,
                            desiredPitch,
                            fullLockAngleStep,
                            fullLockSmoothing,
                            sensitivity);
            return sampled;
        }
        float yawDemand = MathUtils.wrappedAngleDifference(sampledBaseYaw, desiredYaw);
        float pitchDemand = desiredPitch - sampledBasePitch;
        double demand = Math.hypot(yawDemand, pitchDemand);
        double urgency = Mth.clamp(demand / 55.0D, 0.0D, 1.0D);

        // The compatibility profile retains stricter acceleration bounds;
        // the generic profile can respond faster.
        HumanAimSimulator.PacketMotion motion =
                matrixCompatibility
                        ? HumanAimSimulator.samplePacketMotion(lockMode, urgency)
                        : HumanAimSimulator.sampleResponsivePacketMotion(lockMode, urgency);
        if (previousMotion != null) {
            motion =
                    new HumanAimSimulator.PacketMotion(
                            Mth.lerp(
                                    0.3D,
                                    previousMotion.yawAcceleration(),
                                    motion.yawAcceleration()),
                            Mth.lerp(
                                    0.3D,
                                    previousMotion.pitchAcceleration(),
                                    motion.pitchAcceleration()),
                            0.0D,
                            0.0D);
        }
        previousMotion = motion;

        float yawStep =
                HumanAimSimulator.acceleratedAxisStep(
                        yawDemand,
                        previousYawStep,
                        motion.yawAcceleration(),
                        0.0D,
                        YAW_DEADZONE,
                        MAX_YAW_STEP);
        // The look direction is geometrically unstable while the eye is in the
        // target box. Cap the final packet-domain delta as well as the render
        // controller: render sampling can otherwise preserve a >20-degree
        // previous step for a second packet, causing consecutive large turns.
        if (matrixCompatibility && overlappingTarget) {
            yawStep = Mth.clamp(yawStep, -OVERLAP_MAX_YAW_STEP, OVERLAP_MAX_YAW_STEP);
        }
        // Keep airborne tracking in target prediction; this step remains bounded.
        float pitchStep =
                HumanAimSimulator.acceleratedAxisStep(
                        pitchDemand,
                        previousPitchStep,
                        motion.pitchAcceleration(),
                        0.0D,
                        PITCH_DEADZONE,
                        MAX_PITCH_STEP);
        // Point-space noise already expresses the desired motion. Injecting a
        // synthetic pitch count here created a +/-1 GCD oscillation at level aim.
        sampled =
                new Rotation(
                        sampledBaseYaw + yawStep,
                        Mth.clamp(sampledBasePitch + pitchStep, -90.0F, 90.0F));
        return sampled;
    }

    /** FULL-Lock angle stepping; the caller applies sensitivity quantization. */
    private Rotation sampleFullLock(
            float desiredYaw,
            float desiredPitch,
            int configuredAngleStep,
            double configuredSmoothing,
            double sensitivity) {
        float angleStep = (float) Mth.clamp(configuredAngleStep, 0.0D, 180.0D);
        float yawDemand = MathUtils.wrappedAngleDifference(sampledBaseYaw, desiredYaw);
        float pitchDemand = desiredPitch - sampledBasePitch;
        float deadzone =
                (float) Math.min(0.06D, HumanAimSimulator.mouseSensitivityGcd(sensitivity) * 0.5D);
        float yawStep = fullLockAxisStep(yawDemand, angleStep, configuredSmoothing, deadzone);
        float pitchStep = fullLockAxisStep(pitchDemand, angleStep, configuredSmoothing, deadzone);
        return new Rotation(
                sampledBaseYaw + yawStep, Mth.clamp(sampledBasePitch + pitchStep, -90.0F, 90.0F));
    }

    private static float fullLockAxisStep(
            float demand, float angleStep, double smoothing, float deadzone) {
        if (Math.abs(demand) <= deadzone) return 0.0F;
        double scale = 1.0D - 0.5D * Mth.clamp(smoothing, 0.0D, 1.0D);
        return (float) (Mth.clamp(demand, -angleStep, angleStep) * scale);
    }

    /** Records the exact post-GCD pair written into the movement packet. */
    public void confirm(float yaw, float pitch) {
        float baseYaw = confirmed ? confirmedYaw : sampledBaseYaw;
        float basePitch = confirmed ? confirmedPitch : sampledBasePitch;
        previousYawStep = MathUtils.wrappedAngleDifference(baseYaw, yaw);
        previousPitchStep = pitch - basePitch;
        confirmedYaw = yaw;
        confirmedPitch = pitch;
        confirmed = true;
    }

    public void reset() {
        previousMotion = null;
        confirmed = false;
        confirmedYaw = confirmedPitch = 0.0F;
        previousYawStep = previousPitchStep = 0.0F;
        sampledTick = Integer.MIN_VALUE;
        sampled = new Rotation(0.0F, 0.0F);
        sampledBaseYaw = sampledBasePitch = 0.0F;
    }

    /** Start a new owner/profile from the latest observed server rotation. */
    public void rebase(float yaw, float pitch) {
        reset();
        confirmed = true;
        confirmedYaw = yaw;
        confirmedPitch = pitch;
    }
}
