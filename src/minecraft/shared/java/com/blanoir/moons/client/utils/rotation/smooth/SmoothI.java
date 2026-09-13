package com.blanoir.moons.client.utils.rotation.smooth;

import net.minecraft.util.Mth;

/**
 * I: Body-yaw velocity integration with acceleration and overshoot bounds.
 * Numeric state in/out only; render interpolation is a separate algorithm.
 */
public final class SmoothI {
    private SmoothI() {}

    public record AxisMotion(float angle, float velocity) {}

    public static AxisMotion bodyYaw(
            float yaw,
            float velocity,
            float targetYaw,
            float seconds,
            float response,
            float maxSpeed,
            float acceleration) {
        float difference = Mth.wrapDegrees(targetYaw - yaw);
        float desiredVelocity = Mth.clamp(difference * response, -maxSpeed, maxSpeed);
        float velocityChange = acceleration * seconds;
        velocity = Mth.clamp(desiredVelocity, velocity - velocityChange, velocity + velocityChange);
        float step = velocity * seconds;
        if (Math.signum(step) == Math.signum(difference) && Math.abs(step) > Math.abs(difference)) {
            step = difference;
            velocity = 0.0F;
        }
        yaw += step;
        return new AxisMotion(yaw, velocity);
    }
}
