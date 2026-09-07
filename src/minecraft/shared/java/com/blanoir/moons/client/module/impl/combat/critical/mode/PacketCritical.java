package com.blanoir.moons.client.module.impl.combat.critical.mode;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.management.combat.CombatDecisionEngine;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Sends one microscopic downward position before the real attack packet. */
public final class PacketCritical {
    private static final double FALL_SPEED = 0.000001D;
    private static final Vec3 FALL_VECTOR = new Vec3(0.0D, -FALL_SPEED, 0.0D);

    private PacketCritical() {}

    public static Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        if (!Predict.configuredEnabled()) {
            return Critical.AttackDecision.NONE;
        }
        if (!ready(client) || target == null || !target.isAlive()) {
            return Critical.AttackDecision.ABORTED;
        }
        if (earliestAttackTick > 0) {
            return Critical.AttackDecision.NONE;
        }
        CombatInputController.attackTarget(client, target, throughBlock);
        return Critical.AttackDecision.ATTACKED;
    }

    public static Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return Predict.configuredEnabled() && !ready(client)
                ? Critical.AutomaticAttackGate.BLOCK
                : Critical.AutomaticAttackGate.ALLOW;
    }

    /**
     * This hook runs before MultiPlayerGameMode sends ATTACK. It changes only
     * the server-facing position and deliberately leaves local position/motion
     * untouched, so there is no camera bob or one-frame client displacement.
     */
    public static void beforeAttack(Minecraft client, Entity target) {
        if (!Predict.configuredEnabled()
                || !ready(client)
                || !(target instanceof LivingEntity)
                || client.player.isPassenger()
                || client.player.onClimbable()
                || client.player.isInWater()
                || client.player.getAbilities().flying) {
            return;
        }
        Vec3 fallingPosition = client.player.position().add(FALL_VECTOR);
        client.player.connection.send(
                new ServerboundMovePlayerPacket.Pos(
                        fallingPosition, false, client.player.horizontalCollision));
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
                        + ", mode: Packet, fall vector: (0, -0.000001, 0)."
                        + " Usage: .moons critical <enable|disable>.");
        return 1;
    }

    public static CombatDecisionEngine.Decision plannedDecision() {
        return CombatDecisionEngine.Decision.abort("packet");
    }

    private static boolean ready(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && MinecraftClientAccess.screen(client) == null;
    }
}
