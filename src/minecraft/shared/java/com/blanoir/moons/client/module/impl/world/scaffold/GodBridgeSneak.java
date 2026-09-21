package com.blanoir.moons.client.module.impl.world.scaffold;

import java.util.function.IntSupplier;

/** Two-block diagonal cadence with minimum-duration protection at exposed edges. */
public final class GodBridgeSneak {
    private boolean sneaking;
    private int sampledTick = Integer.MIN_VALUE;
    private long releaseAtNanos;
    private int diagonalPlacements;
    private boolean pairPending;

    public void placed(boolean diagonal) {
        if (!diagonal) {
            clearPair();
        } else if (++diagonalPlacements >= 2) {
            diagonalPlacements = 0;
            pairPending = true;
        }
    }

    public void clearPair() {
        diagonalPlacements = 0;
        pairPending = false;
    }

    public static boolean diagonalMovement(float cameraYaw, float forward, float sideways) {
        if (forward == 0 && sideways == 0) return false;
        double heading = cameraYaw - Math.toDegrees(Math.atan2(sideways, forward));
        return Math.floorMod(Math.round(heading / 45.0), 2) == 1;
    }

    public boolean update(int tick, long nowNanos, boolean edge, IntSupplier durationMs) {
        if (tick == sampledTick) return sneaking;
        if (tick < sampledTick) reset();
        sampledTick = tick;
        if (pairPending || edge && !sneaking) {
            sneaking = true;
            releaseAtNanos = nowNanos + Math.max(0, durationMs.getAsInt()) * 1_000_000L;
            pairPending = false;
        }
        if (!edge && sneaking && nowNanos - releaseAtNanos >= 0) sneaking = false;
        return sneaking;
    }

    public boolean sneaking() {
        return sneaking;
    }

    public void reset() {
        sneaking = false;
        sampledTick = Integer.MIN_VALUE;
        releaseAtNanos = 0;
        clearPair();
    }
}
