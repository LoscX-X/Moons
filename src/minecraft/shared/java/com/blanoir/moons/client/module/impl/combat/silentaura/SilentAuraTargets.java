package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsA;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsB;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsD;
import com.blanoir.moons.client.utils.rotation.aim.TargetSelectorA;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Owns one combat/aim combination's target and anchor history. Latest and Legacy use
 * the same implementation, but SilentAuraModes gives each an independent instance.
 * TargetSelectorA evaluates candidates; AimPointsD resolves tracking points. */
final class SilentAuraTargets {

    private final boolean lockMode;
    private final boolean fullLockMode;

    private int targetId = -1;
    private int selectionTick = Integer.MIN_VALUE;
    private final AimPointsA.State center = new AimPointsA.State();
    private final AimPointsB.State closest = new AimPointsB.State();
    private boolean reevaluateAfterAttack;
    private int reevaluateTick = Integer.MIN_VALUE;

    public SilentAuraTargets(boolean lockMode) {
        this(lockMode, false);
    }

    public SilentAuraTargets(boolean lockMode, boolean fullLockMode) {
        this.lockMode = lockMode;
        this.fullLockMode = fullLockMode;
    }

    public LivingEntity select(Minecraft client, Vec3 referenceLook) {
        if (!validClient(client)) {
            clear();
            return null;
        }

        double attackRange = attackRange(client);
        double scanRange = scanRange(client);
        if (attackRange <= 0.0D || scanRange <= 0.0D) {
            clear();
            return null;
        }

        LivingEntity locked = current(client);
        if (selectionTick == client.player.tickCount
                && TargetSelectorA.trackingEligible(
                        selectionParameters(), client, locked, scanRange)) {
            return locked;
        }
        selectionTick = client.player.tickCount;

        TargetSelectorA.Candidates evaluated =
                TargetSelectorA.evaluate(
                        selectionParameters(),
                        client,
                        referenceLook,
                        locked,
                        attackRange,
                        scanRange);
        var candidates = evaluated.sorted();
        var retained = evaluated.retained();
        boolean reevaluateNow =
                SilentAuraConfig.switchTargetMode()
                        && reevaluateAfterAttack
                        && client.player.tickCount >= reevaluateTick;
        boolean keepCurrent = retained != null && !reevaluateNow;
        TargetSelectorA.Candidate selected =
                keepCurrent ? retained : candidates.isEmpty() ? null : candidates.getFirst();
        if (reevaluateNow || retained == null) reevaluateAfterAttack = false;
        if (selected == null) {
            clear();
            return null;
        }

        targetId = selected.entity().getId();
        return selected.entity();
    }

    public LivingEntity current(Minecraft client) {
        if (!validClient(client) || targetId < 0) return null;
        Entity entity = client.level.getEntity(targetId);
        return entity instanceof LivingEntity living ? living : null;
    }

    public boolean inAttackRange(Minecraft client, LivingEntity target) {
        if (!validClient(client) || target == null) return false;
        double range = attackRange(client);
        return range > 0.0D && EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    public Vec3 aimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        if (!validClient(client) || target == null) return null;
        // Never reuse a world-space aim point. Resolve it from the target's
        // current bounding box on every frame, including in balance mode.
        if (target.getId() == targetId
                && TargetSelectorA.trackingEligible(
                        selectionParameters(), client, target, scanRange(client))) {
            return AimPointsD.trackingAimPoint(pointContext(), client, target, look);
        }
        double attackRange = attackRange(client);
        double range =
                EntityDistance.squaredToEntity(client, target) <= attackRange * attackRange
                        ? attackRange
                        : scanRange(client);
        return AimPointsD.visibleAimPoint(pointContext(), client, target, look, range);
    }

    public void clear() {
        targetId = -1;
        selectionTick = Integer.MIN_VALUE;
        center.reset();
        closest.reset();
        reevaluateAfterAttack = false;
        reevaluateTick = Integer.MIN_VALUE;
    }

    /** Preserve this mode's attack-triggered switch timing and history. */
    public void onAttack(Minecraft client, LivingEntity attacked) {
        var currentPlayer = client == null ? null : client.player;
        if (!SilentAuraConfig.switchTargetMode()
                || attacked == null
                || attacked.getId() != targetId) return;
        reevaluateAfterAttack = true;
        reevaluateTick =
                client != null && currentPlayer != null
                        ? currentPlayer.tickCount + 1
                        : Integer.MIN_VALUE;
        selectionTick = Integer.MIN_VALUE;
    }

    private static boolean validClient(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    private static double attackRange(Minecraft client) {
        return CombatReach.entityInteractionRange(client, SilentAuraConfig.aimRange());
    }

    private static double scanRange(Minecraft client) {
        return CombatReach.scanRange(
                client, SilentAuraConfig.aimRange(), SilentAuraConfig.scanExtra());
    }

    private TargetSelectorA.Parameters selectionParameters() {
        return new TargetSelectorA.Parameters(
                SilentAuraConfig.targetPlayers(),
                SilentAuraConfig.targetEntityTypes(),
                SilentAuraConfig.fov(),
                SilentAuraConfig.hurtTime(),
                SilentAuraConfig.aimRange(),
                SilentAuraConfig.scanExtra(),
                lockMode,
                SilentAuraConfig.throughBlocks());
    }

    private AimPointsD.Context pointContext() {
        return new AimPointsD.Context(
                new AimPointsD.Parameters(
                        lockMode,
                        fullLockMode,
                        SilentAuraConfig.closestAimPoint(),
                        SilentAuraConfig.aimWander(),
                        SilentAuraConfig.aimWanderTicks(),
                        SilentAuraConfig.throughBlocks(),
                        SilentAuraConfig.aimRange(),
                        SilentAuraConfig.scanExtra()),
                center,
                closest);
    }
}
