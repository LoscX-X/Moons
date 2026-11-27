package com.blanoir.tempos.client.utils.rotation.aim;

import com.blanoir.tempos.client.utils.math.SeededNoise;

/** Existing continuous seeded motion-noise function. Stateless; does not draw new random seeds. */
public final class SeededMotionNoise {
    private SeededMotionNoise() {}

    public static double sample(double time, long seed) {
        long cell = (long) Math.floor(time);
        double fraction = time - cell;
        double blend =
                fraction * fraction * fraction * (fraction * (fraction * 6.0D - 15.0D) + 10.0D);
        double from = SeededNoise.seededSigned(cell, seed);
        return from + (SeededNoise.seededSigned(cell + 1L, seed) - from) * blend;
    }
}
