package com.blanoir.moons.client.utils.math;

/** Pure response coefficients. Callers own time bounds, settings, and final snapping. */
public final class Smoothing {
    private Smoothing() {}

    public static double exponentialResponse(double response, double elapsedSeconds) {
        return 1.0D - Math.exp(-response * elapsedSeconds);
    }

    /** Converts a per-tick blend into a blend for a possibly fractional tick interval. */
    public static double coefficientForElapsedTicks(double coefficient, double elapsedTicks) {
        return 1.0D - Math.pow(1.0D - coefficient, elapsedTicks);
    }
}
