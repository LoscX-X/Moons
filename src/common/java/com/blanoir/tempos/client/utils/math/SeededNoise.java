package com.blanoir.tempos.client.utils.math;

/** Reproducible indexed noise. */
public final class SeededNoise {
    private SeededNoise() {}

    /** Stable variation in [-1, 1), independent of sampling order or call count. */
    public static double seededSigned(long index, long seed) {
        long bits = seed + index * 0x9E3779B97F4A7C15L;
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        return (bits >>> 11) * 0x1.0p-52 - 1.0D;
    }
}
