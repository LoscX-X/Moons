package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.Smoothing;

/**
 * C: Existing visible-camera assist blend. Preserves wrapped-angle and coefficient casts.
 * Frame coefficient conversion belongs to this interpolation; input influence is separate.
 */
public final class SmoothC {
    private SmoothC() {}

    public static float blendAngle(float current, float target, double coefficient) {
        return MathUtils.smoothWrappedAngle(current, target, coefficient);
    }

    public static double frameCoefficient(double smooth, double frameDeltaSeconds) {
        return Smoothing.coefficientForElapsedTicks(smooth, frameDeltaSeconds * 20.0D);
    }
}
