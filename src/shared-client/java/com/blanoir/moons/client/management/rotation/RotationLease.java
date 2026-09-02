package com.blanoir.moons.client.management.rotation;

import net.minecraft.util.Mth;

/**
 * Global arbitration for every producer that changes server-facing rotation.
 *
 * <p>Contract:
 * <ul>
 *     <li>One producer owns packet yaw/pitch at a time.</li>
 *     <li>A higher priority may preempt an ordinary request.</li>
 *     <li>A pinned interaction window may not be preempted at any priority.</li>
 *     <li>Only an actually emitted movement angle may confirm a request.</li>
 *     <li>Every producer must release its lease after return/reset.</li>
 * </ul>
 */
public final class RotationLease {
    public static final int PRIORITY_CONTINUOUS_COMBAT = 50;
    public static final int PRIORITY_BLOCK_INTERACTION = 100;

    private static RotationLease holder;

    private final String owner;
    private final int priority;
    private RotationRequest request;
    private boolean confirmed;
    private boolean pinned;

    public RotationLease(String owner, int priority) {
        this.owner = owner;
        this.priority = priority;
    }

    public synchronized boolean acquire(RotationRequest request) {
        if (request == null) return false;
        synchronized (RotationLease.class) {
            if (holder != null && holder != this
                    && (holder.pinned || holder.priority >= priority)) return false;
            if (holder != null && holder != this) holder.clearLocal();
            holder = this;
        }
        this.request = request;
        confirmed = false;
        return true;
    }

    public synchronized boolean confirm(float sentYaw, float sentPitch) {
        if (!active() || confirmed) return false;
        if (Math.abs(Mth.wrapDegrees(request.yaw() - sentYaw)) > request.tolerance()
                || Math.abs(request.pitch() - sentPitch) > request.tolerance()) return false;
        confirmed = true;
        request.onConfirmed().run();
        return true;
    }

    public synchronized boolean active() { return holder == this && request != null; }
    public synchronized boolean confirmed() { return active() && confirmed; }
    public synchronized RotationRequest request() { return request; }
    public synchronized String owner() { return owner; }

    /** Prevents a USE/USE_ON transaction from being split across two owners. */
    public synchronized boolean pin() {
        if (!active()) return false;
        pinned = true;
        return true;
    }

    public synchronized void unpin() { pinned = false; }
    public synchronized boolean pinned() { return active() && pinned; }

    public synchronized void release() {
        synchronized (RotationLease.class) { if (holder == this) holder = null; }
        clearLocal();
    }

    public static synchronized boolean busyFor(RotationLease lease) {
        return holder != null && holder != lease
                && (holder.pinned || holder.priority >= lease.priority);
    }

    private void clearLocal() { request = null; confirmed = false; pinned = false; }
}
