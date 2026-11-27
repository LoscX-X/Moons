package com.blanoir.tempos.client.module.impl.misc;

import net.minecraft.util.Mth;

/** Wall-clock camera transition, independent of tick rate and the number of render hooks. */
final class FreeLookTransition {
    private long started;
    private long duration;
    private float fromDistance;
    private float toDistance;

    void begin(long now, int millis, float from, float to) {
        started = now;
        duration = Math.max(0, millis) * 1_000_000L;
        fromDistance = from;
        toDistance = to;
    }

    float progress(long now) {
        if (duration == 0) return 1;
        float t = Math.clamp((float) ((double) (now - started) / duration), 0, 1);
        return t * t * (3 - 2 * t);
    }

    float distance(long now) {
        return Mth.lerp(progress(now), fromDistance, toDistance);
    }

    boolean finished(long now) {
        return duration == 0 || now - started >= duration;
    }

    static float yaw(float from, float to, float progress) {
        return from + Mth.wrapDegrees(to - from) * progress;
    }
}
