package com.blanoir.moons.client.utils.prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Observed target motion with a short reservation for an expected attack impulse. */
public final class KnockbackPrediction {
    private static final double KNOCKBACK_SAMPLE_THRESHOLD = 0.08D;
    private int sampledTargetId = -1;
    private int sampledTargetTick = Integer.MIN_VALUE;
    private Vec3 lastSampledTargetPosition;
    private Vec3 smoothedTargetVelocity = Vec3.ZERO;
    private Vec3 smoothedTargetAcceleration = Vec3.ZERO;
    private Vec3 attackBaselineVelocity = Vec3.ZERO;
    private Vec3 anticipatedAttackVelocity = Vec3.ZERO;
    private boolean awaitingAttackVelocity;
    private int knockbackWaitTicks;

    public boolean matches(Player target) {
        return target != null && sampledTargetId == target.getId();
    }

    public Vec3 velocity() {
        return smoothedTargetVelocity;
    }

    public Vec3 acceleration() {
        return smoothedTargetAcceleration;
    }

    public void begin(Minecraft client, Player target) {
        sampledTargetId = target.getId();
        sampledTargetTick = target.tickCount;
        lastSampledTargetPosition = target.position();
        attackBaselineVelocity = target.getDeltaMovement();
        anticipatedAttackVelocity = expectedPostAttackVelocity(client, target);
        awaitingAttackVelocity =
                horizontalDifference(anticipatedAttackVelocity, attackBaselineVelocity)
                        > KNOCKBACK_SAMPLE_THRESHOLD;
        knockbackWaitTicks = 0;
        smoothedTargetVelocity =
                awaitingAttackVelocity ? anticipatedAttackVelocity : attackBaselineVelocity;
        smoothedTargetAcceleration = Vec3.ZERO;
    }

    /**
     * Combines observed position steps with the entity velocity. Position steps
     * receive more weight because remote-player delta movement can lag behind a
     * server knockback update. Acceleration is deliberately capped at prediction
     * time so one interpolation correction cannot throw the lava several cells.
     */
    public void observe(Minecraft client, Player target) {
        if (target == null) {
            return;
        }
        if (sampledTargetId != target.getId() || lastSampledTargetPosition == null) {
            begin(client, target);
            return;
        }
        if (sampledTargetTick == target.tickCount) {
            return;
        }

        int elapsedTicks = Math.max(1, target.tickCount - sampledTargetTick);
        Vec3 position = target.position();
        Vec3 observed = position.subtract(lastSampledTargetPosition).scale(1.0D / elapsedTicks);
        Vec3 reported = target.getDeltaMovement();
        Vec3 sample = limitHorizontal(observed.scale(0.72D).add(reported.scale(0.28D)), 1.5D);
        Vec3 previousVelocity = smoothedTargetVelocity;
        knockbackWaitTicks += elapsedTicks;
        if (awaitingAttackVelocity
                && knockbackWaitTicks <= 4
                && horizontalDifference(sample, attackBaselineVelocity)
                        < KNOCKBACK_SAMPLE_THRESHOLD) {
            // The attack packet is already out but the remote velocity update
            // has not arrived yet. Keep the vanilla impulse estimate instead
            // of smoothing it back into the stale pre-hit motion.
            smoothedTargetVelocity = anticipatedAttackVelocity;
            smoothedTargetAcceleration = Vec3.ZERO;
        } else {
            awaitingAttackVelocity = false;
            double impulse = horizontalDifference(sample, previousVelocity);
            if (impulse >= KNOCKBACK_SAMPLE_THRESHOLD) {
                // A knockback packet is a velocity discontinuity, not a
                // multi-tick acceleration. Adopt it immediately so a large
                // sideways hit cannot be damped by the old 55/45 filter.
                smoothedTargetVelocity = sample;
                smoothedTargetAcceleration = Vec3.ZERO;
            } else {
                smoothedTargetVelocity = previousVelocity.scale(0.55D).add(sample.scale(0.45D));
                Vec3 observedAcceleration = smoothedTargetVelocity.subtract(previousVelocity);
                smoothedTargetAcceleration =
                        smoothedTargetAcceleration
                                .scale(0.65D)
                                .add(observedAcceleration.scale(0.35D));
            }
        }
        lastSampledTargetPosition = position;
        sampledTargetTick = target.tickCount;
    }

    private static Vec3 limitHorizontal(Vec3 movement, double maximum) {
        double horizontal = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        if (horizontal <= maximum || horizontal <= 1.0E-9D) {
            return movement;
        }
        double scale = maximum / horizontal;
        return new Vec3(movement.x * scale, movement.y, movement.z * scale);
    }

    public void reset() {
        sampledTargetId = -1;
        sampledTargetTick = Integer.MIN_VALUE;
        lastSampledTargetPosition = null;
        smoothedTargetVelocity = Vec3.ZERO;
        smoothedTargetAcceleration = Vec3.ZERO;
        attackBaselineVelocity = Vec3.ZERO;
        anticipatedAttackVelocity = Vec3.ZERO;
        awaitingAttackVelocity = false;
        knockbackWaitTicks = 0;
    }

    public static Vec3 expectedPostAttackVelocity(Minecraft client, Player target) {
        Vec3 current = target.getDeltaMovement();
        double dx = target.getX() - client.player.getX();
        double dz = target.getZ() - client.player.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-6D) {
            return current;
        }
        double levels =
                Math.max(0.0D, client.player.getAttributeValue(Attributes.ATTACK_KNOCKBACK))
                        + (client.player.isSprinting() ? 1.0D : 0.0D);
        double impulse =
                levels
                        * 0.5D
                        * (1.0D
                                - Mth.clamp(
                                        target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE),
                                        0.0D,
                                        1.0D));
        if (impulse <= 1.0E-6D) {
            return current;
        }
        return new Vec3(
                current.x * 0.5D + dx / length * impulse,
                current.y,
                current.z * 0.5D + dz / length * impulse);
    }

    private static double horizontalDifference(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }
}
