package com.blanoir.moons.client.management.rotation;

import static com.blanoir.moons.client.utils.math.MathUtils.approach;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Silent rotation shared by modules that must act without moving the local
 * first-person camera. The smooth angle is written into movement packets via
 * LocalPlayer send-position hook, so the server observes the player's body/head
 * turn. Keyboard input is direction-corrected instead of frozen, matching the
 * movement model used by SilentAura.
 *
 * <p>Modules own their lifecycle: beginRotation -&gt; (confirmed movement) -&gt;
 * invokeUseInPlayerUpdate -&gt; (use done) -&gt; beginReturnToCamera -&gt;
 * (returned callback) -&gt; reset. {@link #queueUse(Minecraft, BlockHitResult)}
 * remains only for compatibility with interaction paths that cannot run in
 * PLAYER_UPDATE. New automated placement must use the PLAYER_UPDATE path.
 * The modules' busy interlocks guarantee a single active user.
 */
public final class SilentPacketRotation {
    public enum Mode {
        INSTANT,
        SMOOTH
    }

    private static final double MAX_YAW_SPEED = 620.0D;
    private static final double MAX_PITCH_SPEED = 460.0D;
    private static final double MAX_YAW_ACCELERATION = 3_600.0D;
    private static final double MAX_PITCH_ACCELERATION = 2_700.0D;
    private static final double TICKS_PER_SECOND = 20.0D;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    private static final RotationLease ROTATION_LEASE =
            new RotationLease(
                    "SilentPacketRotation",
                    RotationLease.PRIORITY_BLOCK_INTERACTION,
                    SilentPacketRotation::shouldApplyRotation,
                    () -> packetRotation(Minecraft.getInstance()));
    private static final RotationInteractionLock USE_LOCK = new RotationInteractionLock();

    private static boolean active;
    private static boolean initialized;
    private static boolean holdingRotation;
    private static boolean returnToCamera;
    private static int smoothTicks;
    private static Runnable reachedAction;
    private static float packetYaw;
    private static float packetPitch;
    private static float yawVelocity;
    private static float pitchVelocity;
    private static float returnYawOffset;
    private static float returnPitchOffset;
    private static Vec3 rotationTarget;
    private static long rotationStartedAtNanos;
    private static long lastRotationFrameNanos;
    private static boolean rotationPacketSent;

    private static BlockHitResult simulatedUseHit;
    private static float simulatedUseYaw;
    private static float simulatedUsePitch;
    private static boolean simulatedUseQueued;
    private static boolean simulatedUseRunning;
    private static boolean simulatedUseCompleted;
    private static boolean invokingSimulatedUse;
    private static boolean deferredReset;
    private static Runnable pendingRotation;

    private SilentPacketRotation() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "SilentPacketRotation.context",
                event -> {
                    discard();
                });
        EventBus.FRAME.register("SilentPacketRotation.update", event -> update(event.client()));
        EventBus.PACKET_SEND_POST.register(
                "SilentPacketRotation.onPacketSent", SilentPacketRotation::onPacketSent);
    }

    private static void recordSentPacket(PacketSendEvent.Post event) {
        if (event.packet() instanceof ServerboundUseItemPacket useItemPacket
                && USE_LOCK.markInteractionPacket(
                        PacketAccess.useItemYaw(useItemPacket),
                        PacketAccess.useItemPitch(useItemPacket))) {
            return;
        }
        if (event.packet() instanceof ServerboundUseItemOnPacket
                && USE_LOCK.markInteractionPacket(simulatedUseYaw, simulatedUsePitch)) {
            return;
        }
        if (!(event.packet() instanceof ServerboundMovePlayerPacket)) {
            return;
        }
        RotationManager.Sent sent = RotationManager.latest();
        if (!RotationManager.observed(event.packet())) return;
        if (RotationManager.sentFor(ROTATION_LEASE, event.packet())) {
            markOutgoing(sent.yaw(), sent.pitch());
        }
        if (USE_LOCK.confirmMovement(sent.yaw(), sent.pitch())) {
            ROTATION_LEASE.unpin();
            if (deferredReset) completeDeferredReset();
        }
    }

    /**
     * Executes the queued vanilla block use only after the matching movement
     * rotation has actually left the connection. This preserves same-tick
     * placement while giving strict servers the order they validate:
     * ROTATION -> USE_ITEM_ON.
     */
    private static void onPacketSent(PacketSendEvent.Post event) {
        recordSentPacket(event);
        if (!(event.packet() instanceof ServerboundMovePlayerPacket)
                || !simulatedUseQueued
                || simulatedUseRunning
                || !rotationPacketSent
                || Float.floatToIntBits(getSentYaw()) != Float.floatToIntBits(simulatedUseYaw)
                || Float.floatToIntBits(getSentPitch())
                        != Float.floatToIntBits(simulatedUsePitch)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            invokeSimulatedUse(client);
        }
    }

    /** Smoothly rotates the packet yaw/pitch toward {@code target}. */
    public static boolean beginRotation(
            Minecraft client, Vec3 target, int smoothTicks, Runnable onReached) {
        return beginRotation(client, target, smoothTicks, Mode.SMOOTH, onReached);
    }

    public static boolean beginRotation(
            Minecraft client, Vec3 target, int smoothTicks, Mode mode, Runnable onReached) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null) {
            if (onReached != null) {
                onReached.run();
            }
            return false;
        }
        Rotation desired = RotationUtils.rotationTo(currentPlayer.getEyePosition(), target);
        if (USE_LOCK.locked()
                || RotationLease.submission() != null
                || !ROTATION_LEASE.acquire(
                        new RotationRequest(
                                desired.yaw(), desired.pitch(), smoothTicks, 0.35F, null))) {
            deferRotation(() -> beginRotation(client, target, smoothTicks, mode, onReached));
            return true;
        }
        pendingRotation = null;
        ROTATION_LEASE.cancelPending();
        Rotation start = RotationManager.start(client);
        packetYaw = start.yaw();
        packetPitch = start.pitch();
        active = true;
        holdingRotation = true;
        returnToCamera = false;
        rotationTarget = target;
        reachedAction = onReached;
        SilentPacketRotation.smoothTicks = Math.max(1, smoothTicks);
        rotationStartedAtNanos = System.nanoTime();
        lastRotationFrameNanos = 0L;
        rotationPacketSent = false;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        if (mode == Mode.INSTANT) {
            packetYaw = desired.yaw();
            packetPitch = desired.pitch();
            active = false;
            rotationPacketSent = false;
            reachedAction = null;
            if (onReached != null) {
                onReached.run();
            }
            return true;
        }
        update(client);
        return true;
    }

    /** Smoothly eases the packet RotationA back to the current camera angles. */
    public static void beginReturnToCamera(Minecraft client, int smoothTicks, Runnable onReturned) {
        beginReturnToCamera(client, smoothTicks, Mode.SMOOTH, onReturned);
    }

    public static void beginReturnToCamera(
            Minecraft client, int smoothTicks, Mode mode, Runnable onReturned) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) {
            if (onReturned != null) {
                onReturned.run();
            }
            reset();
            return;
        }
        if (USE_LOCK.locked()
                || RotationLease.submission() != null
                || !ROTATION_LEASE.acquire(
                        new RotationRequest(
                                currentPlayer.getYRot(),
                                currentPlayer.getXRot(),
                                smoothTicks,
                                0.35F,
                                null))) {
            deferRotation(() -> beginReturnToCamera(client, smoothTicks, mode, onReturned));
            return;
        }
        pendingRotation = null;
        ROTATION_LEASE.cancelPending();
        Rotation start = RotationManager.start(client);
        packetYaw = start.yaw();
        packetPitch = start.pitch();
        returnYawOffset = Mth.wrapDegrees(packetYaw - currentPlayer.getYRot());
        returnPitchOffset = packetPitch - currentPlayer.getXRot();
        active = true;
        holdingRotation = true;
        returnToCamera = true;
        rotationTarget = null;
        reachedAction = onReturned;
        SilentPacketRotation.smoothTicks = Math.max(1, smoothTicks);
        rotationStartedAtNanos = System.nanoTime();
        lastRotationFrameNanos = 0L;
        rotationPacketSent = false;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        if (mode == Mode.INSTANT) {
            packetYaw = currentPlayer.getYRot();
            packetPitch = currentPlayer.getXRot();
            returnYawOffset = 0.0F;
            returnPitchOffset = 0.0F;
            active = false;
            rotationPacketSent = false;
            reachedAction = null;
            if (onReturned != null) {
                onReturned.run();
            }
            return;
        }
        update(client);
    }

    /** Frame-rate-independent smoothstep, sampled by the render frame. */
    private static void update(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (pendingRotation != null) {
            // Fallback only. Normal continuation occurs at the closing motion
            // boundary, so multiple ticks in one frame do not introduce a gap.
            RotationLease.resumePending();
            return;
        }
        boolean followHeldTarget =
                !active
                        && holdingRotation
                        && !returnToCamera
                        && rotationTarget != null
                        && !simulatedUseQueued
                        && !simulatedUseRunning
                        && !USE_LOCK.locked();
        if ((!active && !followHeldTarget) || client == null || currentPlayer == null) {
            return;
        }

        long now = System.nanoTime();
        double deltaSeconds =
                lastRotationFrameNanos == 0L
                        ? 1.0D / 60.0D
                        : Mth.clamp(
                                (now - lastRotationFrameNanos) / NANOS_PER_SECOND,
                                1.0D / 1000.0D,
                                1.0D / 30.0D);
        lastRotationFrameNanos = now;

        double durationSeconds = smoothTicks / TICKS_PER_SECOND;
        if (returnToCamera) {
            // Decay the silent offset relative to the live camera. The camera
            // may keep moving, but the offset still reaches zero in a fixed
            // duration, so return can neither chase forever nor snap on timeout.
            double linear =
                    Mth.clamp(
                            (now - rotationStartedAtNanos) / (durationSeconds * NANOS_PER_SECOND),
                            0.0D,
                            1.0D);
            double progress = MathUtils.cubicSmoothStep(linear);
            double remaining = 1.0D - progress;
            packetYaw = currentPlayer.getYRot() + returnYawOffset * (float) remaining;
            packetPitch =
                    Mth.clamp(
                            currentPlayer.getXRot() + returnPitchOffset * (float) remaining,
                            -90.0F,
                            90.0F);
            yawVelocity = 0.0F;
            pitchVelocity = 0.0F;
            if (linear >= 1.0D) {
                packetYaw = currentPlayer.getYRot();
                packetPitch = currentPlayer.getXRot();
                rotationPacketSent = false;
                Runnable action = reachedAction;
                reachedAction = null;
                active = false;
                if (action != null) {
                    action.run();
                }
            }
            return;
        }

        Rotation target = RotationUtils.rotationTo(currentPlayer.getEyePosition(), rotationTarget);
        float yawDifference = Mth.wrapDegrees(target.yaw() - packetYaw);
        float pitchDifference = target.pitch() - packetPitch;
        // Match SilentAura's inertial model: accelerate from rest toward a
        // bounded desired speed. This avoids the large first exponential step
        // that made a short block interaction look like an instant snap.
        double response = 4.0D / Math.max(durationSeconds, 0.05D);
        float desiredYawVelocity =
                (float) Mth.clamp(yawDifference * response, -MAX_YAW_SPEED, MAX_YAW_SPEED);
        float desiredPitchVelocity =
                (float) Mth.clamp(pitchDifference * response, -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
        yawVelocity =
                approach(
                        yawVelocity,
                        desiredYawVelocity,
                        (float) (MAX_YAW_ACCELERATION * deltaSeconds));
        pitchVelocity =
                approach(
                        pitchVelocity,
                        desiredPitchVelocity,
                        (float) (MAX_PITCH_ACCELERATION * deltaSeconds));

        float yawStep = yawVelocity * (float) deltaSeconds;
        if (Math.signum(yawStep) == Math.signum(yawDifference)
                && Math.abs(yawStep) > Math.abs(yawDifference)) {
            yawStep = yawDifference;
            yawVelocity = 0.0F;
        }
        float pitchStep = pitchVelocity * (float) deltaSeconds;
        if (Math.signum(pitchStep) == Math.signum(pitchDifference)
                && Math.abs(pitchStep) > Math.abs(pitchDifference)) {
            pitchStep = pitchDifference;
            pitchVelocity = 0.0F;
        }
        packetYaw += yawStep;
        packetPitch = Mth.clamp(packetPitch + pitchStep, -90.0F, 90.0F);

        if (followHeldTarget) {
            // The player may jump or walk after reaching the original angle.
            // Continue following the fixed world point until the use click is
            // queued, so changing eye position cannot invalidate the pitch.
            if (Math.abs(Mth.wrapDegrees(target.yaw() - packetYaw)) <= 0.35F
                    && Math.abs(target.pitch() - packetPitch) <= 0.35F) {
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
            }
            return;
        }

        float remainingYaw = Math.abs(Mth.wrapDegrees(target.yaw() - packetYaw));
        float remainingPitch = Math.abs(target.pitch() - packetPitch);
        boolean minimumTimeElapsed =
                now - rotationStartedAtNanos >= durationSeconds * NANOS_PER_SECOND;
        if (minimumTimeElapsed && remainingYaw <= 0.35F && remainingPitch <= 0.35F) {
            packetYaw = target.yaw();
            packetPitch = target.pitch();
            yawVelocity = 0.0F;
            pitchVelocity = 0.0F;
            rotationPacketSent = false;
            Runnable action = reachedAction;
            reachedAction = null;
            active = false;
            if (action != null) {
                action.run();
            }
        }
    }

    /**
     * Queues one vanilla right-click in the current player-update phase. The
     * exact quantized pair is pinned first; sendPosition publishes it later in
     * the same tick and its SEND_POST then performs the interaction.
     */
    public static boolean queueUse(Minecraft client, BlockHitResult hit) {
        return prepareUse(client, hit);
    }

    /**
     * Runs the vanilla use path from PLAYER_UPDATE after an earlier movement
     * packet has published the requested rotation. This matches Scaffold's
     * accepted packet order: held-item sync -> use -> this tick's movement.
     */
    public static boolean invokeUseInPlayerUpdate(Minecraft client, BlockHitResult hit) {
        return invokeUseInPlayerUpdate(client, hit, true);
    }

    /**
     * Instant turns may use their prepared pair before this tick's movement.
     * Only pass false after an instant rotation has reached its target; the
     * existing interaction transaction preserves that pair for sendPosition.
     */
    public static boolean invokeUseInPlayerUpdate(
            Minecraft client, BlockHitResult hit, boolean requirePreviousRotation) {
        if ((requirePreviousRotation && !rotationPacketSent) || !prepareUse(client, hit)) {
            return false;
        }
        invokeSimulatedUse(client);
        return simulatedUseCompleted;
    }

    private static void invokeSimulatedUse(Minecraft client) {
        boolean previousInvocation = invokingSimulatedUse;
        invokingSimulatedUse = true;
        try {
            GameAccess.invokeStartUseItem(client);
        } finally {
            invokingSimulatedUse = previousInvocation;
        }
    }

    /** A queued placement alone must never authorize a physical right-click. */
    public static boolean isInvokingSimulatedUse() {
        return invokingSimulatedUse;
    }

    private static boolean prepareUse(Minecraft client, BlockHitResult hit) {
        if (client == null
                || client.player == null
                || hit == null
                || !ROTATION_LEASE.active()
                || simulatedUseQueued
                || simulatedUseRunning) {
            return false;
        }
        simulatedUseHit = hit;
        simulatedUseYaw = getInteractionYaw(client);
        simulatedUsePitch = getInteractionPitch(client);
        USE_LOCK.begin(simulatedUseYaw, simulatedUsePitch);
        if (!ROTATION_LEASE.pin()) {
            USE_LOCK.clear();
            return false;
        }
        packetYaw = simulatedUseYaw;
        packetPitch = simulatedUsePitch;
        simulatedUseCompleted = false;
        simulatedUseQueued = true;
        return true;
    }

    public static boolean isUseDone() {
        return simulatedUseCompleted && !USE_LOCK.locked();
    }

    /** Native startUseItem returned; a following movement tick may still be pending. */
    public static boolean isUseInvocationDone() {
        return simulatedUseCompleted;
    }

    /** Camera-aligned fast path; only actual matching history can mark it ready. */
    public static void markCurrentAsSent(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) {
            return;
        }
        packetYaw = currentPlayer.getYRot();
        packetPitch = currentPlayer.getXRot();
        if (!ROTATION_LEASE.acquire(new RotationRequest(packetYaw, packetPitch, 1, 0.35F, null)))
            return;
        RotationManager.Sent sent = RotationManager.latest();
        rotationPacketSent =
                sent.valid()
                        && RotationManager.same(
                                new Rotation(packetYaw, packetPitch), sent.rotation());
        holdingRotation = true;
    }

    /** Handles a real send attributed to this request by RotationManager. */
    private static void markOutgoing(float yaw, float pitch) {
        if (active || holdingRotation) {
            // Freeze on the exact float pair that the server will compare with the
            // following USE_ITEM packet; also marks the return packet as sent.
            packetYaw = yaw;
            packetPitch = pitch;
            rotationPacketSent = !active;
        }
    }

    public static boolean shouldApplyRotation() {
        // Keep submitting the reached angle through the interaction. Previously
        // update() set active=false before one final packet could be confirmed,
        // leaving AntiWeb permanently in WAITING_FOR_PLACE_ROTATION.
        return (active || holdingRotation || USE_LOCK.locked()) && ROTATION_LEASE.active();
    }

    public static boolean shouldCorrectMovement() {
        return shouldApplyRotation();
    }

    /** Includes accepted requests waiting for a pinned interaction to finish. */
    public static boolean isBusy() {
        return pendingRotation != null || shouldApplyRotation();
    }

    public static float getYaw() {
        return USE_LOCK.locked() ? USE_LOCK.yaw() : packetYaw;
    }

    public static float getPitch() {
        return USE_LOCK.locked() ? USE_LOCK.pitch() : packetPitch;
    }

    /** The movement hook must bypass a second quantization while this is true. */
    public static boolean isUseRotationLocked() {
        return USE_LOCK.locked();
    }

    public static float getSentYaw() {
        return RotationManager.latest().yaw();
    }

    public static float getSentPitch() {
        return RotationManager.latest().pitch();
    }

    /** Quantized pair that the next movement packet will publish. */
    public static float getInteractionYaw(Minecraft client) {
        return packetRotation(client).yaw();
    }

    public static float getInteractionPitch(Minecraft client) {
        return packetRotation(client).pitch();
    }

    /** Shared action/movement result; sampling cannot advance send history. */
    public static Rotation packetRotation(Minecraft client) {
        Rotation result =
                ROTATION_LEASE.commit(new Rotation(getYaw(), getPitch()), USE_LOCK.locked(), true);
        return result != null ? result : RotationManager.start(client);
    }

    public static Vec3 getInteractionLookVector(Minecraft client) {
        return Vec3.directionFromRotation(getInteractionPitch(client), getInteractionYaw(client));
    }

    private static double mouseSensitivityGcd() {
        return RotationQuantizer.mouseStep();
    }

    /**
     * Random ±1..2 mouse-GCD steps. Applied to every scaffold-published
     * rotation to vary consecutive rotation deltas (repetition is still possible)
     * while every angle stays mouse-reachable.
     */
    public static float packetRotationJitter() {
        double gcd = mouseSensitivityGcd();
        if (!Double.isFinite(gcd) || gcd <= 1.0E-7D) {
            return RandomMath.nextBoolean() ? 0.02F : -0.02F;
        }
        int steps = RandomMath.betweenInclusive(1, 2);
        return (RandomMath.nextBoolean() ? steps : -steps) * (float) gcd;
    }

    /** Matches vanilla mouse increments at packet frequency. */
    public static float quantizePacketYaw(float lastSentYaw, float desiredYaw) {
        return RotationQuantizer.yaw(lastSentYaw, desiredYaw);
    }

    public static float quantizePacketPitch(float lastSentPitch, float desiredPitch) {
        return RotationQuantizer.pitch(lastSentPitch, desiredPitch);
    }

    public static boolean isRotationPacketSent() {
        return rotationPacketSent;
    }

    public static float getMovementYaw() {
        return packetRotation(Minecraft.getInstance()).yaw();
    }

    /** Called by the startUseItem hook for one queued vanilla right-click. */
    public static boolean beginSimulatedUse(Minecraft client) {
        if (!invokingSimulatedUse
                || !simulatedUseQueued
                || simulatedUseRunning
                || client == null
                || client.player == null
                || simulatedUseHit == null) {
            return false;
        }
        simulatedUseQueued = false;
        simulatedUseRunning = true;
        return true;
    }

    public static BlockHitResult getSimulatedUseHit() {
        return simulatedUseHit;
    }

    public static float getSimulatedUseYaw() {
        return simulatedUseYaw;
    }

    public static float getSimulatedUsePitch() {
        return simulatedUsePitch;
    }

    public static void finishSimulatedUse() {
        if (!simulatedUseRunning) {
            return;
        }
        simulatedUseRunning = false;
        simulatedUseCompleted = true;
        boolean packetSent = USE_LOCK.interactionPacketSent();
        USE_LOCK.finishInvocation();
        if (!packetSent) {
            // startUseItem may have returned before using an item (busy hand,
            // cooldown, etc.). No USE_ITEM reached the wire, hence there is no
            // interaction window to close and the module may confirm failure normally.
            ROTATION_LEASE.unpin();
            if (deferredReset) completeDeferredReset();
            RotationLease.resumePending();
        }
    }

    private static void deferRotation(Runnable continuation) {
        pendingRotation = continuation;
        ROTATION_LEASE.whenAvailable(SilentPacketRotation::resumeRotation);
    }

    private static void resumeRotation() {
        Runnable continuation = pendingRotation;
        pendingRotation = null;
        if (continuation != null) continuation.run();
    }

    private static void completeDeferredReset() {
        // A request accepted AFTER reset belongs to the next operation. Closing
        // the old transaction must not silently discard that new operation.
        Runnable next = pendingRotation;
        reset();
        if (next != null) deferRotation(next);
    }

    public static void reset() {
        pendingRotation = null;
        ROTATION_LEASE.cancelPending();
        if (USE_LOCK.locked()) {
            deferredReset = true;
            active = false;
            holdingRotation = true;
            returnToCamera = false;
            reachedAction = null;
            rotationTarget = null;
            packetYaw = USE_LOCK.yaw();
            packetPitch = USE_LOCK.pitch();
            return;
        }
        active = false;
        holdingRotation = false;
        returnToCamera = false;
        smoothTicks = 0;
        reachedAction = null;
        packetYaw = 0.0F;
        packetPitch = 0.0F;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        returnYawOffset = 0.0F;
        returnPitchOffset = 0.0F;
        rotationTarget = null;
        rotationStartedAtNanos = 0L;
        lastRotationFrameNanos = 0L;
        rotationPacketSent = false;
        simulatedUseHit = null;
        simulatedUseYaw = 0.0F;
        simulatedUsePitch = 0.0F;
        simulatedUseQueued = false;
        simulatedUseRunning = false;
        simulatedUseCompleted = false;
        deferredReset = false;
        pendingRotation = null;
        USE_LOCK.clear();
        ROTATION_LEASE.release();
    }

    /** Context loss/unload cannot wait for a closing packet from the old connection. */
    public static void discard() {
        ROTATION_LEASE.cancelPending();
        pendingRotation = null;
        USE_LOCK.clear();
        ROTATION_LEASE.unpin();
        reset();
    }
}
