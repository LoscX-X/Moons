package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.RandomMath;

/** Seeded, continuous variation sampled by elapsed time, independent of call count. */
public final class AimMotionNoise {
    private AimMotionNoise() {}

    public static double sample(double time, long seed) {
        long cell = (long) Math.floor(time);
        double fraction = time - cell;
        double blend =
                fraction * fraction * fraction * (fraction * (fraction * 6.0D - 15.0D) + 10.0D);
        double from = RandomMath.seededSigned(cell, seed);
        return from + (RandomMath.seededSigned(cell + 1L, seed) - from) * blend;
    }
}
