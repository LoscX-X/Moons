package com.blanoir.moons.client.module.impl.combat.critical.mode;

import com.blanoir.moons.client.management.combat.CombatDecisionEngine;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * A single critical-attack strategy. Register implementations with
 * {@link Critical#register(CriticalMode)} to make them selectable through the
 * {@code critical.mode} choice.
 */
public interface CriticalMode {
    /** Stable identifier used by the {@code critical.mode} setting. */
    String id();

    /** Human-readable label shown in the module HUD/status. */
    String displayName();

    /** Subscribe tick handlers / register lifecycle hooks when the feature boots. */
    void initialize();

    boolean isEnabled();

    int setEnabled(Minecraft client, boolean value);

    Critical.AttackDecision requestAttack(
            Minecraft client,
            Entity target,
            int earliestAttackTick,
            boolean throughBlock
    );

    Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client,
            Entity target,
            int earliestAttackTick
    );

    void cancelSilentAuraPrediction(Minecraft client);

    boolean isAimingWindowActive();

    CombatDecisionEngine.Decision plannedDecision();

    /** Runs at the head of Minecraft's real entity-attack path. */
    default void beforeAttack(Minecraft client, Entity target) {
    }

    int showStatus(Minecraft client);
}
