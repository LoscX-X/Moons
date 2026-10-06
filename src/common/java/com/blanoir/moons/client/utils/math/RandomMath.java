package com.blanoir.moons.client.utils.math;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Shared probability, bounded sampling and seeded variation primitives. */
public final class RandomMath {
    private RandomMath() {}

    /** Independent trial with a probability expressed as 0..100 percent. */
    public static boolean chancePercent(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0D) return false;
        if (percent >= 100.0D) return true;
        return ThreadLocalRandom.current().nextDouble(100.0D) < percent;
    }

    /** Independent trial with a probability expressed as a 0..1 fraction. */
    public static boolean chance(double probability) {
        if (!Double.isFinite(probability) || probability <= 0.0D) return false;
        return probability >= 1.0D || nextDouble() < probability;
    }

    public static boolean nextBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }

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

    /** Stable variation in [-1, 1), independent of sampling order or call count. */
    public static double seededSigned(long index, long seed) {
        long bits = seed + index * 0x9E3779B97F4A7C15L;
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        bits ^= bits >>> 31;
        return (bits >>> 11) * 0x1.0p-52 - 1.0D;
    }

    /** Credentials must use the cryptographic source rather than gameplay sampling. */
    public static byte[] secureBytes(int length) {
        byte[] bytes = new byte[length];
        SecureSource.RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static UUID uuid() {
        return UUID.randomUUID();
    }

    /** Preserves a deterministic percentage cadence for consumers that already use it. */
    public static final class PercentAccumulator {
        private double accumulated;

        public boolean test(double percent) {
            if (!Double.isFinite(percent) || percent <= 0.0D) return false;
            accumulated = (accumulated % 100.0D) + Math.min(percent, 100.0D);
            return accumulated >= 100.0D;
        }
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

    private static final class SecureSource {
        private static final SecureRandom RANDOM = new SecureRandom();
    }
}
