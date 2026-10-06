package com.blanoir.moons.client.module.impl.combat.critical.mode;

import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.utils.combat.CombatDecisionEngine;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Registers the Predict strategy as the {@code predict} critical mode. All
 * calls are forwarded to {@link Predict}.
 */
public final class PredictMode implements CriticalMode {
    private static final PredictMode INSTANCE = new PredictMode();

    public static PredictMode mode() {
        return INSTANCE;
    }

    private PredictMode() {}

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
        Predict.init();
    }

    @Override
    public boolean isEnabled() {
        return Predict.isEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return Predict.setEnabled(client, value);
    }

    @Override
    public Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        return Predict.requestAttack(client, target, earliestAttackTick, throughBlock);
    }

    @Override
    public Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return Predict.gateSilentAuraAttack(client, target, earliestAttackTick);
    }

    @Override
    public void cancelSilentAuraPrediction(Minecraft client) {
        Predict.cancelSilentAuraPrediction(client);
    }

    @Override
    public boolean isAimingWindowActive() {
        return Predict.isAimingWindowActive();
    }

    @Override
    public CombatDecisionEngine.Decision plannedDecision() {
        return Predict.getPlannedDecision();
    }

    @Override
    public int showStatus(Minecraft client) {
        return Predict.showStatus(client);
    }
}
