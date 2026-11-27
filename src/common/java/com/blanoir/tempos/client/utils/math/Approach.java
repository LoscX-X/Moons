package com.blanoir.tempos.client.utils.math;

/** Bounded scalar and wrapped-angle steps. */
public final class Approach {
    private Approach() {}

    /** Moves a scalar by at most the caller's step; this is not exponential smoothing. */
    public static float approach(float current, float target, float maxChange) {
        return current + Math.clamp(target - current, -maxChange, maxChange);
    }

    public static double approach(double current, double target, double maxChange) {
        return current + Math.clamp(target - current, -maxChange, maxChange);
    }

    /** Limits the shortest yaw turn while retaining the current whole-turn domain. */
    public static float approachWrapped(float current, float target, float maxChange) {
        return current
                + Math.clamp(
                        AngleMath.wrappedAngleDifference(current, target), -maxChange, maxChange);
    }
}
