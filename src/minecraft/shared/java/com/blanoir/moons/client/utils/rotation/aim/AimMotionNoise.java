package com.blanoir.moons.client.utils.rotation.aim;

/** Seeded, continuous variation sampled by elapsed time, independent of call count. */
public final class AimMotionNoise {
    private AimMotionNoise() {}

    public static double sample(double time, long seed) {
        long cell = (long) Math.floor(time);
        double fraction = time - cell;
        double blend =
                fraction * fraction * fraction * (fraction * (fraction * 6.0D - 15.0D) + 10.0D);
        double from = value(cell, seed);
        return from + (value(cell + 1L, seed) - from) * blend;
    }

    private static double value(long cell, long seed) {
        long bits = seed + cell * 0x9E3779B97F4A7C15L;
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        return (bits >>> 11) * 0x1.0p-52 - 1.0D;
    }
}
