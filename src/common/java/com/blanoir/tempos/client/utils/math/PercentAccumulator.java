package com.blanoir.tempos.client.utils.math;

/** Preserves a deterministic percentage cadence for consumers that already use it. */
public final class PercentAccumulator {
    private double accumulated;

    public boolean test(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0D) return false;
        accumulated = (accumulated % 100.0D) + Math.min(percent, 100.0D);
        return accumulated >= 100.0D;
    }
}
