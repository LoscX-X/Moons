package com.blanoir.tempos.client.ui.widget;

/** A timed transition. A reversal starts at the currently displayed value. */
public final class Anim {
    private float from, to;
    private long start, duration;

    public Anim(float initial) { from = to = initial; }

    public void snap(float value) {
        from = to = value;
        duration = 0;
    }

    public void to(float next, long now, int millis) {
        if (next == to) return;
        from = value(now);
        to = next;
        start = now;
        duration = Math.max(0L, millis * 1_000_000L);
    }

    public float value(long now) {
        if (duration == 0 || now - start >= duration) return to;
        double t = Math.max(0, (now - start) / (double) duration);
        return from + (to - from) * (float) ((1 - Math.cos(Math.PI * t)) * .5);
    }

    public float target() { return to; }
    public boolean moving(long now) { return duration > 0 && now - start < duration; }
}
