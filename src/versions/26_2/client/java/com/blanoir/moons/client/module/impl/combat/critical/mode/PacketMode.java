package com.blanoir.moons.client.module.impl.combat.critical.mode;

import com.blanoir.moons.client.management.combat.CombatDecisionEngine;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Registers the microscopic downward-packet critical strategy. */
public final class PacketMode implements CriticalMode {
    private static final PacketMode INSTANCE = new PacketMode();

    public static PacketMode mode() {
        return INSTANCE;
    }

    private PacketMode() {
    }

    @Override public String id() { return "packet"; }
    @Override public String displayName() { return "Packet"; }
    @Override public void initialize() { }
    @Override public boolean isEnabled() { return PacketCritical.isEnabled(); }
    @Override public int setEnabled(Minecraft client, boolean value) {
        return PacketCritical.setEnabled(client, value);
    }

    @Override
    public Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        return PacketCritical.requestAttack(client, target, earliestAttackTick, throughBlock);
    }

    @Override
    public Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return PacketCritical.gateSilentAuraAttack(client, target, earliestAttackTick);
    }

    @Override public void cancelSilentAuraPrediction(Minecraft ignoredClient) { }
    @Override public boolean isAimingWindowActive() { return false; }
    @Override public CombatDecisionEngine.Decision plannedDecision() {
        return PacketCritical.plannedDecision();
    }
    @Override public void beforeAttack(Minecraft client, Entity target) {
        PacketCritical.beforeAttack(client, target);
    }
    @Override public int showStatus(Minecraft client) {
        return PacketCritical.showStatus(client);
    }
}
