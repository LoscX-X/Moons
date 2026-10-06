package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.rotation.aim.AuraTargetSelector;
import com.blanoir.moons.client.utils.rotation.aim.AuraTrackingPoints;
import com.blanoir.moons.client.utils.rotation.aim.CenterAimPoints;
import com.blanoir.moons.client.utils.rotation.aim.ClosestAimPoints;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Owns one combat/aim combination's target and anchor history. Latest and Legacy use
 * the same implementation, but SilentAuraModes gives each an independent instance.
 * AuraTargetSelector evaluates candidates; AuraTrackingPoints resolves tracking points. */
final class SilentAuraTargets {

    private final boolean lockMode;
    private final boolean fullLockMode;

    private int targetId = -1;
    private int selectionTick = Integer.MIN_VALUE;
    private final CenterAimPoints.State center = new CenterAimPoints.State();
    private final ClosestAimPoints.State closest = new ClosestAimPoints.State();
    private final SilentAuraPointProcessor pointProcessor = new SilentAuraPointProcessor();
    private Object pointWorld;
    private LivingEntity pointTarget;
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
                && AuraTargetSelector.trackingEligible(
                        selectionParameters(), client, locked, scanRange)) {
            return locked;
        }
        selectionTick = client.player.tickCount;

        AuraTargetSelector.Candidates evaluated =
                AuraTargetSelector.evaluate(
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
        AuraTargetSelector.Candidate selected =
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
        return range > 0.0D && CombatGeometry.distanceSquared(client, target) <= range * range;
    }

    public Vec3 aimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        if (!validClient(client) || target == null) return null;
        if (pointWorld != client.level || pointTarget != target) {
            center.reset();
            closest.reset();
            pointProcessor.reset();
            pointWorld = client.level;
            pointTarget = target;
        }
        double attackRange = attackRange(client);
        double range =
                CombatGeometry.distanceSquared(client, target) <= attackRange * attackRange
                        ? attackRange
                        : scanRange(client);
        // Never reuse a world-space aim point. Resolve it from the target's
        // current bounding box on every frame, including in balance mode.
        Vec3 preferred;
        if (target.getId() == targetId
                && AuraTargetSelector.trackingEligible(
                        selectionParameters(), client, target, scanRange(client))) {
            preferred = AuraTrackingPoints.trackingAimPoint(pointContext(), client, target, look);
        } else {
            preferred =
                    AuraTrackingPoints.visibleAimPoint(pointContext(), client, target, look, range);
        }
        Vec3 eye = client.player.getEyePosition();
        var shape = CombatGeometry.shape(client, target);
        return pointProcessor.process(
                client.level,
                target,
                client.player.tickCount,
                shape.box(),
                preferred,
                SilentAuraConfig.pointParameters(),
                point ->
                        eye.distanceToSqr(point) <= range * range
                                && CombatGeometry.visible(
                                        client,
                                        shape,
                                        eye,
                                        point,
                                        SilentAuraConfig.throughBlocks()));
    }

    public void clear() {
        targetId = -1;
        selectionTick = Integer.MIN_VALUE;
        center.reset();
        closest.reset();
        pointProcessor.reset();
        pointWorld = null;
        pointTarget = null;
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

    private AuraTargetSelector.Parameters selectionParameters() {
        return new AuraTargetSelector.Parameters(
                SilentAuraConfig.targetPlayers(),
                SilentAuraConfig.targetEntityTypes(),
                SilentAuraConfig.fov(),
                SilentAuraConfig.hurtTime(),
                SilentAuraConfig.aimRange(),
                SilentAuraConfig.scanExtra(),
                lockMode,
                SilentAuraConfig.throughBlocks());
    }

    private AuraTrackingPoints.Context pointContext() {
        return new AuraTrackingPoints.Context(
                new AuraTrackingPoints.Parameters(
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
