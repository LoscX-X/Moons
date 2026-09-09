package com.blanoir.moons.client.module.impl.network.backtrack;

/** Ordered deadlines without an additional per-packet pacing delay. */
final class BacktrackTiming {
    private BacktrackTiming() {}

    static long deadline(long arrivalMs, int delayMs, long previousDeadlineMs) {
        return Math.max(arrivalMs + Math.max(0, delayMs), previousDeadlineMs);
    }

    static boolean due(long nowMs, long releaseAtMs) {
        return nowMs >= releaseAtMs;
    }
}
