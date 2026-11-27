package com.blanoir.tempos.client.utils.math;

import java.util.concurrent.ThreadLocalRandom;

/** Uniform samples from the current thread’s random source. */
public final class RandomRange {
    private RandomRange() {}

    /** Uniform value in [0, 1). */
    public static double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }

    /** Uniform value in [min, max); equal bounds return that value. Bounds may be reversed. */
    public static double nextDouble(double first, double second) {
        requireFinite(first, second);
        double min = Math.min(first, second);
        double max = Math.max(first, second);
        if (min == max) return min;
        if (Double.isFinite(max - min)) {
            return ThreadLocalRandom.current().nextDouble(min, max);
        }
        return Math.clamp(interpolate(min, max), min, Math.nextDown(max));
    }

    /** Uniform bounded variation with both endpoints included, matching range settings. */
    public static double between(double first, double second) {
        requireFinite(first, second);
        double min = Math.min(first, second);
        double max = Math.max(first, second);
        if (min == max) return min;
        double exclusiveMax = Math.nextUp(max);
        if (Double.isFinite(exclusiveMax) && Double.isFinite(exclusiveMax - min)) {
            return ThreadLocalRandom.current().nextDouble(min, exclusiveMax);
        }
        return Math.clamp(interpolate(min, max), min, max);
    }

    public static int betweenInclusive(int first, int second) {
        int min = Math.min(first, second);
        int max = Math.max(first, second);
        if (min == max) return min;
        return max == Integer.MAX_VALUE
                ? (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1L)
                : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public static long betweenInclusive(long first, long second) {
        long min = Math.min(first, second);
        long max = Math.max(first, second);
        if (min == max) return min;
        if (max != Long.MAX_VALUE) return ThreadLocalRandom.current().nextLong(min, max + 1L);
        if (min == Long.MIN_VALUE) return nextLong();
        return ThreadLocalRandom.current().nextLong(min - 1L, max) + 1L;
    }

    /** Unrestricted sample suitable for a caller-owned non-security noise seed. */
    public static long nextLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    private static double interpolate(double min, double max) {
        double fraction = nextDouble();
        // Weighted endpoints avoid overflowing max - min for wide finite ranges.
        return min * (1.0D - fraction) + max * fraction;
    }

    private static void requireFinite(double first, double second) {
        if (!Double.isFinite(first) || !Double.isFinite(second)) {
            throw new IllegalArgumentException("Random bounds must be finite");
        }
    }
}
