package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.network.PacketBlink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Direct timing port of LiquidBounce Velocity Grim2371.
 *
 * <p>The hooks are intentionally split into their owning phases:</p>
 * <ul>
 *     <li>SEND_PRE classifies interactions, cancels positional movement while
 *     frozen, and observes the pong acknowledgement.</li>
 *     <li>SEND_POST records the rotation that actually left the client.</li>
 *     <li>RECEIVE_PRE arms damage velocity, owns the incoming Blink queue, and
 *     observes the block update used by the transaction sequence.</li>
 *     <li>PLAYER_UPDATE performs the downwards click and is cancellable while
 *     waiting for the block-update/pong handshake.</li>
 *     <li>Tick START resumes LB's two one-tick coroutine waits.</li>
 * </ul>
 */
final class VelocityGrim2371 {
    private static final int MAX_FREEZE_TICKS = 20;
    private static final PacketBlink INCOMING = new PacketBlink(8192);

    private static boolean coreInitialized;
    private static boolean receiveTailInitialized;

    private static boolean cancelNextVelocity;
    private static boolean delay;
    private static boolean needClick;
    private static boolean waitForPing;
    private static boolean waitForUpdate;
    private static boolean shouldSkip;
    private static BlockHitResult hitResult;
    private static int freezeTicks;

    /** -1 means no suspended one-tick continuation. */
    private static int blockUpdateWaitTicks = -1;
    private static int pongWaitTicks = -1;

    private static boolean serverRotationValid;
    private static float serverYaw;
    private static float serverPitch;

    private VelocityGrim2371() {
    }

    static synchronized void initCore() {
        if (coreInitialized) return;
        coreInitialized = true;
        EventBus.TICK.register("VelocityGrim2371.tick", event -> onTickStart(event.client()));
        EventBus.PACKET_SEND_PRE.register("VelocityGrim2371.packetSendPre", VelocityGrim2371::onSendPre);
        EventBus.PACKET_SEND_POST.register("VelocityGrim2371.packetSendPost", VelocityGrim2371::onSendPost);
        EventBus.PLAYER_UPDATE.register("VelocityGrim2371.playerUpdate", VelocityGrim2371::onPlayerUpdate);
    }

    /**
     * Registered after PacketEventRouter so every normal incoming PRE
     * observer sees a packet before Grim2371 decides to Blink it. Replayed
     * packets then resume at vanilla APPLY and are never double-classified.
     */
    static synchronized void initReceiveTail() {
        if (receiveTailInitialized) return;
        receiveTailInitialized = true;
        EventBus.PACKET_RECEIVE_PRE.register("VelocityGrim2371.packetReceivePre", VelocityGrim2371::onReceivePre);
    }

    static synchronized void enable() {
        resetState(false, Minecraft.getInstance());
    }

    static synchronized void disable(Minecraft client) {
        resetState(true, client);
    }

    static synchronized void clearForWorldChange(Minecraft client) {
        resetState(false, client);
        serverRotationValid = false;
    }

    private static boolean running() {
        return Velocity.isEnabled() && Velocity.grim2371Mode();
    }

    private static synchronized void onTickStart(Minecraft client) {
        if (!running() || client == null || client.player == null || client.level == null) {
            return;
        }

        if (blockUpdateWaitTicks > 0 && --blockUpdateWaitTicks == 0) {
            blockUpdateWaitTicks = -1;
            waitForPing = true;
            needClick = false;
        }

        if (pongWaitTicks > 0 && --pongWaitTicks == 0) {
            pongWaitTicks = -1;
            waitForUpdate = false;
            waitForPing = false;
        }
    }

    private static synchronized void onSendPre(PacketSendEvent.Pre event) {
        if (!running()) return;
        Packet<?> packet = event.packet();

        if (packet instanceof ServerboundInteractPacket
                || packet instanceof ServerboundAttackPacket
                || isSpectatorActionPacket(packet)
                || packet instanceof ServerboundUseItemOnPacket) {
            shouldSkip = true;
        }

        if (packet instanceof ServerboundMovePlayerPacket movement
                && movement.hasPosition() && waitForUpdate) {
            event.cancel();
            return;
        }

        if (packet instanceof ServerboundPongPacket && waitForPing) {
            pongWaitTicks = 1;
        }
    }

    private static synchronized void onSendPost(PacketSendEvent.Post event) {
        if (!running() || !(event.packet() instanceof ServerboundMovePlayerPacket movement)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        float fallbackYaw = serverRotationValid ? serverYaw
                : player == null ? 0.0F : player.getYRot();
        float fallbackPitch = serverRotationValid ? serverPitch
                : player == null ? 0.0F : player.getXRot();
        serverYaw = movement.getYRot(fallbackYaw);
        serverPitch = movement.getXRot(fallbackPitch);
        serverRotationValid = true;
    }

    private static synchronized void onReceivePre(PacketReceiveEvent.Pre event) {
        if (!running()) return;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) return;

        Packet<?> packet = event.packet();

        if (waitForUpdate) {
            if (packet instanceof ClientboundBlockUpdatePacket update
                    && update.getPos().equals(player.blockPosition())) {
                blockUpdateWaitTicks = 1;
            }
            return;
        }

        // The velocity packet itself is cancelled by the mode and is not part
        // of the Blink buffer. Every later incoming packet is retained by
        // identity until the downwards click is ready.
        if (delay) {
            if (INCOMING.offer(packet)) event.cancel();
            return;
        }

        if (packet instanceof ClientboundDamageEventPacket damage
                && damage.entityId() == player.getId()) {
            cancelNextVelocity = true;
            return;
        }

        if (cancelNextVelocity && isLocalVelocity(packet, player)) {
            event.cancel();
            delay = true;
            cancelNextVelocity = false;
            needClick = true;
        }
    }

    private static synchronized void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!running()) return;
        Minecraft client = event.client();
        var currentGameMode = client == null ? null : client.gameMode;
        var connectionSnapshot = client == null ? null : client.getConnection();
        LocalPlayer player = client == null ? null : client.player;
        if (client == null || player == null || client.level == null || currentGameMode == null
                || connectionSnapshot == null) {
            return;
        }

        if (needClick && !shouldSkip && !player.isUsingItem()) {
            hitResult = traceDown(client, player);
        }

        BlockHitResult click = hitResult;
        if (click != null) {
            delay = false;
            flushIncoming(client);

            InteractionResult result = currentGameMode.useItemOn(
                    player, InteractionHand.MAIN_HAND, click);
            if (result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
            }

            if (!serverRotationValid || Float.compare(serverPitch, 90.0F) != 0) {
                connectionSnapshot.send(new ServerboundMovePlayerPacket.Rot(
                        player.getYRot(), 90.0F, player.onGround(),
                        player.horizontalCollision));
            } else {
                connectionSnapshot.send(new ServerboundMovePlayerPacket.StatusOnly(
                        player.onGround(), player.horizontalCollision));
            }

            freezeTicks = 0;
            waitForUpdate = true;
            hitResult = null;
            needClick = false;
        }

        if (waitForUpdate) {
            event.cancel();
            freezeTicks++;
            if (freezeTicks > MAX_FREEZE_TICKS) {
                waitForUpdate = false;
                waitForPing = false;
                needClick = false;
                blockUpdateWaitTicks = -1;
                pongWaitTicks = -1;
            }
        }

        shouldSkip = false;
    }

    private static BlockHitResult traceDown(Minecraft client, LocalPlayer player) {
        float yaw = serverRotationValid ? serverYaw : player.getYRot();
        Vec3 eye = player.getEyePosition();
        double range = player.blockInteractionRange();
        Vec3 end = eye.add(Vec3.directionFromRotation(90.0F, yaw).scale(range));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos adjacent = hit.getBlockPos().relative(hit.getDirection());
        return adjacent.equals(player.blockPosition()) ? hit : null;
    }

    private static boolean isLocalVelocity(Packet<?> packet, LocalPlayer player) {
        return packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.id() == player.getId()
                || packet instanceof ClientboundExplodePacket explosion
                && explosion.playerKnockback().isPresent();
    }

    /** 26.1 carries spectate in Interact; newer protocol mappings split it. */
    private static boolean isSpectatorActionPacket(Packet<?> packet) {
        return packet.getClass().getSimpleName().equals("ServerboundSpectatorActionPacket");
    }

    @SuppressWarnings("unchecked")
    private static void flushIncoming(Minecraft client) {
        ClientPacketListener listener = client == null ? null : client.getConnection();
        if (listener == null) {
            INCOMING.clear();
            return;
        }
        for (Packet<?> packet : INCOMING.drain()) {
            try {
                ((Packet<ClientGamePacketListener>) packet).handle(listener);
            } catch (Exception ignored) {
                INCOMING.clear();
                return;
            }
        }
    }

    private static void resetState(boolean flush, Minecraft client) {
        if (flush) flushIncoming(client);
        else INCOMING.clear();
        cancelNextVelocity = false;
        delay = false;
        needClick = false;
        waitForPing = false;
        waitForUpdate = false;
        hitResult = null;
        shouldSkip = false;
        freezeTicks = 0;
        blockUpdateWaitTicks = -1;
        pongWaitTicks = -1;
    }
}
