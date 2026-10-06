package com.blanoir.moons.client.utils.prediction;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;

import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

/** Observed target motion with a short reservation for an expected attack impulse. */
public final class KnockbackPrediction {
    private static final double KNOCKBACK_SAMPLE_THRESHOLD = 0.08D;
    private int sampledTargetId = -1;
    private int sampledTargetTick = Integer.MIN_VALUE;
    private Vec3 lastSampledTargetPosition;
    private Vec3 smoothedTargetVelocity = VecMath.ZERO;
    private Vec3 smoothedTargetAcceleration = VecMath.ZERO;
    private Vec3 attackBaselineVelocity = VecMath.ZERO;
    private Vec3 anticipatedAttackVelocity = VecMath.ZERO;
    private boolean awaitingAttackVelocity;
    private int knockbackWaitTicks;

    public boolean matches(EntityPlayer target) {
        return target != null && sampledTargetId == target.getEntityId();
    }

    public Vec3 velocity() {
        return smoothedTargetVelocity;
    }

    public Vec3 acceleration() {
        return smoothedTargetAcceleration;
    }

    public void begin(Minecraft client, EntityPlayer target) {
        sampledTargetId = target.getEntityId();
        sampledTargetTick = target.ticksExisted;
        lastSampledTargetPosition = VecMath.position(target);
        attackBaselineVelocity = VecMath.motion(target);
        anticipatedAttackVelocity = expectedPostAttackVelocity(client, target);
        awaitingAttackVelocity =
                horizontalDifference(anticipatedAttackVelocity, attackBaselineVelocity)
                        > KNOCKBACK_SAMPLE_THRESHOLD;
        knockbackWaitTicks = 0;
        smoothedTargetVelocity =
                awaitingAttackVelocity ? anticipatedAttackVelocity : attackBaselineVelocity;
        smoothedTargetAcceleration = VecMath.ZERO;
    }

    /**
     * Combines observed position steps with the entity velocity. Position steps
     * receive more weight because remote-player delta movement can lag behind a
     * server knockback update. Acceleration is deliberately capped at prediction
     * time so one interpolation correction cannot throw the lava several cells.
     */
    public void observe(Minecraft client, EntityPlayer target) {
        if (target == null) {
            return;
        }
        if (sampledTargetId != target.getEntityId() || lastSampledTargetPosition == null) {
            begin(client, target);
            return;
        }
        if (sampledTargetTick == target.ticksExisted) {
            return;
        }

        int elapsedTicks = Math.max(1, target.ticksExisted - sampledTargetTick);
        Vec3 position = VecMath.position(target);
        Vec3 observed =
                VecMath.scale(position.subtract(lastSampledTargetPosition), 1.0D / elapsedTicks);
        Vec3 reported = VecMath.motion(target);
        Vec3 sample =
                limitHorizontal(
                        VecMath.scale(observed, 0.72D).add(VecMath.scale(reported, 0.28D)), 1.5D);
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
            smoothedTargetAcceleration = VecMath.ZERO;
        } else {
            awaitingAttackVelocity = false;
            double impulse = horizontalDifference(sample, previousVelocity);
            if (impulse >= KNOCKBACK_SAMPLE_THRESHOLD) {
                // A knockback packet is a velocity discontinuity, not a
                // multi-tick acceleration. Adopt it immediately so a large
                // sideways hit cannot be damped by the old 55/45 filter.
                smoothedTargetVelocity = sample;
                smoothedTargetAcceleration = VecMath.ZERO;
            } else {
                smoothedTargetVelocity =
                        VecMath.scale(previousVelocity, 0.55D).add(VecMath.scale(sample, 0.45D));
                Vec3 observedAcceleration = smoothedTargetVelocity.subtract(previousVelocity);
                smoothedTargetAcceleration =
                        VecMath.scale(smoothedTargetAcceleration, 0.65D)
                                .add(VecMath.scale(observedAcceleration, 0.35D));
            }
        }
        lastSampledTargetPosition = position;
        sampledTargetTick = target.ticksExisted;
    }

    private static Vec3 limitHorizontal(Vec3 movement, double maximum) {
        double horizontal =
                Math.sqrt(movement.xCoord * movement.xCoord + movement.zCoord * movement.zCoord);
        if (horizontal <= maximum || horizontal <= 1.0E-9D) {
            return movement;
        }
        double scale = maximum / horizontal;
        return new Vec3(movement.xCoord * scale, movement.yCoord, movement.zCoord * scale);
    }

    public void reset() {
        sampledTargetId = -1;
        sampledTargetTick = Integer.MIN_VALUE;
        lastSampledTargetPosition = null;
        smoothedTargetVelocity = VecMath.ZERO;
        smoothedTargetAcceleration = VecMath.ZERO;
        attackBaselineVelocity = VecMath.ZERO;
        anticipatedAttackVelocity = VecMath.ZERO;
        awaitingAttackVelocity = false;
        knockbackWaitTicks = 0;
    }

    public static Vec3 expectedPostAttackVelocity(Minecraft client, EntityPlayer target) {
        Vec3 current = VecMath.motion(target);
        double dx = target.posX - client.thePlayer.posX;
        double dz = target.posZ - client.thePlayer.posZ;
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-6D) {
            return current;
        }
        double levels =
                Math.max(0.0D, EnchantmentHelper.getKnockbackModifier(client.thePlayer))
                        + (client.thePlayer.isSprinting() ? 1.0D : 0.0D);
        double impulse =
                levels
                        * 0.5D
                        * (1.0D
                                - Mth.clamp(
                                        target.getEntityAttribute(
                                                        SharedMonsterAttributes.knockbackResistance)
                                                .getAttributeValue(),
                                        0.0D,
                                        1.0D));
        if (impulse <= 1.0E-6D) {
            return current;
        }
        return new Vec3(
                current.xCoord * 0.5D + dx / length * impulse,
                current.yCoord,
                current.zCoord * 0.5D + dz / length * impulse);
    }

    private static double horizontalDifference(Vec3 first, Vec3 second) {
        return Math.hypot(first.xCoord - second.xCoord, first.zCoord - second.zCoord);
    }
}
