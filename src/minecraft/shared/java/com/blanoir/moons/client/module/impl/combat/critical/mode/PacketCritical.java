package com.blanoir.moons.client.module.impl.combat.critical.mode;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.RotationManager;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatDecisionEngine;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/** Publishes a short airborne descent and the attack's rotation before ATTACK. */
public final class PacketCritical {
    private static final double HOP_HEIGHT = 0.0625D;
    private static final double FALL_HEIGHT = 0.001D;
    private static final float CRITICAL_CHARGE = 0.9F;

    private PacketCritical() {}

    public static Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        if (!Predict.configuredEnabled()) {
            return Critical.AttackDecision.NONE;
        }
        if (!ClientReady.gameplay(client) || target == null || !target.isAlive()) {
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
        if (!ClientReady.gameplay(client) || target == null || !target.isAlive()) {
            return Critical.AutomaticAttackGate.BLOCK;
        }
        if (canHop(client, target)
                && client.player.getAttackStrengthScale(0.5F) <= CRITICAL_CHARGE) {
            return Critical.AutomaticAttackGate.WAIT;
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
        if (!Predict.configuredEnabled()
                || !canHop(client, target)
                || client.player.getAttackStrengthScale(0.5F) <= CRITICAL_CHARGE) {
            return;
        }
        RotationManager.Decision decision = RotationManager.resolve();
        Rotation rotation =
                decision == null
                        ? new Rotation(client.player.getYRot(), client.player.getXRot())
                        : decision.rotation();
        // The server may still be sprinting even if another module just cleared
        // the local flag: send the stop before ATTACK, not next sendPosition.
        client.player.setSprinting(false);
        client.player.connection.send(
                new ServerboundPlayerCommandPacket(
                        client.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        sendMovement(
                client.player.position(),
                rotation,
                client.player.horizontalCollision,
                client.player.connection::send);
    }

    private static boolean canHop(Minecraft client, Entity target) {
        return ClientReady.gameplay(client)
                && target instanceof LivingEntity
                && target.isAlive()
                && client.player.onGround()
                && !client.player.isPassenger()
                && !client.player.onClimbable()
                && !client.player.isInWater()
                && !client.player.isInLava()
                && !client.player.isMobilityRestricted()
                && !client.player.getAbilities().flying
                && client.level.noCollision(
                        client.player,
                        client.player.getBoundingBox().expandTowards(0.0D, HOP_HEIGHT, 0.0D));
    }

    private static void sendMovement(
            Vec3 position,
            Rotation rotation,
            boolean horizontalCollision,
            Consumer<ServerboundMovePlayerPacket> send) {
        send.accept(
                new ServerboundMovePlayerPacket.PosRot(
                        position.add(0.0D, HOP_HEIGHT, 0.0D),
                        rotation.yaw(),
                        rotation.pitch(),
                        false,
                        horizontalCollision));
        send.accept(
                new ServerboundMovePlayerPacket.PosRot(
                        position.add(0.0D, FALL_HEIGHT, 0.0D),
                        rotation.yaw(),
                        rotation.pitch(),
                        false,
                        horizontalCollision));
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
