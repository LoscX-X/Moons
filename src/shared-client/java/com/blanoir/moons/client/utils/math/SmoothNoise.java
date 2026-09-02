package com.blanoir.moons.client.utils.math;

/** Deterministic low-frequency noise without frame-to-frame random jumps. */
public final class SmoothNoise {
    private static final double TAU = Math.PI * 2.0D;

    private SmoothNoise() {}

    /** Returns continuous, approximately normalized noise in [-1, 1]. */
    public static double sample(double seconds, long seed) {
        long second = mix(seed);
        long third = mix(second);
        return Math.sin(seconds * 1.07D + phase(seed)) * 0.52D
                + Math.sin(seconds * 1.73D + phase(second)) * 0.31D
                + Math.sin(seconds * 0.61D + phase(third)) * 0.17D;
    }

    private static double phase(long value) {
        return ((value >>> 11) * 0x1.0p-53) * TAU;
    }

    private static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
