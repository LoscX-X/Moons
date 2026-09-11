package com.blanoir.moons.client.management.lease;

import com.blanoir.moons.client.management.rotation.RotationManager;
import com.blanoir.moons.client.management.rotation.RotationQuantizer;
import com.blanoir.moons.client.management.rotation.RotationRequest;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
    public static final int PRIORITY_SCRIPT_PLACEMENT = 20;

    private static RotationLease holder;
    private static Submission submission;
    private static Rotation manual;
    private static final Map<RotationLease, Runnable> continuations = new LinkedHashMap<>();
    private static boolean resuming;
    private static boolean motionWindow;
    private static int packetObservers;
    private static long window;

    /** Final result used by an action/movement window; never a send acknowledgement. */
    public record Submission(
            RotationLease lease,
            long generation,
            long requestId,
            Rotation rotation,
            boolean correctMovement) {}

    private final String owner;
    private final int priority;
    private final BooleanSupplier outputReady;
    private final BooleanSupplier movementCorrection;
    private final Runnable publishOutput;
    private RotationRequest request;
    private boolean confirmed;
    private boolean pinned;
    private boolean releasePending;
    private long generation;
    private long requestId;
    private Rotation start;

    public RotationLease(String owner, int priority) {
        this(owner, priority, () -> false, () -> {});
    }

    /** The owner supplies its trajectory; consumers never need to inspect feature classes. */
    public RotationLease(
            String owner, int priority, BooleanSupplier outputReady, Runnable publishOutput) {
        this(owner, priority, outputReady, () -> true, publishOutput);
    }

    public RotationLease(
            String owner,
            int priority,
            BooleanSupplier outputReady,
            BooleanSupplier movementCorrection,
            Runnable publishOutput) {
        this.owner = owner;
        this.priority = priority;
        this.outputReady = java.util.Objects.requireNonNull(outputReady);
        this.movementCorrection = java.util.Objects.requireNonNull(movementCorrection);
        this.publishOutput = java.util.Objects.requireNonNull(publishOutput);
    }

    public boolean acquire(RotationRequest request) {
        return acquire(request, RotationManager.start(Minecraft.getInstance()), ignored -> {});
    }

    /** The initializer runs once per acquisition, before the new producer computes its trajectory. */
    public boolean acquire(
            RotationRequest request, Rotation camera, Consumer<Rotation> initialize) {
        synchronized (RotationManager.class) {
            if (request == null || manual != null) return false;
            if (submission != null && submission.lease() != this) return false;
            if (holder != null && holder != this && (holder.pinned || holder.priority >= priority))
                return false;
            boolean acquired = holder != this || this.request == null;
            if (holder != null && holder != this) holder.clearLocal();
            holder = this;
            this.request = request;
            confirmed = false;
            requestId++;
            if (acquired) {
                generation++;
                start = RotationManager.start(camera);
                try {
                    initialize.accept(start);
                } catch (RuntimeException | Error failure) {
                    holder = null;
                    clearLocal();
                    throw failure;
                }
            }
            return true;
        }
    }

    public void confirm(float sentYaw, float sentPitch) {
        if (!active() || confirmed) return;
        if (Math.abs(Mth.wrapDegrees(request.yaw() - sentYaw)) > request.tolerance()
                || Math.abs(request.pitch() - sentPitch) > request.tolerance()) return;
        confirmed = true;
        request.onConfirmed().run();
    }

    public boolean active() {
        synchronized (RotationManager.class) {
            return holder == this && request != null;
        }
    }

    public boolean confirmed() {
        synchronized (RotationManager.class) {
            return active() && confirmed;
        }
    }

    public RotationRequest request() {
        synchronized (RotationManager.class) {
            return request;
        }
    }

    public String owner() {
        return owner;
    }

    /** Commit once. Later readers, release requests and competing owners cannot replace it. */
    public Rotation commit(Rotation candidate, boolean exact, boolean correctMovement) {
        synchronized (RotationManager.class) {
            if (!active() || manual != null) return null;
            if (submission != null)
                return submission.lease() == this ? submission.rotation() : null;
            Rotation base = RotationManager.start(start);
            Rotation result =
                    exact
                            ? candidate
                            : new Rotation(
                                    RotationQuantizer.yaw(base.yaw(), candidate.yaw()),
                                    RotationQuantizer.pitch(base.pitch(), candidate.pitch()));
            submission = new Submission(this, generation, requestId, result, correctMovement);
            return result;
        }
    }

    public static Submission submission() {
        synchronized (RotationManager.class) {
            return submission;
        }
    }

    /** Resolve only the arbitrated owner, committing its tick/action candidate once. */
    public static Submission resolveSubmission(boolean forMovement) {
        synchronized (RotationManager.class) {
            if (submission == null
                    && manual == null
                    && holder != null
                    && holder.outputReady.getAsBoolean()
                    && (!forMovement || holder.movementCorrection.getAsBoolean())) {
                holder.publishOutput.run();
            }
            return submission;
        }
    }

    public static long window() {
        synchronized (RotationManager.class) {
            return window;
        }
    }

    /** Covers acquisition, turning, interaction, return and deferred release. */
    public static boolean hasSilentRotation() {
        synchronized (RotationManager.class) {
            return holder != null || submission != null;
        }
    }

    /** sendPosition completed (possibly cancelled/no packet); history is updated separately. */
    public static void finishMotion() {
        synchronized (RotationManager.class) {
            motionWindow = false;
            RotationLease previous = submission == null ? null : submission.lease();
            submission = null;
            window++;
            if (previous != null && previous.releasePending && !previous.pinned) previous.release();
            resumePending();
        }
    }

    /**
     * Queue preparation after a blocked acquisition/phase change. One latest
     * continuation per producer; reset must cancel it. It may prepare rotation,
     * but actions still belong to their original PLAYER_UPDATE phase.
     */
    public void whenAvailable(Runnable continuation) {
        synchronized (RotationManager.class) {
            continuations.put(this, java.util.Objects.requireNonNull(continuation));
            resumePending();
        }
    }

    public static void beginMotion() {
        synchronized (RotationManager.class) {
            motionWindow = true;
        }
    }

    public static void beginPacketObservation() {
        synchronized (RotationManager.class) {
            packetObservers++;
        }
    }

    public static void endPacketObservation() {
        synchronized (RotationManager.class) {
            packetObservers--;
        }
    }

    public void cancelPending() {
        synchronized (RotationManager.class) {
            continuations.remove(this);
        }
    }

    /** Runs ready preparation outside committed, temporary-camera and packet-observer windows. */
    public static void resumePending() {
        synchronized (RotationManager.class) {
            if (resuming
                    || motionWindow
                    || packetObservers != 0
                    || submission != null
                    || manual != null
                    || continuations.isEmpty()) return;
            resuming = true;
            try {
                var ready = new ArrayList<>(continuations.keySet());
                ready.sort(
                        Comparator.comparingInt((RotationLease lease) -> lease.priority)
                                .reversed());
                for (RotationLease lease : ready) {
                    if (submission != null || manual != null) break;
                    if (holder != null
                            && (holder.pinned
                                    || holder != lease && holder.priority >= lease.priority))
                        continue;
                    Runnable continuation = continuations.remove(lease);
                    if (continuation == null) continue;
                    try {
                        continuation.run();
                    } catch (RuntimeException failure) {
                        // One producer failure must not strand other already-ready work.
                        System.err.println(
                                "[RotationLease] Continuation failed for " + lease.owner);
                        failure.printStackTrace(System.err);
                    }
                }
            } finally {
                resuming = false;
            }
        }
    }

    public boolean matches(Submission value) {
        return active()
                && value == submission
                && value.generation() == generation
                && value.requestId() == requestId;
    }

    /** The manual USE_ITEM floats belong to its closing movement, even if Aura is disabled. */
    public static boolean holdManual(Rotation rotation) {
        synchronized (RotationManager.class) {
            if (holder != null && holder.pinned
                    || submission != null && !RotationManager.same(submission.rotation(), rotation))
                return false;
            if (manual != null && !RotationManager.same(manual, rotation)) return false;
            manual = rotation;
            return true;
        }
    }

    public static Rotation manualRotation() {
        synchronized (RotationManager.class) {
            return manual;
        }
    }

    public static void confirmManual(Rotation rotation) {
        if (manual != null && RotationManager.same(manual, rotation)) manual = null;
    }

    /** Prevents a USE/USE_ON transaction from being split across two owners. */
    public boolean pin() {
        synchronized (RotationManager.class) {
            if (!active()) return false;
            pinned = true;
            return true;
        }
    }

    public void unpin() {
        synchronized (RotationManager.class) {
            pinned = false;
            if (releasePending) release();
        }
    }

    public boolean pinned() {
        synchronized (RotationManager.class) {
            return active() && pinned;
        }
    }

    public void release() {
        synchronized (RotationManager.class) {
            if (pinned || submission != null && submission.lease() == this) {
                releasePending = true;
                return;
            }
            if (holder == this) holder = null;
            clearLocal();
            // A release in PLAYER_UPDATE must not make the next producer wait
            // until that tick's movement has already passed. Packet observers
            // and temporary-camera windows are explicitly excluded above.
            resumePending();
        }
    }

    public static boolean busyFor(RotationLease lease) {
        synchronized (RotationManager.class) {
            return manual != null
                    || submission != null && submission.lease() != lease
                    || holder != null
                            && holder != lease
                            && (holder.pinned || holder.priority >= lease.priority);
        }
    }

    public static void resetAll() {
        synchronized (RotationManager.class) {
            if (holder != null) holder.clearLocal();
            holder = null;
            submission = null;
            manual = null;
            continuations.clear();
            motionWindow = false;
            window++;
        }
    }

    private void clearLocal() {
        request = null;
        confirmed = pinned = releasePending = false;
        start = null;
        generation++;
    }
}
