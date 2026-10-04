package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationManager;
import com.blanoir.moons.client.management.rotation.RotationRequest;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.Vec3;

/** Coordinates independent selector, rotation and attack components. */
public final class SilentAuraRuntime {
    private static final SilentAuraModes MODES = new SilentAuraModes();
    private static boolean initialized;
    private static boolean running;
    private static boolean stopping;
    private static String activeMode;
    private static boolean activeMatrix;
    private static SentRotation sent = SentRotation.invalid();
    private static final RotationLease ROTATION_LEASE =
            new RotationLease(
                    "SilentAura",
                    RotationLease.PRIORITY_CONTINUOUS_COMBAT,
                    SilentAuraRuntime::shouldApplyRotation,
                    SilentAuraRuntime::committedRotation);

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
        if (stopping) {
            if (!ClientReady.world(client) || externallyPreempted()) {
                reset(client);
            } else {
                finishTracking(client, deltaSeconds);
                if (!MODES.rotation().active()) reset(client);
            }
            return;
        }
        if (!baseCanRun(client)) {
            if (running) stop(client);
            return;
        }
        running = true;
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
                MODES.rotation().active() && !MODES.rotation().returning()
                        ? MODES.rotation().lookVector()
                        : client.thePlayer.getLook(1.0F);
        EntityLivingBase target = MODES.targets().select(client, referenceLook);
        if (target == null) {
            MODES.learned().reset();
            sent = SentRotation.invalid();
            MODES.rotation().returnToCamera(client, deltaSeconds);
            if (!MODES.rotation().active()) MODES.resetPackets();
            syncLease();
            return;
        }
        if (MODES.rotation().targetId() != target.getEntityId()) {
            sent = SentRotation.invalid();
        }
        // Lock is judged against the packet-domain ray, not the faster visual
        // head rotation.  Feed the last confirmed look back into point
        // selection so recovery chooses the smallest real server-visible turn.
        Vec3 aimReferenceLook =
                SilentAuraConfig.lockMode()
                                && sent.valid()
                                && sent.targetId() == target.getEntityId()
                        ? sent.look()
                        : referenceLook;
        Vec3 point = MODES.targets().aimPoint(client, target, aimReferenceLook);
        if (point == null) {
            MODES.learned().reset();
            MODES.clearTargets();
            sent = SentRotation.invalid();
            MODES.rotation().returnToCamera(client, deltaSeconds);
            if (!MODES.rotation().active()) MODES.resetPackets();
            syncLease();
            return;
        }
        if (!ROTATION_LEASE.active()) {
            Rotation camera =
                    new Rotation(client.thePlayer.rotationYaw, client.thePlayer.rotationPitch);
            if (!ROTATION_LEASE.acquire(
                    new RotationRequest(camera.yaw(), camera.pitch(), 1, 0.35F, null),
                    camera,
                    start -> {
                        MODES.clearRotations();
                        MODES.resetPackets();
                        MODES.rotation().beginFrom(start.yaw(), start.pitch());
                        MODES.packets().rebase(start.yaw(), start.pitch());
                    })) return;
        }
        MODES.rotation().track(client, target, point, deltaSeconds);
        if (SilentAuraConfig.learnedAssist()) observeLearned(client, target, point);
        syncLease();
    }

    private static void observeLearned(Minecraft client, EntityLivingBase target, Vec3 point) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        MODES.learned()
                .observe(
                        client.theWorld,
                        target,
                        new LearnedPacketRotation.Geometry(
                                eye.xCoord,
                                eye.yCoord,
                                eye.zCoord,
                                point.xCoord,
                                point.yCoord,
                                point.zCoord));
    }

    public static void resetLearnedAssist() {
        MODES.learned().reset();
    }

    public static String learnedStatus() {
        return MODES.learned().status();
    }

    private static void finishTracking(Minecraft client, double deltaSeconds) {
        MODES.learned().reset();
        MODES.clearTargets();
        sent = SentRotation.invalid();
        MODES.rotation().returnToCamera(client, deltaSeconds);
        if (!MODES.rotation().active()) MODES.resetPackets();
        syncLease();
    }

    public static boolean activationHeld(Minecraft client) {
        return baseCanRun(client)
                && Targeting.isHoldingTriggerWeapon(client)
                && (!SilentAuraConfig.requireLeftClick()
                        || CombatInputController.isPhysicallyDown(
                                client, client.gameSettings.keyBindAttack));
    }

    public static boolean shouldApplyRotation() {
        Minecraft client = Minecraft.getMinecraft();
        return client != null
                && ClientReady.world(client)
                && ROTATION_LEASE.active()
                && canApply(client)
                && MODES.rotation().active()
                && SilentAuraConfig.aimMode().equals(activeMode)
                && SilentAuraConfig.matrixCompatibility() == activeMatrix;
    }

    public static boolean shouldCorrectMovement() {
        return shouldApplyRotation();
    }

    public static boolean shouldSuppressBlockBreaking() {
        return activationHeld(Minecraft.getMinecraft());
    }

    /** Keeps manual USE_ITEM yaw/pitch in the same tick domain as movement. */
    public static boolean shouldSuppressUseAction(Minecraft client) {
        if (!baseCanRun(client) || SilentPacketRotation.isInvokingSimulatedUse()) {
            return false;
        }
        if (RotationLease.hasSilentRotation()) return true;
        RotationManager.Sent previous = RotationManager.latest();
        // A release may follow this tick's movement. Discard mismatched input;
        // never interrupt the return trajectory or replay a blocked click later.
        return previous.valid()
                && previous.tick() == client.thePlayer.ticksExisted
                && (!sameAngle(previous.yaw(), client.thePlayer.rotationYaw)
                        || !sameAngle(previous.pitch(), client.thePlayer.rotationPitch));
    }

    private static void trackRotationPacket(PacketSendEvent.Post event) {
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null) {
            return;
        }
        if (event.packet() instanceof C08PacketPlayerBlockPlacement use
                && SilentAuraConfig.enabled()
                && !SilentPacketRotation.isUseRotationLocked()
                && !SilentPacketRotation.shouldApplyRotation()
                && RotationManager.latest().tick() != currentPlayer.ticksExisted) {
            // A vanilla USE_ITEM sent before EntityPlayerSP.tick must own the
            // exact float pair of the movement packet that closes this tick.
            // Even a sub-display-decimal mouse/GCD change trips BadPacketsJ.
            RotationLease.holdManual(
                    new Rotation(currentPlayer.rotationYaw, currentPlayer.rotationPitch));
            return;
        }
        if (!(event.packet() instanceof C03PacketPlayer)) {
            return;
        }
        if (!RotationManager.observed(event.packet())) return;
        RotationManager.Sent previous = RotationManager.latest();
        if (RotationManager.sentFor(ROTATION_LEASE, event.packet())) {
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
        MODES.clearTargets();
        MODES.clearRotations();
        sent = SentRotation.invalid();
        MODES.resetPackets();
        ROTATION_LEASE.release();
    }

    private static boolean sameAngle(float first, float second) {
        return Float.floatToIntBits(first) == Float.floatToIntBits(second);
    }

    public static float yaw() {
        return MODES.rotation().yaw();
    }

    public static float pitch() {
        return MODES.rotation().pitch();
    }

    public static boolean crossingTarget() {
        return MODES.rotation().crossingTarget();
    }

    public static float bodyYaw() {
        return MODES.rotation().bodyYaw();
    }

    public static float movementYaw() {
        return committedRotation().yaw();
    }

    private static void confirmOutgoingRotation(float yaw, float pitch) {
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        if (!shouldApplyRotation() || client == null || currentPlayer == null) return;
        MODES.packets().confirm(yaw, pitch);
        if (SilentAuraConfig.learnedAssist() && !MODES.rotation().returning()) {
            MODES.learned().confirm(currentPlayer.ticksExisted, System.nanoTime(), yaw, pitch);
        }
        int targetId = MODES.rotation().returning() ? -1 : MODES.rotation().targetId();
        sent =
                new SentRotation(
                        targetId >= 0,
                        yaw,
                        pitch,
                        currentPlayer.getPositionEyes(1.0F),
                        VecMath.directionFromRotation(pitch, yaw),
                        targetId);
        if (MODES.rotation().returnPacketReached(yaw, pitch)) {
            MODES.rotation().completeReturn();
            sent = SentRotation.invalid();
            MODES.resetPackets();
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
        return result != null ? result : RotationManager.start(Minecraft.getMinecraft());
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
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null
                || currentPlayer == null
                || !shouldApplyRotation()
                || MODES.rotation().returning()) {
            return AttackRotation.invalid();
        }
        EntityLivingBase target = MODES.targets().current(client);
        if (target == null || MODES.rotation().targetId() != target.getEntityId()) {
            return AttackRotation.invalid();
        }
        Rotation candidate = committedRotation();
        float yaw = candidate.yaw();
        float pitch = candidate.pitch();
        Vec3 eye = currentPlayer.getPositionEyes(1.0F);
        return new AttackRotation(
                true,
                yaw,
                pitch,
                eye,
                VecMath.directionFromRotation(pitch, yaw),
                target.getEntityId());
    }

    public static EntityLivingBase currentTarget(Minecraft client) {
        return MODES.targets().current(client);
    }

    public static void onSuccessfulAttack(Minecraft client, EntityLivingBase target) {
        MODES.targets().onAttack(client, target);
    }

    public static void resetTargeting() {
        SilentAuraCombat.stop(Minecraft.getMinecraft());
        clearRotation();
    }

    public static void finishReturn(Minecraft client) {
        if (!MODES.rotation().returning()) return;
        MODES.rotation().cancelReturn(client);
        sent = SentRotation.invalid();
        MODES.resetPackets();
        ROTATION_LEASE.release();
    }

    public static void reset(Minecraft client) {
        SilentAuraCombat.stop(client);
        running = false;
        stopping = false;
        MODES.clearTargets();
        MODES.clearRotations();
        sent = SentRotation.invalid();
        MODES.resetPackets();
        ROTATION_LEASE.release();
    }

    /** Ordinary disable stops attacks now, but observes the return's actual movement send. */
    public static void stop(Minecraft client) {
        SilentAuraCombat.stop(client);
        running = false;
        if (!ClientReady.world(client) || !ROTATION_LEASE.active() || !MODES.rotation().active()) {
            reset(client);
            return;
        }
        stopping = true;
        finishTracking(client, 0.0D);
        if (!MODES.rotation().active()) reset(client);
    }

    private static void syncLease() {
        if (MODES.rotation().active()) {
            ROTATION_LEASE.acquire(
                    new RotationRequest(
                            MODES.rotation().yaw(), MODES.rotation().pitch(), 1, 0.35F, null));
        } else {
            ROTATION_LEASE.release();
        }
    }

    private static Rotation packetRotation() {
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null) {
            return new Rotation(MODES.rotation().yaw(), MODES.rotation().pitch());
        }
        boolean assist = SilentAuraConfig.learnedAssist() && !MODES.rotation().returning();
        if (assist) {
            EntityLivingBase target = MODES.targets().current(client);
            Vec3 point = MODES.rotation().learnedPoint();
            assist =
                    target != null
                            && point != null
                            && target.getEntityId() == MODES.rotation().targetId();
            if (assist) observeLearned(client, target, point);
        }
        if (!assist) MODES.learned().reset();
        return MODES.samplePacket(
                currentPlayer.ticksExisted,
                currentPlayer.rotationYaw,
                currentPlayer.rotationPitch,
                MODES.rotation().yaw(),
                MODES.rotation().pitch(),
                SilentAuraConfig.lockMode(),
                client.gameSettings.mouseSensitivity,
                MODES.rotation().crossingTarget(),
                SilentAuraConfig.matrixCompatibility(),
                assist);
    }

    private static boolean canApply(Minecraft client) {
        return (SilentAuraConfig.enabled() || stopping)
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
        private static final SentRotation INVALID =
                new SentRotation(false, 0.0F, 0.0F, VecMath.ZERO, VecMath.ZERO, -1);

        static SentRotation invalid() {
            return INVALID;
        }
    }

    public record AttackRotation(
            boolean valid, float yaw, float pitch, Vec3 eye, Vec3 look, int targetId) {
        private static final AttackRotation INVALID =
                new AttackRotation(false, 0.0F, 0.0F, VecMath.ZERO, VecMath.ZERO, -1);

        static AttackRotation invalid() {
            return INVALID;
        }
    }
}
