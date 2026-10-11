package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimMotionSampling;
import com.blanoir.moons.client.utils.rotation.quantize.MouseAngleQuantizer;
import com.blanoir.moons.client.utils.rotation.quantize.PacketStepQuantizer;
import com.blanoir.moons.client.utils.rotation.smooth.FullLockRotationStep;
import com.blanoir.moons.client.utils.rotation.smooth.PacketRotationStep;

import net.minecraft.util.Mth;

import java.util.function.DoubleBinaryOperator;

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
    @FunctionalInterface
    interface StepAdjustment {
        Rotation apply(
                Rotation base,
                Rotation primary,
                float previousYaw,
                float previousPitch,
                double yawMax,
                double pitchMax,
                double yawAcceleration,
                double pitchAcceleration);
    }

    private final boolean lockMode;
    private final boolean fullLockMode;
    private final DoubleBinaryOperator random;

    private boolean confirmed;
    private float confirmedYaw;
    private float confirmedPitch;
    private float previousYawStep;
    private float previousPitchStep;

    private int sampledTick = Integer.MIN_VALUE;
    private Rotation sampled = new Rotation(0.0F, 0.0F);
    private float sampledBaseYaw;
    private float sampledBasePitch;
    private AimMotionSampling.PacketMotion previousMotion;

    public PacketRotationSmoother(boolean lockMode) {
        this(lockMode, false);
    }

    public PacketRotationSmoother(boolean lockMode, boolean fullLockMode) {
        this(lockMode, fullLockMode, RandomMath::between);
    }

    /** Package-local deterministic replay seam; live callers retain their original source. */
    PacketRotationSmoother(boolean lockMode, boolean fullLockMode, DoubleBinaryOperator random) {
        this.lockMode = lockMode;
        this.fullLockMode = fullLockMode;
        this.random = random;
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
        return sample(
                tick,
                cameraYaw,
                cameraPitch,
                desiredYaw,
                desiredPitch,
                sensitivity,
                overlappingTarget,
                matrixCompatibility,
                fullLockAngleStep,
                fullLockSmoothing,
                false,
                1,
                null);
    }

    Rotation sample(
            int tick,
            float cameraYaw,
            float cameraPitch,
            float desiredYaw,
            float desiredPitch,
            double sensitivity,
            boolean overlappingTarget,
            boolean matrixCompatibility,
            int fullLockAngleStep,
            double fullLockSmoothing,
            StepAdjustment adjustment) {
        return sample(
                tick,
                cameraYaw,
                cameraPitch,
                desiredYaw,
                desiredPitch,
                sensitivity,
                overlappingTarget,
                matrixCompatibility,
                fullLockAngleStep,
                fullLockSmoothing,
                false,
                1,
                adjustment);
    }

    Rotation sample(
            int tick,
            float cameraYaw,
            float cameraPitch,
            float desiredYaw,
            float desiredPitch,
            double sensitivity,
            boolean overlappingTarget,
            boolean matrixCompatibility,
            int fullLockAngleStep,
            double fullLockSmoothing,
            boolean packetPlanner,
            int packetPlannerTicks,
            StepAdjustment adjustment) {
        if (sampledTick == tick) return sampled;

        sampledTick = tick;
        sampledBaseYaw = confirmed ? confirmedYaw : cameraYaw;
        sampledBasePitch = confirmed ? confirmedPitch : cameraPitch;
        Rotation base = new Rotation(sampledBaseYaw, sampledBasePitch);
        float yawDemand = MathUtils.wrappedAngleDifference(sampledBaseYaw, desiredYaw);
        float pitchDemand = desiredPitch - sampledBasePitch;
        double demand = Math.hypot(yawDemand, pitchDemand);
        double urgency = Mth.clamp(demand / 55.0D, 0.0D, 1.0D);

        // The switch is a real compatibility escape hatch: disabled FullLock
        // retains its original direct step and does not consume motion samples.
        if (fullLockMode && !packetPlanner) {
            double cap =
                    Mth.clamp(fullLockAngleStep, 0, 180)
                            * (1 - .5 * Mth.clamp(fullLockSmoothing, 0, 1));
            sampled =
                    sampleFullLock(
                            desiredYaw,
                            desiredPitch,
                            fullLockAngleStep,
                            fullLockSmoothing,
                            sensitivity);
            if (adjustment != null) {
                sampled =
                        adjustment.apply(
                                base,
                                sampled,
                                previousYawStep,
                                previousPitchStep,
                                cap,
                                cap,
                                Double.POSITIVE_INFINITY,
                                Double.POSITIVE_INFINITY);
            }
            return sampled;
        }

        // Every planned aim mode shares one packet-domain motion budget. Profiles only
        // choose the target and maximum step; none may restart or bypass the
        // last movement packet's confirmed velocity.
        AimMotionSampling.PacketMotion motion =
                matrixCompatibility
                        ? AimMotionSampling.samplePacketMotion(lockMode, urgency, random)
                        : AimMotionSampling.sampleResponsivePacketMotion(lockMode, urgency, random);
        if (previousMotion != null) {
            motion =
                    new AimMotionSampling.PacketMotion(
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

        double yawMax;
        double pitchMax;
        if (fullLockMode) {
            double cap =
                    Mth.clamp(fullLockAngleStep, 0, 180)
                            * (1 - .5 * Mth.clamp(fullLockSmoothing, 0, 1));
            yawMax = pitchMax = cap;
            sampled =
                    sampleFullLock(
                            desiredYaw,
                            desiredPitch,
                            fullLockAngleStep,
                            fullLockSmoothing,
                            sensitivity);
        } else {
            yawMax = matrixCompatibility && overlappingTarget ? 18 : 48;
            pitchMax = 32;
            sampled =
                    packetPlanner
                            ? PacketRotationStep.plan(
                                    base,
                                    yawDemand,
                                    pitchDemand,
                                    previousYawStep,
                                    previousPitchStep,
                                    motion.yawAcceleration(),
                                    motion.pitchAcceleration(),
                                    yawMax,
                                    pitchMax,
                                    packetPlannerTicks)
                            : PacketRotationStep.step(
                                    base,
                                    yawDemand,
                                    pitchDemand,
                                    previousYawStep,
                                    previousPitchStep,
                                    motion.yawAcceleration(),
                                    motion.pitchAcceleration(),
                                    matrixCompatibility && overlappingTarget);
        }

        // Final receding-horizon step. Target prediction, FullLock and learned
        // residuals may move the desired angle, but every path reaches the
        // movement packet through this one acceleration-aware planner.
        if (packetPlanner) {
            sampled =
                    PacketRotationStep.plan(
                            base,
                            MathUtils.wrappedAngleDifference(base.yaw(), sampled.yaw()),
                            sampled.pitch() - base.pitch(),
                            previousYawStep,
                            previousPitchStep,
                            motion.yawAcceleration(),
                            motion.pitchAcceleration(),
                            yawMax,
                            pitchMax,
                            packetPlannerTicks);
        }
        if (adjustment != null) {
            sampled =
                    adjustment.apply(
                            base,
                            sampled,
                            previousYawStep,
                            previousPitchStep,
                            yawMax,
                            pitchMax,
                            motion.yawAcceleration(),
                            motion.pitchAcceleration());
        }
        if (packetPlanner) {
            sampled = quantizePlanned(base, sampled, sensitivity, motion, yawMax, pitchMax);
        }
        return sampled;
    }

    /** Selects a real mouse-GCD step from the acceleration-feasible interval. */
    private Rotation quantizePlanned(
            Rotation base,
            Rotation desired,
            double sensitivity,
            AimMotionSampling.PacketMotion motion,
            double yawMax,
            double pitchMax) {
        double quantum = MouseAngleQuantizer.mouseSensitivityGcd(sensitivity);
        float yawMinimum = (float) Math.max(-180.0D, Math.min(-yawMax, previousYawStep));
        float yawMaximum = (float) Math.min(180.0D, Math.max(yawMax, previousYawStep));
        float yawStep =
                quantizedStep(
                        MathUtils.wrappedAngleDifference(base.yaw(), desired.yaw()),
                        previousYawStep,
                        motion.yawAcceleration(),
                        quantum,
                        yawMinimum,
                        yawMaximum);
        float pitchMinimum =
                (float)
                        Math.max(
                                -90.0F - base.pitch(),
                                Math.min(-pitchMax, previousPitchStep));
        float pitchMaximum =
                (float)
                        Math.min(
                                90.0F - base.pitch(),
                                Math.max(pitchMax, previousPitchStep));
        float pitchStep =
                quantizedStep(
                        desired.pitch() - base.pitch(),
                        previousPitchStep,
                        motion.pitchAcceleration(),
                        quantum,
                        pitchMinimum,
                        pitchMaximum);
        return new Rotation(base.yaw() + yawStep, base.pitch() + pitchStep);
    }

    static float quantizedStep(
            float desiredStep,
            float previousStep,
            double acceleration,
            double quantum,
            float physicalMinimum,
            float physicalMaximum) {
        return PacketStepQuantizer.step(
                desiredStep, previousStep, acceleration, quantum, physicalMinimum, physicalMaximum);
    }

    /** FULL-Lock angle stepping; the caller applies sensitivity quantization. */
    private Rotation sampleFullLock(
            float desiredYaw,
            float desiredPitch,
            int configuredAngleStep,
            double configuredSmoothing,
            double sensitivity) {
        return FullLockRotationStep.fullLock(
                new Rotation(sampledBaseYaw, sampledBasePitch),
                new Rotation(desiredYaw, desiredPitch),
                configuredAngleStep,
                configuredSmoothing,
                sensitivity);
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
