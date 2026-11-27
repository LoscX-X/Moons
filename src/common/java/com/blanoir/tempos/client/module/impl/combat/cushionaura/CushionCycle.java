package com.blanoir.tempos.client.module.impl.combat.cushionaura;

/** Confirmation and timing policy, independent of client prediction and render cadence. */
final class CushionCycle {
    enum Phase {
        SEARCH,
        TURN_PLACE,
        PLACE,
        WAIT_ENTITY,
        TURN_MOUNT,
        MOUNT,
        WAIT_RIDE,
        SEATED,
        DISMOUNT
    }

    private Phase phase = Phase.SEARCH;
    private long since;
    private int sinceTick;
    private boolean attackSent;

    Phase phase() {
        return phase;
    }

    void enter(Phase next, long now) {
        enter(next, now, 0);
    }

    void enter(Phase next, long now, int tick) {
        phase = next;
        since = now;
        sinceTick = tick;
        attackSent = false;
    }

    boolean elapsedTicks(int tick, int delay) {
        return ticksElapsed(tick, sinceTick, delay);
    }

    static boolean ticksElapsed(int tick, int start, int delay) {
        return tick - start >= delay;
    }

    void recordAttack() {
        if (phase == Phase.SEATED) attackSent = true;
    }

    boolean mayRelocate(int tick, int interval, boolean requireAttack) {
        return phase == Phase.SEATED
                && elapsedTicks(tick, interval)
                && (!requireAttack || attackSent);
    }

    boolean elapsed(long now, int milliseconds) {
        return now - since >= milliseconds * 1_000_000L;
    }

    boolean mayAttack(boolean ridingExpectedSeat, boolean serverMounted) {
        return phase == Phase.SEATED && ridingExpectedSeat && serverMounted;
    }

    boolean acknowledgeRide(boolean ridingExpectedSeat, boolean serverMounted, long now) {
        return acknowledgeRide(ridingExpectedSeat, serverMounted, now, 0);
    }

    boolean acknowledgeRide(boolean ridingExpectedSeat, boolean serverMounted, long now, int tick) {
        if (phase != Phase.WAIT_RIDE || !ridingExpectedSeat || !serverMounted) return false;
        enter(Phase.SEATED, now, tick);
        return true;
    }

    boolean acknowledgeDismount(boolean passenger, boolean serverMounted, long now) {
        if (phase != Phase.DISMOUNT || passenger || serverMounted) return false;
        enter(Phase.SEARCH, now);
        return true;
    }
}
