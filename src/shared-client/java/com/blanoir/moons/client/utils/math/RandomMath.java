package com.blanoir.moons.client.utils.math;

import java.util.concurrent.ThreadLocalRandom;

/** Shared bounded-random helpers for module settings expressed as ranges or percentages. */
public final class RandomMath {
    private RandomMath() {
    }

    public static boolean chancePercent(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0D) return false;
        if (percent >= 100.0D) return true;
        return ThreadLocalRandom.current().nextDouble(100.0D) < percent;
    }

    public static boolean chance(double probability) {
        if (!Double.isFinite(probability) || probability <= 0.0D) return false;
        return probability >= 1.0D || ThreadLocalRandom.current().nextDouble() < probability;
    }

    public static double between(double first, double second) {
        double min = Math.min(first, second);
        double max = Math.max(first, second);
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("Random bounds must be finite");
        }
        return min == max ? min : ThreadLocalRandom.current().nextDouble(min, Math.nextUp(max));
    }

    public static int betweenInclusive(int first, int second) {
        int min = Math.min(first, second);
        int max = Math.max(first, second);
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public static long betweenInclusive(long first, long second) {
        long min = Math.min(first, second);
        long max = Math.max(first, second);
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1L);
    }
}
