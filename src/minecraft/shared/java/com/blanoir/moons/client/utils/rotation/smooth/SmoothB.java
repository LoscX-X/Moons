package com.blanoir.moons.client.utils.rotation.smooth;

import static com.blanoir.moons.client.utils.math.MathUtils.approach;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothA.Motion;

import net.minecraft.util.Mth;

/** B: Accelerated fixed-point pursuit with overshoot handling. Cubic return is SmoothG. */
public final class SmoothB {
    private SmoothB() {}

    public record AccelerationLimits(
            double maxYawSpeed,
            double maxPitchSpeed,
            double yawAcceleration,
            double pitchAcceleration) {}

    public static Motion approachTarget(
            Motion current,
            Rotation target,
            double deltaSeconds,
            double response,
            AccelerationLimits limits) {
        float packetYaw = current.yaw();
        float packetPitch = current.pitch();
        float yawVelocity = current.yawVelocity();
        float pitchVelocity = current.pitchVelocity();
        float yawDifference = Mth.wrapDegrees(target.yaw() - packetYaw);
        float pitchDifference = target.pitch() - packetPitch;
        float desiredYawVelocity =
                (float)
                        Mth.clamp(
                                yawDifference * response,
                                -limits.maxYawSpeed(),
                                limits.maxYawSpeed());
        float desiredPitchVelocity =
                (float)
                        Mth.clamp(
                                pitchDifference * response,
                                -limits.maxPitchSpeed(),
                                limits.maxPitchSpeed());
        yawVelocity =
                approach(
                        yawVelocity,
                        desiredYawVelocity,
                        (float) (limits.yawAcceleration() * deltaSeconds));
        pitchVelocity =
                approach(
                        pitchVelocity,
                        desiredPitchVelocity,
                        (float) (limits.pitchAcceleration() * deltaSeconds));

        float yawStep = yawVelocity * (float) deltaSeconds;
        if (Math.signum(yawStep) == Math.signum(yawDifference)
                && Math.abs(yawStep) > Math.abs(yawDifference)) {
            yawStep = yawDifference;
            yawVelocity = 0.0F;
        }
        float pitchStep = pitchVelocity * (float) deltaSeconds;
        if (Math.signum(pitchStep) == Math.signum(pitchDifference)
                && Math.abs(pitchStep) > Math.abs(pitchDifference)) {
            pitchStep = pitchDifference;
            pitchVelocity = 0.0F;
        }
        packetYaw += yawStep;
        packetPitch = Mth.clamp(packetPitch + pitchStep, -90.0F, 90.0F);
        return new Motion(packetYaw, packetPitch, yawVelocity, pitchVelocity);
    }
}
