package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationRequest;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Coordinates independent selector, rotation and attack components. */
public final class SilentAuraRuntime {
    private static final SilentAuraTargetRouter SELECTOR = new SilentAuraTargetRouter();
    private static final SilentAuraRotationRouter ROTATION =
            new SilentAuraRotationRouter();
    private static boolean initialized;
    private static SentRotation sent = SentRotation.invalid();
    private static float outgoingYaw;
    private static float outgoingPitch;
    private static int outgoingTick = Integer.MIN_VALUE;
    private static float lastMovementYaw;
    private static float lastMovementPitch;
    private static int lastMovementTick = Integer.MIN_VALUE;
    private static boolean lastMovementValid;
    private static boolean deferredManualUse;
    private static boolean replayingManualUse;
    private static int deferredManualUseTick = Integer.MIN_VALUE;
    private static boolean manualUseRotationPending;
    private static float manualUseYaw;
    private static float manualUsePitch;
    private static final SilentAuraPacketRotationRouter PACKET_ROTATION =
            new SilentAuraPacketRotationRouter();
    private static final RotationLease ROTATION_LEASE = new RotationLease(
            "SilentAura", RotationLease.PRIORITY_CONTINUOUS_COMBAT);

    private SilentAuraRuntime() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.FRAME.register("SilentAuraRuntime.frame", event -> frame(event.client(), event.deltaSeconds()));
        EventBus.PLAYER_UPDATE.register(
                "SilentAuraRuntime.manualUseReplay",
                event -> replayDeferredManualUse(event.client()));
        EventBus.PACKET_SEND_POST.register(
                "SilentAuraRuntime.rotationPacketTracker",
                SilentAuraRuntime::trackRotationPacket);
    }

    private static void frame(Minecraft client, double deltaSeconds) {
        if (!baseCanRun(client)) { reset(client); return; }
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
        Vec3 referenceLook = ROTATION.active() && !ROTATION.returning()
                ? ROTATION.lookVector() : client.player.getViewVector(1.0F);
        LivingEntity target = SELECTOR.select(client, referenceLook);
        if (target == null) {
            Animations.setAuraBlocking(false);
            sent = SentRotation.invalid();
            PACKET_ROTATION.invalidateSample();
            ROTATION.returnToCamera(client, deltaSeconds);
            if (!ROTATION.active()) PACKET_ROTATION.reset();
            syncLease();
            return;
        }
        if (ROTATION.targetId() != target.getId()) {
            sent = SentRotation.invalid();
            PACKET_ROTATION.invalidateSample();
        }
        // Lock is judged against the packet-domain ray, not the faster visual
        // head rotation.  Feed the last confirmed look back into point
        // selection so recovery chooses the smallest real server-visible turn.
        Vec3 aimReferenceLook = SilentAuraConfig.lockMode()
                && sent.valid() && sent.targetId() == target.getId()
                ? sent.look() : referenceLook;
        Vec3 point = SELECTOR.aimPoint(client, target, aimReferenceLook);
        if (point == null) {
            Animations.setAuraBlocking(false);
            SELECTOR.clear();
            sent = SentRotation.invalid();
            PACKET_ROTATION.invalidateSample();
            ROTATION.returnToCamera(client, deltaSeconds);
            if (!ROTATION.active()) PACKET_ROTATION.reset();
            syncLease();
            return;
        }
        // Holding the target owns the visual block pose. Attack range and
        // cooldown only decide when a block-hit swing is fired.
        Animations.setAuraBlocking(SilentAuraConfig.block());
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
        return client != null && ClientReady.world(client) && ROTATION_LEASE.active()
                && canApply(client) && ROTATION.active();
    }
    public static boolean shouldCorrectMovement() { return shouldApplyRotation(); }
    public static boolean shouldSuppressBlockBreaking() { return activationHeld(Minecraft.getInstance()); }
    /** Keeps manual USE_ITEM yaw/pitch in the same tick domain as movement. */
    public static boolean shouldSuppressUseAction(Minecraft client) {
        if (!baseCanRun(client) || replayingManualUse
                || SilentPacketRotation.isUseRotationLocked()) {
            return false;
        }
        boolean triggerWeaponRotation = Targeting.isHoldingTriggerWeapon(client)
                && ROTATION_LEASE.active() && ROTATION.active() && canApply(client);
        if (triggerWeaponRotation) {
            return true;
        }
        boolean postMovementMismatch = lastMovementValid
                && lastMovementTick == client.player.tickCount
                && (!sameAngle(lastMovementYaw, client.player.getYRot())
                || !sameAngle(lastMovementPitch, client.player.getXRot()));
        boolean utilityInterruptedAura = !Targeting.isHoldingTriggerWeapon(client)
                && ROTATION.active();
        if (!postMovementMismatch && !utilityInterruptedAura) {
            return false;
        }

        // startUseItem is currently running after this tick's movement packet.
        // Cancel it once, discard any remaining Aura return path, then replay
        // at the next LocalPlayer.tick head. The replayed USE_ITEM is followed
        // by a movement packet with the same camera yaw/pitch, satisfying
        // Grim BadPacketsJ without delaying utility input for the full return.
        deferredManualUse = true;
        deferredManualUseTick = client.player.tickCount;
        clearAuraRotationForManualUse();
        return true;
    }

    private static void trackRotationPacket(PacketSendEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        if (event.packet() instanceof ServerboundUseItemPacket use
                && SilentAuraConfig.enabled()
                && !SilentPacketRotation.isUseRotationLocked()
                && !SilentPacketRotation.shouldApplyRotation()
                && lastMovementTick != client.player.tickCount) {
            // A vanilla USE_ITEM sent before LocalPlayer.tick must own the
            // exact float pair of the movement packet that closes this tick.
            // Even a sub-display-decimal mouse/GCD change trips BadPacketsJ.
            manualUseYaw = use.getYRot();
            manualUsePitch = use.getXRot();
            manualUseRotationPending = true;
            return;
        }
        if (!(event.packet() instanceof ServerboundMovePlayerPacket movement)) {
            return;
        }
        float fallbackYaw = lastMovementValid
                ? lastMovementYaw : client.player.getYRot();
        float fallbackPitch = lastMovementValid
                ? lastMovementPitch : client.player.getXRot();
        lastMovementYaw = movement.getYRot(fallbackYaw);
        lastMovementPitch = movement.getXRot(fallbackPitch);
        lastMovementTick = client.player.tickCount;
        lastMovementValid = true;
        // One movement packet is the transaction boundary Grim uses. Never
        // carry a manual-use angle into a later physical tick.
        manualUseRotationPending = false;
    }

    public static boolean shouldApplyManualUseRotation() {
        return manualUseRotationPending;
    }

    public static float manualUseYaw() {
        return manualUseYaw;
    }

    public static float manualUsePitch() {
        return manualUsePitch;
    }

    private static void replayDeferredManualUse(Minecraft client) {
        if (!deferredManualUse || client == null || client.player == null
                || client.player.tickCount == deferredManualUseTick) {
            return;
        }
        if (!baseCanRun(client)) {
            clearDeferredManualUse();
            return;
        }
        if (SilentPacketRotation.shouldApplyRotation()
                || SilentPacketRotation.isUseRotationLocked()) {
            // Never fire an old physical click after an AutoWeb/AutoLava
            // transaction has taken ownership in the following tick.
            clearDeferredManualUse();
            return;
        }
        deferredManualUse = false;
        replayingManualUse = true;
        try {
            GameAccess.invokeStartUseItem(client);
        } finally {
            replayingManualUse = false;
            deferredManualUseTick = Integer.MIN_VALUE;
        }
    }

    private static void clearAuraRotationForManualUse() {
        Animations.setAuraBlocking(false);
        SELECTOR.clear();
        ROTATION.clear();
        sent = SentRotation.invalid();
        outgoingYaw = 0.0F;
        outgoingPitch = 0.0F;
        outgoingTick = Integer.MIN_VALUE;
        PACKET_ROTATION.reset();
        ROTATION_LEASE.release();
    }

    private static void clearDeferredManualUse() {
        deferredManualUse = false;
        replayingManualUse = false;
        deferredManualUseTick = Integer.MIN_VALUE;
    }

    private static boolean sameAngle(float first, float second) {
        return Float.floatToIntBits(first) == Float.floatToIntBits(second);
    }
    public static float yaw() { return ROTATION.yaw(); }
    public static float pitch() { return ROTATION.pitch(); }
    public static boolean crossingTarget() { return ROTATION.crossingTarget(); }
    public static float bodyYaw() { return ROTATION.bodyYaw(); }
    public static float movementYaw() {
        Minecraft client = Minecraft.getInstance();
        float base = sent.valid() ? sent.yaw()
                : client != null && client.player != null ? client.player.getYRot() : ROTATION.yaw();
        // Movement input and moveRelative must use the exact same tick-domain
        // yaw that sendPosition will publish. Bypassing PacketRotationSmoother
        // here made large crossing turns simulate against a different yaw on
        // the server. sample() is tick-cached, so the later packet hook reuses
        // this same candidate rather than drawing a second step.
        return RotationUtils.quantizeMouseStep(base, packetRotation().yaw());
    }

    public static void markOutgoingRotation(float yaw, float pitch, int tick) {
        Minecraft client = Minecraft.getInstance();
        if (!shouldApplyRotation() || client == null || client.player == null) return;
        if (outgoingTick == tick
                && Float.floatToIntBits(outgoingYaw) == Float.floatToIntBits(yaw)
                && Float.floatToIntBits(outgoingPitch) == Float.floatToIntBits(pitch)) return;
        outgoingTick = tick;
        outgoingYaw = yaw;
        outgoingPitch = pitch;
        PACKET_ROTATION.confirm(yaw, pitch);
        int targetId = ROTATION.returning() ? -1 : ROTATION.targetId();
        sent = new SentRotation(targetId >= 0, yaw, pitch, client.player.getEyePosition(),
                Vec3.directionFromRotation(pitch, yaw), targetId);
        ROTATION_LEASE.confirm(yaw, pitch);
        if (ROTATION.returnPacketReached(yaw, pitch)) {
            ROTATION.completeReturn();
            sent = SentRotation.invalid();
            PACKET_ROTATION.reset();
            ROTATION_LEASE.release();
        }
    }

    public static float packetYaw() { return packetRotation().yaw(); }
    public static float packetPitch() { return packetRotation().pitch(); }

    public static Vec3 sentLookVector() { return sent.valid() ? sent.look() : ROTATION.lookVector(); }
    public static SentRotation sentRotation() { return sent; }

    /**
     * Rotation used by the attack path in the current player tick. Like
     * LiquidBounce's Normal rotation timing, the ray is checked against the
     * rotation candidate that the later sendPosition call will publish, not
     * against the movement packet from the previous tick. packetRotation() is
     * tick-cached, so the attack and movement hooks consume the same candidate.
     */
    public static AttackRotation attackRotation(Minecraft client) {
        if (client == null || client.player == null || !shouldApplyRotation()
                || ROTATION.returning()) {
            return AttackRotation.invalid();
        }
        LivingEntity target = SELECTOR.current(client);
        if (target == null || ROTATION.targetId() != target.getId()) {
            return AttackRotation.invalid();
        }
        Rotation candidate = packetRotation();
        float baseYaw = outgoingTick != Integer.MIN_VALUE
                ? outgoingYaw : client.player.getYRot();
        float basePitch = outgoingTick != Integer.MIN_VALUE
                ? outgoingPitch : client.player.getXRot();
        float yaw = SilentPacketRotation.quantizePacketYaw(baseYaw, candidate.yaw());
        float pitch = SilentPacketRotation.quantizePacketPitch(basePitch, candidate.pitch());
        Vec3 eye = client.player.getEyePosition();
        return new AttackRotation(true, yaw, pitch, eye,
                Vec3.directionFromRotation(pitch, yaw), target.getId());
    }

    public static LivingEntity currentTarget(Minecraft client) { return SELECTOR.current(client); }
    public static void onSuccessfulAttack(Minecraft client, LivingEntity target) {
        SELECTOR.onAttack(client, target);
    }

    public static void clearVisualBlock() { Animations.setAuraBlocking(false); }

    public static void resetTargeting(Minecraft client) {
        Animations.setAuraBlocking(false);
        SELECTOR.clear();
        sent = SentRotation.invalid();
        PACKET_ROTATION.reset();
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
        outgoingYaw = 0.0F;
        outgoingPitch = 0.0F;
        outgoingTick = Integer.MIN_VALUE;
        PACKET_ROTATION.reset();
        ROTATION_LEASE.release();
        clearDeferredManualUse();
        lastMovementValid = false;
        lastMovementTick = Integer.MIN_VALUE;
        manualUseRotationPending = false;
        manualUseYaw = 0.0F;
        manualUsePitch = 0.0F;
    }

    private static void syncLease() {
        if (ROTATION.active()) {
            ROTATION_LEASE.acquire(new RotationRequest(
                    ROTATION.yaw(), ROTATION.pitch(), 1, 0.35F, null));
        } else {
            ROTATION_LEASE.release();
        }
    }

    private static Rotation packetRotation() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return new Rotation(ROTATION.yaw(), ROTATION.pitch());
        }
        return PACKET_ROTATION.sample(
                client.player.tickCount,
                client.player.getYRot(), client.player.getXRot(),
                ROTATION.yaw(), ROTATION.pitch(), SilentAuraConfig.lockMode(),
                client.options.sensitivity().get(), ROTATION.crossingTarget(),
                SilentAuraConfig.matrixCompatibility());
    }

    private static boolean canRun(Minecraft client) {
        return baseCanRun(client) && !externallyPreempted();
    }
    private static boolean canApply(Minecraft client) {
        return SilentAuraConfig.enabled() && ClientReady.world(client)
                && !SilentPacketRotation.shouldApplyRotation()
                && !RotationLease.busyFor(ROTATION_LEASE);
    }

    private static boolean baseCanRun(Minecraft client) {
        return SilentAuraConfig.enabled() && ClientReady.gameplay(client);
    }

    private static boolean externallyPreempted() {
        return SilentPacketRotation.shouldApplyRotation()
                || RotationLease.busyFor(ROTATION_LEASE);
    }

    /** Keep targeting/aim inertia, but never expose the stale combat ray. */
    private static void pauseForExternalRotation() {
        Animations.setAuraBlocking(false);
        sent = SentRotation.invalid();
        PACKET_ROTATION.invalidateSample();
        ROTATION_LEASE.release();
    }

    public record SentRotation(boolean valid, float yaw, float pitch, Vec3 eye, Vec3 look, int targetId) {
        static SentRotation invalid() {
            return new SentRotation(false, 0.0F, 0.0F, Vec3.ZERO, Vec3.ZERO, -1);
        }
    }

    public record AttackRotation(boolean valid, float yaw, float pitch,
                                 Vec3 eye, Vec3 look, int targetId) {
        static AttackRotation invalid() {
            return new AttackRotation(false, 0.0F, 0.0F, Vec3.ZERO, Vec3.ZERO, -1);
        }
    }
}
