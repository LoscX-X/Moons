package com.blanoir.tempos.client.module.impl.combat.aim;

import com.blanoir.tempos.client.utils.math.AngleMath;
import com.blanoir.tempos.client.utils.math.Smoothing;

/**
 * Existing visible-camera assist blend. Preserves wrapped-angle and coefficient casts.
 * Frame coefficient conversion belongs to this interpolation; input influence is separate.
 */
public final class CameraAssistBlend {
    private CameraAssistBlend() {}

    public static float blendAngle(float current, float target, double coefficient) {
        return AngleMath.smoothWrappedAngle(current, target, coefficient);
    }

    public static double frameCoefficient(double smooth, double frameDeltaSeconds) {
        return Smoothing.coefficientForElapsedTicks(smooth, frameDeltaSeconds * 20.0D);
    }
}
