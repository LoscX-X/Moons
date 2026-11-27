package com.blanoir.tempos.client.manager.combat;

import com.blanoir.tempos.client.utils.math.RandomRange;

/** Monotonic CPS scheduling with fractional tick carry and no burst replay after a pause. */
public final class ClickScheduler {
    private long nextClickAt;

    public boolean ready(long now) {
        return nextClickAt == 0 || now - nextClickAt >= 0;
    }

    public void clicked(long now, double minimum, double maximum) {
        double sampledCps = RandomRange.between(minimum, maximum);
        long interval = (long) (1_000_000_000D / Math.max(1D, sampledCps));
        long base = nextClickAt == 0 || now - nextClickAt > 100_000_000L ? now : nextClickAt;
        nextClickAt = base + interval;
    }

    public void reset() {
        nextClickAt = 0;
    }
}
