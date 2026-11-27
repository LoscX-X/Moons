package com.blanoir.tempos.client.module.impl.combat.critical.mode;

import com.blanoir.tempos.client.manager.combat.CombatDecisionEngine;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Registers the grounded packet-hop critical strategy. */
public final class PacketMode implements CriticalMode {
    private final PacketCritical feature;

    public PacketMode(PacketCritical feature) {
        this.feature = feature;
    }

    @Override
    public String id() {
        return "packet";
    }

    @Override
    public String displayName() {
        return "Packet";
    }

    @Override
    public void initialize() {
        feature.registerEvents();
    }

    @Override
    public boolean isEnabled() {
        return feature.isActive();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return feature.setActive(client, value);
    }

    @Override
    public Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        return feature.requestAttack(client, target, earliestAttackTick, throughBlock);
    }

    @Override
    public Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return feature.gateSilentAuraAttack(client, target, earliestAttackTick);
    }

    @Override
    public void cancelSilentAuraPrediction(Minecraft ignoredClient) {}

    @Override
    public boolean isAimingWindowActive() {
        return false;
    }

    @Override
    public CombatDecisionEngine.Decision plannedDecision() {
        return feature.plannedDecision();
    }

    @Override
    public void beforeAttack(Minecraft client, Entity target) {
        feature.beforeAttack(client, target);
    }

    @Override
    public int showStatus(Minecraft client) {
        return feature.showStatus(client);
    }
}
