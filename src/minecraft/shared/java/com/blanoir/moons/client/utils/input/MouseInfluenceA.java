package com.blanoir.moons.client.utils.input;

import net.minecraft.util.Mth;

/**
 * A: Existing mouse-activity influence on assistance. Computes one multiplier from
 * explicitly sampled motion values; it neither polls input nor performs angle smoothing.
 */
public final class MouseInfluenceA {
    private MouseInfluenceA() {}

    public static double mouseMultiplier(
            boolean recentlyMoved,
            double velocityPxPerSecond,
            double accelerationPxPerSecondSquared) {
        if (!recentlyMoved) {
            return 1.0D;
        }

        double velocityInfluence = Mth.clamp(velocityPxPerSecond / 250.0D, 0.0D, 1.0D);

        double accelerationInfluence =
                Mth.clamp(accelerationPxPerSecondSquared / 2000.0D, 0.0D, 1.0D);

        double influence = Math.max(velocityInfluence, accelerationInfluence);

        return 1.0D - 0.65D * influence;
    }
}
