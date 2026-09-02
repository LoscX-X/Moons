package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
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
    private static final RotationLease ROTATION_LEASE = new RotationLease(
            "SilentPacketRotation", RotationLease.PRIORITY_BLOCK_INTERACTION);
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
    private static float sentYaw;
    private static float sentPitch;
    private static boolean sentRotationValid;

    private static BlockHitResult simulatedUseHit;
    private static float simulatedUseYaw;
    private static float simulatedUsePitch;
    private static boolean simulatedUseQueued;
    private static boolean simulatedUseRunning;
    private static boolean simulatedUseCompleted;
    private static boolean deferredReset;
    private static PendingRotation pendingRotation;

    private SilentPacketRotation() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.FRAME.register("SilentPacketRotation.update",
                event -> update(event.client()));
        // Registered after PacketEventRouter: cancelled/queued FakeLag
        // packets stop propagation and are not mistaken for sent movement.
        EventBus.PACKET_SEND_PRE.register(
                "SilentPacketRotation.onPacketSend", SilentPacketRotation::onPacketSend);
        EventBus.PACKET_SEND_POST.register(
                "SilentPacketRotation.onPacketSent", SilentPacketRotation::onPacketSent);
    }

    private static void onPacketSend(PacketSendEvent.Pre event) {
        if (event.packet() instanceof ServerboundUseItemPacket useItemPacket
                && USE_LOCK.markInteractionPacket(
                useItemPacket.getYRot(), useItemPacket.getXRot())) {
            return;
        }
        if (event.packet() instanceof ServerboundUseItemOnPacket
                && USE_LOCK.markInteractionPacket(
                simulatedUseYaw, simulatedUsePitch)) {
            return;
        }
        if (!(event.packet() instanceof ServerboundMovePlayerPacket movement)
                || event.isCancelled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        float fallbackYaw = sentRotationValid
                ? sentYaw
                : client != null && client.player != null
                ? client.player.getYRot() : packetYaw;
        float fallbackPitch = sentRotationValid
                ? sentPitch
                : client != null && client.player != null
                ? client.player.getXRot() : packetPitch;
        markOutgoing(
                movement.getYRot(fallbackYaw),
                movement.getXRot(fallbackPitch));
    }

    /**
     * Executes the queued vanilla block use only after the matching movement
     * rotation has actually left the connection. This preserves same-tick
     * placement while giving strict servers the order they validate:
     * ROTATION -> USE_ITEM_ON.
     */
    private static void onPacketSent(PacketSendEvent.Post event) {
        if (!(event.packet() instanceof ServerboundMovePlayerPacket)
                || !simulatedUseQueued || simulatedUseRunning
                || !rotationPacketSent
                || Float.floatToIntBits(sentYaw) != Float.floatToIntBits(simulatedUseYaw)
                || Float.floatToIntBits(sentPitch) != Float.floatToIntBits(simulatedUsePitch)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            GameAccess.invokeStartUseItem(client);
        }
    }

    /** Smoothly rotates the packet yaw/pitch toward {@code target}. */
    public static boolean beginRotation(
            Minecraft client,
            Vec3 target,
            int smoothTicks,
            Runnable onReached
    ) {
        return beginRotation(client, target, smoothTicks, Mode.SMOOTH, onReached);
    }

    public static boolean beginRotation(
            Minecraft client,
            Vec3 target,
            int smoothTicks,
            Mode mode,
            Runnable onReached
    ) {
        if (client == null || client.player == null || target == null) {
            if (onReached != null) {
                onReached.run();
            }
            return false;
        }
        Rotation desired = RotationUtils.rotationTo(client.player.getEyePosition(), target);
        if (USE_LOCK.locked() || !ROTATION_LEASE.acquire(new RotationRequest(
                desired.yaw(), desired.pitch(), smoothTicks, 0.35F, null))) {
            // A pinned higher-priority interaction lasts until its exact
            // movement confirmation. Accept this request and retry it from the
            // frame loop instead of leaving the caller stuck in a turning phase.
            pendingRotation = new PendingRotation(
                    target, smoothTicks, mode, onReached);
            return true;
        }
        pendingRotation = null;
        packetYaw = sentRotationValid ? sentYaw : client.player.getYRot();
        packetPitch = sentRotationValid ? sentPitch : client.player.getXRot();
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
    public static void beginReturnToCamera(
            Minecraft client,
            int smoothTicks,
            Runnable onReturned
    ) {
        beginReturnToCamera(client, smoothTicks, Mode.SMOOTH, onReturned);
    }

    public static void beginReturnToCamera(
            Minecraft client,
            int smoothTicks,
            Mode mode,
            Runnable onReturned
    ) {
        if (client == null || client.player == null) {
            if (onReturned != null) {
                onReturned.run();
            }
            reset();
            return;
        }
        if (USE_LOCK.locked()) return;
        if (!ROTATION_LEASE.acquire(new RotationRequest(
                client.player.getYRot(), client.player.getXRot(),
                smoothTicks, 0.35F, null))) return;
        packetYaw = sentRotationValid ? sentYaw : packetYaw;
        packetPitch = sentRotationValid ? sentPitch : packetPitch;
        returnYawOffset = Mth.wrapDegrees(packetYaw - client.player.getYRot());
        returnPitchOffset = packetPitch - client.player.getXRot();
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
            packetYaw = client.player.getYRot();
            packetPitch = client.player.getXRot();
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
        if (pendingRotation != null) {
            PendingRotation pending = pendingRotation;
            pendingRotation = null;
            beginRotation(client, pending.target(), pending.smoothTicks(),
                    pending.mode(), pending.onReached());
            return;
        }
        boolean followHeldTarget = !active
                && holdingRotation
                && !returnToCamera
                && rotationTarget != null
                && !simulatedUseQueued
                && !simulatedUseRunning
                && !USE_LOCK.locked();
        if ((!active && !followHeldTarget) || client == null || client.player == null) {
            return;
        }

        long now = System.nanoTime();
        double deltaSeconds = lastRotationFrameNanos == 0L
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
            double linear = Mth.clamp(
                    (now - rotationStartedAtNanos)
                            / (durationSeconds * NANOS_PER_SECOND),
                    0.0D,
                    1.0D);
            double progress = linear * linear * (3.0D - 2.0D * linear);
            double remaining = 1.0D - progress;
            packetYaw = client.player.getYRot()
                    + returnYawOffset * (float) remaining;
            packetPitch = Mth.clamp(
                    client.player.getXRot()
                            + returnPitchOffset * (float) remaining,
                    -90.0F,
                    90.0F);
            yawVelocity = 0.0F;
            pitchVelocity = 0.0F;
            if (linear >= 1.0D) {
                packetYaw = client.player.getYRot();
                packetPitch = client.player.getXRot();
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

        Rotation target = RotationUtils.rotationTo(client.player.getEyePosition(), rotationTarget);
        float yawDifference = Mth.wrapDegrees(target.yaw() - packetYaw);
        float pitchDifference = target.pitch() - packetPitch;
        // Match SilentAura's inertial model: accelerate from rest toward a
        // bounded desired speed. This avoids the large first exponential step
        // that made a short block interaction look like an instant snap.
        double response = 4.0D / Math.max(durationSeconds, 0.05D);
        float desiredYawVelocity = (float) Mth.clamp(
                yawDifference * response, -MAX_YAW_SPEED, MAX_YAW_SPEED);
        float desiredPitchVelocity = (float) Mth.clamp(
                pitchDifference * response, -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
        yawVelocity = approach(
                yawVelocity,
                desiredYawVelocity,
                (float) (MAX_YAW_ACCELERATION * deltaSeconds));
        pitchVelocity = approach(
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
        boolean minimumTimeElapsed = now - rotationStartedAtNanos
                >= durationSeconds * NANOS_PER_SECOND;
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

    private static float approach(float current, float target, float maxChange) {
        return current + Mth.clamp(target - current, -maxChange, maxChange);
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
    public static boolean invokeUseInPlayerUpdate(
            Minecraft client,
            BlockHitResult hit
    ) {
        if (!rotationPacketSent || !prepareUse(client, hit)) {
            return false;
        }
        GameAccess.invokeStartUseItem(client);
        return simulatedUseCompleted;
    }

    private static boolean prepareUse(Minecraft client, BlockHitResult hit) {
        if (client == null || client.player == null || hit == null
                || !ROTATION_LEASE.active()
                || simulatedUseQueued || simulatedUseRunning) {
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

    /** No-RotationA fast path: treat the current camera angles as already sent. */
    public static void markCurrentAsSent(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        packetYaw = client.player.getYRot();
        packetPitch = client.player.getXRot();
        if (!ROTATION_LEASE.acquire(new RotationRequest(
                packetYaw, packetPitch, 1, 0.35F, null))) return;
        sentYaw = quantizePacketYaw(packetYaw, packetYaw);
        sentPitch = quantizePacketPitch(packetPitch, packetPitch);
        sentRotationValid = true;
        rotationPacketSent = true;
        holdingRotation = true;
    }

    /** Records the exact quantized angles written to the movement packet. */
    public static void markOutgoing(float yaw, float pitch) {
        sentYaw = yaw;
        sentPitch = pitch;
        sentRotationValid = true;
        ROTATION_LEASE.confirm(yaw, pitch);
        if (active || holdingRotation) {
            // Freeze on the exact float pair that Grim will compare with the
            // following USE_ITEM packet; also marks the return packet as sent.
            packetYaw = yaw;
            packetPitch = pitch;
            rotationPacketSent = true;
        }
        if (USE_LOCK.confirmMovement(yaw, pitch)) {
            ROTATION_LEASE.unpin();
            if (deferredReset) reset();
        }
    }

    public static boolean shouldApplyRotation() {
        // Keep submitting the reached angle through the interaction. Previously
        // update() set active=false before one final packet could be confirmed,
        // leaving AntiWeb permanently in WAITING_FOR_PLACE_ROTATION.
        return (active || holdingRotation || USE_LOCK.locked())
                && ROTATION_LEASE.active();
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
        return sentYaw;
    }

    public static float getSentPitch() {
        return sentPitch;
    }

    public static Vec3 getSentLookVector() {
        return Vec3.directionFromRotation(sentPitch, sentYaw);
    }

    /** Quantized pair that the next movement packet will publish. */
    public static float getInteractionYaw(Minecraft client) {
        float base = sentRotationValid ? sentYaw
                : client != null && client.player != null
                ? client.player.getYRot() : packetYaw;
        return quantizePacketYaw(base, packetYaw);
    }

    public static float getInteractionPitch(Minecraft client) {
        float base = sentRotationValid ? sentPitch
                : client != null && client.player != null
                ? client.player.getXRot() : packetPitch;
        return quantizePacketPitch(base, packetPitch);
    }

    public static Vec3 getInteractionLookVector(Minecraft client) {
        return Vec3.directionFromRotation(
                getInteractionPitch(client), getInteractionYaw(client));
    }

    private static double mouseSensitivityGcd() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return 0.0D;
        }
        double sensitivity = client.options.sensitivity().get();
        double factor = sensitivity * 0.6D + 0.2D;
        double sensitivityFactor = factor * factor * factor * 8.0D;
        return (double) ((float) sensitivityFactor * 0.15F);
    }

    /**
     * Random ±1..2 mouse-GCD steps. Applied to every scaffold-published
     * rotation so two consecutive rotation deltas never repeat exactly
     * (Grim DuplicateRotPlace) while every angle stays mouse-reachable.
     */
    public static float packetRotationJitter() {
        double gcd = mouseSensitivityGcd();
        java.util.concurrent.ThreadLocalRandom random =
                java.util.concurrent.ThreadLocalRandom.current();
        if (!Double.isFinite(gcd) || gcd <= 1.0E-7D) {
            return random.nextBoolean() ? 0.02F : -0.02F;
        }
        int steps = random.nextInt(1, 3);
        return (random.nextBoolean() ? steps : -steps) * (float) gcd;
    }

    /** Matches vanilla mouse increments at packet frequency. */
    public static float quantizePacketYaw(float lastSentYaw, float desiredYaw) {
        double gcd = mouseSensitivityGcd();
        if (!Double.isFinite(gcd) || gcd <= 1.0E-7D) {
            return desiredYaw;
        }
        double difference = Mth.wrapDegrees(desiredYaw - lastSentYaw);
        return lastSentYaw + (float) (Math.round(difference / gcd) * gcd);
    }

    public static float quantizePacketPitch(float lastSentPitch, float desiredPitch) {
        double gcd = mouseSensitivityGcd();
        if (!Double.isFinite(gcd) || gcd <= 1.0E-7D) {
            return Mth.clamp(desiredPitch, -90.0F, 90.0F);
        }
        double difference = desiredPitch - lastSentPitch;
        return Mth.clamp(
                lastSentPitch + (float) (Math.round(difference / gcd) * gcd),
                -90.0F,
                90.0F);
    }

    public static boolean isRotationPacketSent() {
        return rotationPacketSent;
    }

    public static float getMovementYaw() {
        Minecraft client = Minecraft.getInstance();
        float baseYaw = sentRotationValid
                ? sentYaw
                : client != null && client.player != null
                ? client.player.getYRot() : packetYaw;
        return quantizePacketYaw(baseYaw, packetYaw);
    }

    /** Called by the startUseItem hook for one queued vanilla right-click. */
    public static boolean beginSimulatedUse(Minecraft client) {
        if (!simulatedUseQueued || simulatedUseRunning || client == null
                || client.player == null || simulatedUseHit == null) {
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

    public static void finishSimulatedUse(Minecraft client) {
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
            // Grim window to close and the module may confirm failure normally.
            ROTATION_LEASE.unpin();
            if (deferredReset) reset();
        }
    }

    public static void reset() {
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
        sentYaw = 0.0F;
        sentPitch = 0.0F;
        sentRotationValid = false;
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

    private record PendingRotation(
            Vec3 target,
            int smoothTicks,
            Mode mode,
            Runnable onReached
    ) {
    }

}
