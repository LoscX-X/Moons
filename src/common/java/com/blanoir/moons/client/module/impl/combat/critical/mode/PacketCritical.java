package com.blanoir.moons.client.module.impl.combat.critical.mode;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.rotation.RotationManager;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatDecisionEngine;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

import java.util.function.Consumer;

/** Publishes a short airborne descent and the attack's rotation before ATTACK. */
public final class PacketCritical {
    private static final double HOP_HEIGHT = 0.0625D;
    private static final double FALL_HEIGHT = 0.001D;

    private PacketCritical() {}

    public static Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        if (!Predict.configuredEnabled()) {
            return Critical.AttackDecision.NONE;
        }
        if (!ClientReady.gameplay(client) || target == null || !target.isEntityAlive()) {
            return Critical.AttackDecision.ABORTED;
        }
        if (earliestAttackTick > 0) {
            return Critical.AttackDecision.NONE;
        }
        if (gateSilentAuraAttack(client, target, earliestAttackTick)
                == Critical.AutomaticAttackGate.WAIT) {
            return Critical.AttackDecision.NONE;
        }
        return CombatInputController.attackTargetNow(client, target, throughBlock)
                ? Critical.AttackDecision.ATTACKED
                : Critical.AttackDecision.ABORTED;
    }

    public static Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        if (!Predict.configuredEnabled()) return Critical.AutomaticAttackGate.ALLOW;
        if (!ClientReady.gameplay(client) || target == null || !target.isEntityAlive()) {
            return Critical.AutomaticAttackGate.BLOCK;
        }

        return Critical.AutomaticAttackGate.ALLOW;
    }

    /**
     * SilentAura attacks during PLAYER_UPDATE, before vanilla sendPosition.
     * Publish its committed rotation here without changing the camera. Both
     * positions stay above the floor; moving below it can trigger a server
     * collision correction that also snaps the camera to the server's yaw.
     */
    public static void beforeAttack(Minecraft client, Entity target) {
        if (!Predict.configuredEnabled() || !canHop(client, target)) {
            return;
        }
        RotationManager.Decision decision = RotationManager.resolve();
        Rotation rotation =
                decision == null
                        ? new Rotation(client.thePlayer.rotationYaw, client.thePlayer.rotationPitch)
                        : decision.rotation();
        // The server may still be sprinting even if another module just cleared
        // the local flag: send the stop before ATTACK, not next sendPosition.
        client.thePlayer.setSprinting(false);
        client.thePlayer.sendQueue.addToSendQueue(
                new C0BPacketEntityAction(
                        client.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
        sendMovement(
                VecMath.position(client.thePlayer),
                rotation,
                client.thePlayer.isCollidedHorizontally,
                client.thePlayer.sendQueue::addToSendQueue);
    }

    private static boolean canHop(Minecraft client, Entity target) {
        return ClientReady.gameplay(client)
                && target instanceof EntityLivingBase
                && target.isEntityAlive()
                && client.thePlayer.onGround
                && !client.thePlayer.isRiding()
                && !client.thePlayer.isOnLadder()
                && !client.thePlayer.isInWater()
                && !client.thePlayer.isInLava()
                && !client.thePlayer.isPlayerSleeping()
                && !client.thePlayer.capabilities.isFlying
                && client.theWorld
                        .getCollidingBoundingBoxes(
                                client.thePlayer,
                                client.thePlayer
                                        .getEntityBoundingBox()
                                        .addCoord(0.0D, HOP_HEIGHT, 0.0D))
                        .isEmpty();
    }

    private static void sendMovement(
            Vec3 position,
            Rotation rotation,
            boolean horizontalCollision,
            Consumer<C03PacketPlayer> send) {
        send.accept(
                new C03PacketPlayer.C06PacketPlayerPosLook(
                        position.xCoord,
                        position.yCoord + HOP_HEIGHT,
                        position.zCoord,
                        rotation.yaw(),
                        rotation.pitch(),
                        false));
        send.accept(
                new C03PacketPlayer.C06PacketPlayerPosLook(
                        position.xCoord,
                        position.yCoord + FALL_HEIGHT,
                        position.zCoord,
                        rotation.yaw(),
                        rotation.pitch(),
                        false));
    }

    public static boolean isEnabled() {
        return Predict.configuredEnabled();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        Predict.setConfiguredEnabled(value);
        return showStatus(client);
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Critical: "
                        + (Predict.configuredEnabled() ? "enabled" : "disabled")
                        + ", mode: Packet, grounded hop: 0.0625 -> 0.001, stop-sprint."
                        + " Usage: .moons critical <enable|disable>.");
        return 1;
    }

    public static CombatDecisionEngine.Decision plannedDecision() {
        return CombatDecisionEngine.Decision.abort("packet");
    }
}
