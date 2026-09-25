package com.blanoir.moons.client.module.impl.player;

/** Short, tick-based damage history. A low health value alone is not a prediction of death. */
final class AutoTotemDanger {
    static final int HORIZON_TICKS = 10;
    private static final int HISTORY_TICKS = 40;
    private int previousTick = Integer.MIN_VALUE;
    private int lastHitTick = Integer.MIN_VALUE;
    private int interval;
    private int previousHurtTime;
    private float previousHealth, previousAbsorption, lastHitDamage;

    void reset() {
        previousTick = lastHitTick = Integer.MIN_VALUE;
        interval = 0;
        previousHurtTime = 0;
        previousHealth = previousAbsorption = lastHitDamage = 0;
    }

    void sample(int tick, float health, float absorption, int hurtTime) {
        if (previousTick != Integer.MIN_VALUE
                && (tick < previousTick || tick - previousTick > HISTORY_TICKS)) reset();
        if (tick == previousTick) return;
        if (previousTick != Integer.MIN_VALUE) {
            float loss = previousHealth + previousAbsorption - health - absorption;
            // Absorption expiry without a new hurt animation is not another attack.
            if (loss > 0
                    && hurtTime > 0
                    && (health < previousHealth || hurtTime > previousHurtTime)) {
                int elapsed = lastHitTick == Integer.MIN_VALUE ? 0 : tick - lastHitTick;
                if (elapsed > 0 && elapsed < 10) {
                    // Extra damage during vanilla hurt immunity belongs to the same burst.
                    lastHitDamage += loss;
                } else {
                    interval = elapsed >= 10 && elapsed <= HISTORY_TICKS ? elapsed : 0;
                    lastHitDamage = loss;
                    lastHitTick = tick;
                }
            }
        }
        previousTick = tick;
        previousHealth = health;
        previousAbsorption = absorption;
        previousHurtTime = hurtTime;
    }

    float incomingDamage(int tick) {
        if (interval == 0 || lastHitTick == Integer.MIN_VALUE) return 0;
        int elapsed = tick - lastHitTick;
        // Do not perpetually extrapolate a fight after its expected next hit failed to arrive.
        if (elapsed < 0 || elapsed > interval + 2) return 0;
        int untilNext = Math.max(1, interval - elapsed);
        if (untilNext > HORIZON_TICKS) return 0;
        int hits = 1 + (HORIZON_TICKS - untilNext) / interval;
        return lastHitDamage * hits;
    }

    static boolean lethal(float health, float damage) {
        return Float.isFinite(health) && health > 0 && Float.isFinite(damage) && damage >= health;
    }

    static boolean withinWindow(int ticks) {
        return ticks >= 0 && ticks <= HORIZON_TICKS;
    }

    static float damageBudget(
            float health, boolean fallback, boolean subtractDamage, int threshold) {
        return fallback && subtractDamage ? Math.max(0, health - threshold) : health;
    }
}
