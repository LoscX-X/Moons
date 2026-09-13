package com.blanoir.moons.client.utils.rotation.smooth;

import static com.blanoir.moons.client.utils.math.MathUtils.approach;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.Smoothing;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/**
 * A: Existing frame pursuit with optional velocity inertia. Used by Lock/Balance and return.
 * No owned state, clock, random sampling or output writes; callers supply history and profile.
 */
public final class SmoothA {
    private SmoothA() {}

    public record Motion(float yaw, float pitch, float yawVelocity, float pitchVelocity) {}

    public record Tracking(
            double response,
            double maxYawSpeed,
            double maxPitchSpeed,
            double yawStepScale,
            double pitchStepScale,
            double yawFeedForward,
            float deadzone,
            boolean inertial) {}

    public static Motion track(
            Motion current, Rotation desired, double rawDelta, Tracking profile) {
        double delta = Mth.clamp(rawDelta, 0.0D, 1.0D / 20.0D);
        if (delta <= 0.0D) return current;
        float yaw = current.yaw();
        float pitch = current.pitch();
        float yawVelocity = current.yawVelocity();
        float pitchVelocity = current.pitchVelocity();
        double yawStepScale = profile.yawStepScale();
        double pitchStepScale = profile.pitchStepScale();
        float yawDifference = MathUtils.wrappedAngleDifference(yaw, desired.yaw());
        float pitchDifference = desired.pitch() - pitch;
        double responseFraction =
                Smoothing.exponentialResponse(Math.max(0.01D, profile.response()), delta);
        double yawCap = profile.maxYawSpeed() * delta * Mth.clamp(yawStepScale, 0.62D, 1.42D);
        double pitchCap = profile.maxPitchSpeed() * delta * Mth.clamp(pitchStepScale, 0.62D, 1.42D);
        float yawStep =
                Math.abs(yawDifference) <= profile.deadzone()
                        ? 0.0F
                        : (float) Mth.clamp(yawDifference * responseFraction, -yawCap, yawCap);
        yawStep = (float) Mth.clamp(yawStep + profile.yawFeedForward() * delta, -yawCap, yawCap);
        float pitchStep =
                Math.abs(pitchDifference) <= profile.deadzone()
                        ? 0.0F
                        : (float)
                                Mth.clamp(pitchDifference * responseFraction, -pitchCap, pitchCap);
        if (profile.inertial()) {
            // Integrate the acceleration ramp using the average old/new velocity.
            float nextYawVelocity =
                    approach(
                            yawVelocity,
                            yawStep / (float) delta,
                            (float) (3_600.0D * yawStepScale * delta));
            float nextPitchVelocity =
                    approach(
                            pitchVelocity,
                            pitchStep / (float) delta,
                            (float) (2_600.0D * pitchStepScale * delta));
            yawStep = (yawVelocity + nextYawVelocity) * 0.5F * (float) delta;
            pitchStep = (pitchVelocity + nextPitchVelocity) * 0.5F * (float) delta;
            yawVelocity = nextYawVelocity;
            pitchVelocity = nextPitchVelocity;
        } else {
            yawVelocity = yawStep / (float) delta;
            pitchVelocity = pitchStep / (float) delta;
        }
        // Wrap differences only; keep the accumulated yaw continuous across +/-180.
        yaw += yawStep;
        pitch = Mth.clamp(pitch + pitchStep, -90.0F, 90.0F);
        return new Motion(yaw, pitch, yawVelocity, pitchVelocity);
    }
}
