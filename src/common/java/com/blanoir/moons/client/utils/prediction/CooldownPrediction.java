package com.blanoir.moons.client.utils.prediction;

import net.minecraft.util.Mth;

/** Attack-charge and overcharge timing in ticks; callers own attack policy. */
public final class CooldownPrediction {
    private CooldownPrediction() {}

    public static double chargeAt(double currentCharge, double attackDelay, int ticks) {
        return Mth.clamp(currentCharge + ticks / attackDelay, 0.0D, 1.0D);
    }

    public static int ticksUntilThreshold(
            double currentCharge, double requiredCharge, double attackDelay) {
        if (currentCharge >= requiredCharge) return 0;
        attackDelay = Math.max(1.0D, attackDelay);
        return Math.max(1, (int) Math.ceil((requiredCharge - currentCharge) * attackDelay));
    }

    public static int projectedOverchargeTicks(
            double currentCharge, double attackDelay, int futureTick, int currentOverchargeTicks) {
        if (currentCharge >= 0.999D) {
            return currentOverchargeTicks + futureTick;
        }
        int ticksUntilFull = Math.max(0, (int) Math.ceil((1.0D - currentCharge) * attackDelay));
        return Math.max(0, futureTick - ticksUntilFull);
    }
}
