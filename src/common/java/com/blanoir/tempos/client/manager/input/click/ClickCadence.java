package com.blanoir.tempos.client.manager.input.click;

/** Client-frame deadlines retain fractional time without replaying clicks after a stall. */
public final class ClickCadence {
    private final ClickTiming timing;
    private boolean active;
    private long deadline;

    public ClickCadence(long seed) {
        timing = new ClickTiming(seed);
    }

    public boolean poll(
            long now, boolean allowed, double minimum, double maximum, ClickTiming.Mode mode) {
        if (!allowed) {
            reset();
            return false;
        }
        if (!active) {
            active = true;
            deadline = now + 50_000_000L;
        }
        if (now - deadline < 0) return false;
        long delay =
                Math.max(50L, timing.nextDelayMillis(now / 1_000_000L, minimum, maximum, mode))
                        * 1_000_000L;
        long candidate = deadline + delay;
        deadline = now - deadline > 100_000_000L || candidate - now <= 0 ? now + delay : candidate;
        if (deadline - now < 50_000_000L) deadline = now + 50_000_000L;
        return true;
    }

    public void reset() {
        active = false;
    }
}
