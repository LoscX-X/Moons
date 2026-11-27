package com.blanoir.tempos.client.utils.math;

/** Interpolation curves. */
public final class Easing {
    private Easing() {}

    /** Cubic interpolation polynomial; callers retain their own input clamping. */
    public static double cubicSmoothStep(double progress) {
        return progress * progress * (3.0D - 2.0D * progress);
    }
}
