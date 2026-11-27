package com.blanoir.tempos.client.module.impl.combat.critical.mode;

import com.blanoir.tempos.client.manager.combat.CombatDecisionEngine;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Registers the Predict strategy as the {@code predict} critical mode. All
 * calls are forwarded to {@link Predict}.
 */
public final class PredictMode implements CriticalMode {
    private final Predict feature;

    public PredictMode(Predict feature) {
        this.feature = feature;
    }

    @Override
    public String id() {
        return "predict";
    }

    @Override
    public String displayName() {
        return "Predict";
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
    public void cancelSilentAuraPrediction(Minecraft client) {
        feature.cancelSilentAuraPrediction(client);
    }

    @Override
    public boolean isAimingWindowActive() {
        return feature.isAimingWindowActive();
    }

    @Override
    public CombatDecisionEngine.Decision plannedDecision() {
        return feature.getPlannedDecision();
    }

    @Override
    public int showStatus(Minecraft client) {
        return feature.showStatus(client);
    }
}
