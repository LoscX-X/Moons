package com.blanoir.moons.client.module.impl.player;

/** A phase may wait for acknowledgement, but changing phases never renews the whole cycle. */
final class AntiWebCycleGuard {
    private Object previousPhase;
    private int phaseTicks;
    private int totalTicks;

    boolean expired(Object phase, int smoothTicks, int holdTicks) {
        if (phase != previousPhase) {
            previousPhase = phase;
            phaseTicks = 0;
        }
        return ++totalTicks > 160 || ++phaseTicks > Math.max(30, smoothTicks + 20) + holdTicks;
    }

    void reset() {
        previousPhase = null;
        phaseTicks = 0;
        totalTicks = 0;
    }
}
