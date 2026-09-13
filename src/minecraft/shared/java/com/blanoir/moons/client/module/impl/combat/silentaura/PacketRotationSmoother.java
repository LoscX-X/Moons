package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimSamplingA;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothF;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothJ;

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
    private AimSamplingA.PacketMotion previousMotion;

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
        AimSamplingA.PacketMotion motion =
                matrixCompatibility
                        ? AimSamplingA.samplePacketMotion(lockMode, urgency)
                        : AimSamplingA.sampleResponsivePacketMotion(lockMode, urgency);
        if (previousMotion != null) {
            motion =
                    new AimSamplingA.PacketMotion(
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

        sampled =
                SmoothF.step(
                        new Rotation(sampledBaseYaw, sampledBasePitch),
                        yawDemand,
                        pitchDemand,
                        previousYawStep,
                        previousPitchStep,
                        motion.yawAcceleration(),
                        motion.pitchAcceleration(),
                        matrixCompatibility && overlappingTarget);
        return sampled;
    }

    /** FULL-Lock angle stepping; the caller applies sensitivity quantization. */
    private Rotation sampleFullLock(
            float desiredYaw,
            float desiredPitch,
            int configuredAngleStep,
            double configuredSmoothing,
            double sensitivity) {
        return SmoothJ.fullLock(
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
