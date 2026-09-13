package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothA;

/**
 * A: State adapter for the shared SmoothA pursuit integrator. Tracking and camera return
 * supply their own desired angle and response; only the caller-owned angle/velocity fields
 * are written here. No point policy, return curve, random sampling or lifecycle decisions.
 */
public final class AimStepA {
    private AimStepA() {}

    private static final float ANGLE_DEADZONE = 0.075F;

    static void stepToward(
            AimState state,
            AimProfile profile,
            Rotation desired,
            double rawDelta,
            double response,
            double maxYawSpeed,
            double maxPitchSpeed,
            double yawStepScale,
            double pitchStepScale) {
        SmoothA.Motion next =
                SmoothA.track(
                        new SmoothA.Motion(
                                state.yaw, state.pitch, state.yawVelocity, state.pitchVelocity),
                        desired,
                        rawDelta,
                        new SmoothA.Tracking(
                                response,
                                maxYawSpeed,
                                maxPitchSpeed,
                                yawStepScale,
                                pitchStepScale,
                                state.yawFeedForward,
                                ANGLE_DEADZONE,
                                !profile.lock() && !state.returning));
        state.yaw = next.yaw();
        state.pitch = next.pitch();
        state.yawVelocity = next.yawVelocity();
        state.pitchVelocity = next.pitchVelocity();
    }
}
