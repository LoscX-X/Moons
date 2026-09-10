package com.blanoir.moons.client.module.impl.network.backtrack;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/** Approach grace, motion sampling and teleport rejection, independent of packet/render APIs. */
final class BacktrackWindow {
    private static final int APPROACH_GRACE_TICKS = 3;
    private static final int MOTION_HISTORY_TICKS = 5;
    private static final double MAX_DISPLACEMENT_SQUARED = 25.0;
    private static final double DISTANCE_EPSILON = 1.0E-4;

    private final ArrayDeque<Sample> motion = new ArrayDeque<>();
    private int resumeTick;

    boolean motionReady(int tick) {
        return tick >= resumeTick;
    }

    double speed() {
        if (motion.size() < 2) return 0;
        Sample first = motion.getFirst();
        Sample last = motion.getLast();
        int ticks = last.tick() - first.tick();
        return ticks <= 0 ? 0 : first.position().distanceTo(last.position()) / (ticks * .05);
    }

    void reset() {
        resumeTick = 0;
        motion.clear();
    }

    void seed(Vec3 position, int tick) {
        motion.clear();
        motion.addLast(new Sample(tick, position));
    }

    Decision observe(
            Vec3 previous,
            Vec3 current,
            int tick,
            double previousDistanceSquared,
            double realDistanceSquared,
            double visibleDistanceSquared) {
        if (!finite(current) || previous.distanceToSqr(current) > MAX_DISPLACEMENT_SQUARED)
            return Decision.RESET;
        while (!motion.isEmpty() && tick - motion.getFirst().tick() > MOTION_HISTORY_TICKS)
            motion.removeFirst();
        if (!motion.isEmpty()
                && motion.getFirst().position().distanceToSqr(current) > MAX_DISPLACEMENT_SQUARED)
            return Decision.RESET;
        // At most one endpoint per tick, plus the starting position for this tick.
        if (motion.size() > 1 && motion.getLast().tick() == tick) motion.removeLast();
        motion.addLast(new Sample(tick, current));

        if (previous.distanceToSqr(current) >= DISTANCE_EPSILON
                && realDistanceSquared + DISTANCE_EPSILON < previousDistanceSquared) {
            resumeTick = tick + APPROACH_GRACE_TICKS;
            return Decision.RELEASE;
        }
        return useful(realDistanceSquared, visibleDistanceSquared)
                ? Decision.HOLD
                : Decision.RELEASE;
    }

    static boolean useful(double realDistanceSquared, double visibleDistanceSquared) {
        return realDistanceSquared > visibleDistanceSquared + DISTANCE_EPSILON;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    enum Decision {
        HOLD,
        RELEASE,
        RESET
    }

    private record Sample(int tick, Vec3 position) {}
}
