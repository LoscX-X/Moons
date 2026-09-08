package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.RotationHistory;
import com.blanoir.moons.client.management.rotation.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationRequest;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Coordinates independent selector, rotation and attack components. */
public final class SilentAuraRuntime {
    private static final SilentAuraTargetRouter SELECTOR = new SilentAuraTargetRouter();
    private static final SilentAuraRotationRouter ROTATION = new SilentAuraRotationRouter();
    private static boolean initialized;
    private static String activeMode;
    private static boolean activeMatrix;
    private static SentRotation sent = SentRotation.invalid();
    private static final SilentAuraPacketRotationRouter PACKET_ROTATION =
            new SilentAuraPacketRotationRouter();
    private static final RotationLease ROTATION_LEASE =
            new RotationLease("SilentAura", RotationLease.PRIORITY_CONTINUOUS_COMBAT);

    private SilentAuraRuntime() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "SilentAuraRuntime.context", event -> reset(event.client()));
        EventBus.FRAME.register(
                "SilentAuraRuntime.frame", event -> frame(event.client(), event.deltaSeconds()));
        EventBus.PACKET_SEND_POST.register(
                "SilentAuraRuntime.rotationPacketTracker", SilentAuraRuntime::trackRotationPacket);
    }

    private static void frame(Minecraft client, double deltaSeconds) {
        if (!baseCanRun(client)) {
            reset(client);
            return;
        }
        if (!SilentAuraConfig.aimMode().equals(activeMode)
                || SilentAuraConfig.matrixCompatibility() != activeMatrix) {
            resetTargeting();
            activeMode = SilentAuraConfig.aimMode();
            activeMatrix = SilentAuraConfig.matrixCompatibility();
        }
        if (externallyPreempted()) {
            pauseForExternalRotation();
            return;
        }
        if (!activationHeld(client)) {
            finishTracking(client, deltaSeconds);
            return;
        }
        updateTracking(client, deltaSeconds);
    }

    /** Live target tracking follows render cadence; attack dispatch remains tick-bound. */
    private static void updateTracking(Minecraft client, double deltaSeconds) {
        Vec3 referenceLook =
                ROTATION.active() && !ROTATION.returning()
                        ? ROTATION.lookVector()
                        : client.player.getViewVector(1.0F);
        LivingEntity target = SELECTOR.select(client, referenceLook);
        if (target == null) {
            Animations.setAuraBlocking(false);
            sent = SentRotation.invalid();
            ROTATION.returnToCamera(client, deltaSeconds);
            if (!ROTATION.active()) PACKET_ROTATION.reset();
            syncLease();
            return;
        }
        if (ROTATION.targetId() != target.getId()) {
            sent = SentRotation.invalid();
        }
        // Lock is judged against the packet-domain ray, not the faster visual
        // head rotation.  Feed the last confirmed look back into point
        // selection so recovery chooses the smallest real server-visible turn.
        Vec3 aimReferenceLook =
                SilentAuraConfig.lockMode() && sent.valid() && sent.targetId() == target.getId()
                        ? sent.look()
                        : referenceLook;
        Vec3 point = SELECTOR.aimPoint(client, target, aimReferenceLook);
        if (point == null) {
            Animations.setAuraBlocking(false);
            SELECTOR.clear();
            sent = SentRotation.invalid();
            ROTATION.returnToCamera(client, deltaSeconds);
            if (!ROTATION.active()) PACKET_ROTATION.reset();
            syncLease();
            return;
        }
        // Holding the target owns the visual block pose. Attack range and
        // cooldown only decide when a block-hit swing is fired.
        Animations.setAuraBlocking(SilentAuraConfig.block());
        if (!ROTATION_LEASE.active()) {
            Rotation camera = new Rotation(client.player.getYRot(), client.player.getXRot());
            if (!ROTATION_LEASE.acquire(
                    new RotationRequest(camera.yaw(), camera.pitch(), 1, 0.35F, null),
                    camera,
                    start -> {
                        ROTATION.clear();
                        PACKET_ROTATION.reset();
                        ROTATION.beginFrom(start.yaw(), start.pitch());
                        PACKET_ROTATION.rebase(start.yaw(), start.pitch());
                    })) return;
        }
        ROTATION.track(client, target, point, deltaSeconds);
        syncLease();
    }

    private static void finishTracking(Minecraft client, double deltaSeconds) {
        Animations.setAuraBlocking(false);
        SELECTOR.clear();
        sent = SentRotation.invalid();
        ROTATION.returnToCamera(client, deltaSeconds);
        if (!ROTATION.active()) PACKET_ROTATION.reset();
        syncLease();
    }

    public static boolean activationHeld(Minecraft client) {
        return baseCanRun(client)
                && Targeting.isHoldingTriggerWeapon(client)
                && CombatInputController.isPhysicallyDown(client, client.options.keyAttack);
    }

    public static boolean shouldApplyRotation() {
        Minecraft client = Minecraft.getInstance();
        return client != null
                && ClientReady.world(client)
                && ROTATION_LEASE.active()
                && canApply(client)
                && ROTATION.active()
                && SilentAuraConfig.aimMode().equals(activeMode)
                && SilentAuraConfig.matrixCompatibility() == activeMatrix;
    }

    public static boolean shouldCorrectMovement() {
        return shouldApplyRotation();
    }

    public static boolean shouldSuppressBlockBreaking() {
        return activationHeld(Minecraft.getInstance());
    }

    /** Keeps manual USE_ITEM yaw/pitch in the same tick domain as movement. */
    public static boolean shouldSuppressUseAction(Minecraft client) {
        if (!baseCanRun(client) || SilentPacketRotation.isInvokingSimulatedUse()) {
            return false;
        }
        if (RotationLease.hasSilentRotation()) return true;
        RotationHistory.Sent previous = RotationHistory.latest();
        // A release may follow this tick's movement. Discard mismatched input;
        // never interrupt the return trajectory or replay a blocked click later.
        return previous.valid()
                && previous.tick() == client.player.tickCount
                && (!sameAngle(previous.yaw(), client.player.getYRot())
                        || !sameAngle(previous.pitch(), client.player.getXRot()));
    }

    private static void trackRotationPacket(PacketSendEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) {
            return;
        }
        if (event.packet() instanceof ServerboundUseItemPacket use
                && SilentAuraConfig.enabled()
                && !SilentPacketRotation.isUseRotationLocked()
                && !SilentPacketRotation.shouldApplyRotation()
                && RotationHistory.latest().tick() != currentPlayer.tickCount) {
            // A vanilla USE_ITEM sent before LocalPlayer.tick must own the
            // exact float pair of the movement packet that closes this tick.
            // Even a sub-display-decimal mouse/GCD change trips BadPacketsJ.
            RotationLease.holdManual(new Rotation(use.getYRot(), use.getXRot()));
            return;
        }
        if (!(event.packet() instanceof ServerboundMovePlayerPacket)) {
            return;
        }
        if (!RotationHistory.observed(event.packet())) return;
        RotationHistory.Sent previous = RotationHistory.latest();
        if (RotationHistory.sentFor(ROTATION_LEASE, event.packet())) {
            confirmOutgoingRotation(previous.yaw(), previous.pitch());
        }
    }

    public static boolean shouldApplyManualUseRotation() {
        return RotationLease.manualRotation() != null;
    }

    public static float manualUseYaw() {
        Rotation rotation = RotationLease.manualRotation();
        return rotation == null ? 0 : rotation.yaw();
    }

    public static float manualUsePitch() {
        Rotation rotation = RotationLease.manualRotation();
        return rotation == null ? 0 : rotation.pitch();
    }

    private static void clearRotation() {
        Animations.setAuraBlocking(false);
        SELECTOR.clear();
        ROTATION.clear();
        sent = SentRotation.invalid();
        PACKET_ROTATION.reset();
        ROTATION_LEASE.release();
    }

    private static boolean sameAngle(float first, float second) {
        return Float.floatToIntBits(first) == Float.floatToIntBits(second);
    }

    public static float yaw() {
        return ROTATION.yaw();
    }

    public static float pitch() {
        return ROTATION.pitch();
    }

    public static boolean crossingTarget() {
        return ROTATION.crossingTarget();
    }

    public static float bodyYaw() {
        return ROTATION.bodyYaw();
    }

    public static float movementYaw() {
        return committedRotation().yaw();
    }

    private static void confirmOutgoingRotation(float yaw, float pitch) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (!shouldApplyRotation() || client == null || currentPlayer == null) return;
        PACKET_ROTATION.confirm(yaw, pitch);
        int targetId = ROTATION.returning() ? -1 : ROTATION.targetId();
        sent =
                new SentRotation(
                        targetId >= 0,
                        yaw,
                        pitch,
                        currentPlayer.getEyePosition(),
                        Vec3.directionFromRotation(pitch, yaw),
                        targetId);
        if (ROTATION.returnPacketReached(yaw, pitch)) {
            ROTATION.completeReturn();
            sent = SentRotation.invalid();
            PACKET_ROTATION.reset();
            ROTATION_LEASE.release();
        }
    }

    public static float packetYaw() {
        return committedRotation().yaw();
    }

    public static float packetPitch() {
        return committedRotation().pitch();
    }

    public static Rotation committedRotation() {
        RotationLease.Submission existing = RotationLease.submission();
        if (existing != null && existing.lease() == ROTATION_LEASE) return existing.rotation();
        Rotation result = ROTATION_LEASE.commit(packetRotation(), false, true);
        return result != null ? result : RotationHistory.start(Minecraft.getInstance());
    }

    public static SentRotation sentRotation() {
        return sent;
    }

    /**
     * Rotation used by the attack path in the current player tick.
     * The ray is checked against the
     * rotation candidate that the later sendPosition call will publish, not
     * against the movement packet from the previous tick. packetRotation() is
     * tick-cached, so the attack and movement hooks consume the same candidate.
     */
    public static AttackRotation attackRotation(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null
                || currentPlayer == null
                || !shouldApplyRotation()
                || ROTATION.returning()) {
            return AttackRotation.invalid();
        }
        LivingEntity target = SELECTOR.current(client);
        if (target == null || ROTATION.targetId() != target.getId()) {
            return AttackRotation.invalid();
        }
        Rotation candidate = committedRotation();
        float yaw = candidate.yaw();
        float pitch = candidate.pitch();
        Vec3 eye = currentPlayer.getEyePosition();
        return new AttackRotation(
                true, yaw, pitch, eye, Vec3.directionFromRotation(pitch, yaw), target.getId());
    }

    public static LivingEntity currentTarget(Minecraft client) {
        return SELECTOR.current(client);
    }

    public static void onSuccessfulAttack(Minecraft client, LivingEntity target) {
        SELECTOR.onAttack(client, target);
    }

    public static void clearVisualBlock() {
        Animations.setAuraBlocking(false);
    }

    public static void resetTargeting() {
        clearRotation();
    }

    public static void finishReturn(Minecraft client) {
        if (!ROTATION.returning()) return;
        ROTATION.cancelReturn(client);
        sent = SentRotation.invalid();
        PACKET_ROTATION.reset();
        ROTATION_LEASE.release();
    }

    public static void reset(Minecraft client) {
        Animations.setAuraBlocking(false);
        SELECTOR.clear();
        ROTATION.clear();
        sent = SentRotation.invalid();
        PACKET_ROTATION.reset();
        ROTATION_LEASE.release();
    }

    private static void syncLease() {
        if (ROTATION.active()) {
            ROTATION_LEASE.acquire(
                    new RotationRequest(ROTATION.yaw(), ROTATION.pitch(), 1, 0.35F, null));
        } else {
            ROTATION_LEASE.release();
        }
    }

    private static Rotation packetRotation() {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) {
            return new Rotation(ROTATION.yaw(), ROTATION.pitch());
        }
        return PACKET_ROTATION.sample(
                currentPlayer.tickCount,
                currentPlayer.getYRot(),
                currentPlayer.getXRot(),
                ROTATION.yaw(),
                ROTATION.pitch(),
                SilentAuraConfig.lockMode(),
                client.options.sensitivity().get(),
                ROTATION.crossingTarget(),
                SilentAuraConfig.matrixCompatibility());
    }

    private static boolean canApply(Minecraft client) {
        return SilentAuraConfig.enabled()
                && ClientReady.world(client)
                && !SilentPacketRotation.shouldApplyRotation()
                && !RotationLease.busyFor(ROTATION_LEASE);
    }

    private static boolean baseCanRun(Minecraft client) {
        return SilentAuraConfig.enabled() && ClientReady.gameplay(client);
    }

    private static boolean externallyPreempted() {
        return SilentPacketRotation.shouldApplyRotation() || RotationLease.busyFor(ROTATION_LEASE);
    }

    /** External rotations invalidate both the old trajectory and its packet history. */
    private static void pauseForExternalRotation() {
        clearRotation();
    }

    public record SentRotation(
            boolean valid, float yaw, float pitch, Vec3 eye, Vec3 look, int targetId) {
        static SentRotation invalid() {
            return new SentRotation(false, 0.0F, 0.0F, Vec3.ZERO, Vec3.ZERO, -1);
        }
    }

    public record AttackRotation(
            boolean valid, float yaw, float pitch, Vec3 eye, Vec3 look, int targetId) {
        static AttackRotation invalid() {
            return new AttackRotation(false, 0.0F, 0.0F, Vec3.ZERO, Vec3.ZERO, -1);
        }
    }
}
