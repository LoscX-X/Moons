package com.blanoir.tempos.client.utils.math;

import java.util.concurrent.ThreadLocalRandom;

/** Independent probability trials. */
public final class RandomChance {
    private RandomChance() {}

    /** Independent trial with a probability expressed as 0..100 percent. */
    public static boolean chancePercent(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0D) return false;
        if (percent >= 100.0D) return true;
        return ThreadLocalRandom.current().nextDouble(100.0D) < percent;
    }

    /** Independent trial with a probability expressed as a 0..1 fraction. */
    public static boolean chance(double probability) {
        if (!Double.isFinite(probability) || probability <= 0.0D) return false;
        return probability >= 1.0D || RandomRange.nextDouble() < probability;
    }

    public static boolean nextBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }
}
