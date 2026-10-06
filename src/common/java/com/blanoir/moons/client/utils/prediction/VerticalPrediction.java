package com.blanoir.moons.client.utils.prediction;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/** Jump-cycle and descent forecasts. Callers supply input intent and measured ground distance. */
public final class VerticalPrediction {
    private static final double GRAVITY = 0.08D;
    private static final double SLOW_FALLING_GRAVITY = 0.01D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double CONFIRMED_DESCENT_VELOCITY = -0.01D;

    private VerticalPrediction() {}

    public record VerticalState(
            double yOffset, boolean critical, boolean onGround, int jumpCycle) {}

    public record Snapshot(
            double velocityY,
            double fallDistance,
            boolean onGround,
            double jumpPower,
            Integer levitationAmplifier,
            boolean slowFalling) {}

    public static VerticalState[] forecast(
            LivingEntity player,
            int count,
            double groundDistance,
            boolean physicalJumpHeld,
            boolean effectiveJumpHeld) {
        var levitation = player.getEffect(MobEffects.LEVITATION);
        return forecast(
                new Snapshot(
                        player.getDeltaMovement().y,
                        player.fallDistance,
                        player.onGround(),
                        LivingEntity.BASE_JUMP_POWER + player.getJumpBoostPower(),
                        levitation == null ? null : levitation.getAmplifier(),
                        player.hasEffect(MobEffects.SLOW_FALLING)),
                count,
                groundDistance,
                physicalJumpHeld,
                effectiveJumpHeld);
    }

    public static VerticalState[] forecast(
            Snapshot snapshot,
            int count,
            double groundDistance,
            boolean physicalJumpHeld,
            boolean effectiveJumpHeld) {
        VerticalState[] states = new VerticalState[count + 1];
        double velocityY = snapshot.velocityY();
        double fallDistance = snapshot.fallDistance();
        double yOffset = 0.0D;
        boolean onGround = snapshot.onGround();
        double groundYOffset = -groundDistance;
        int jumpCycle = onGround ? 0 : 1;

        states[0] =
                new VerticalState(
                        yOffset,
                        fallDistance > 0.0D && velocityY < CONFIRMED_DESCENT_VELOCITY && !onGround,
                        onGround,
                        jumpCycle);

        for (int tick = 1; tick <= count; tick++) {
            boolean firstEffectiveJump = jumpCycle == 0 && effectiveJumpHeld;
            boolean repeatedPhysicalJump = jumpCycle > 0 && physicalJumpHeld;
            if (onGround && (firstEffectiveJump || repeatedPhysicalJump)) {
                velocityY = snapshot.jumpPower();
                fallDistance = 0.0D;
                onGround = false;
                jumpCycle++;
            }

            if (!onGround) {
                if (snapshot.levitationAmplifier() != null) {
                    int amplifier = snapshot.levitationAmplifier();
                    velocityY += (0.05D * (amplifier + 1) - velocityY) * 0.2D;
                    fallDistance = 0.0D;
                } else {
                    double gravity =
                            snapshot.slowFalling() && velocityY <= 0.0D
                                    ? SLOW_FALLING_GRAVITY
                                    : GRAVITY;
                    velocityY = (velocityY - gravity) * VERTICAL_DRAG;
                    if (velocityY < 0.0D) {
                        if (snapshot.slowFalling()) {
                            fallDistance = 0.0D;
                        } else {
                            fallDistance -= velocityY;
                        }
                    }
                    yOffset += velocityY;
                }

                if (velocityY <= 0.0D && yOffset <= groundYOffset) {
                    yOffset = groundYOffset;
                    velocityY = 0.0D;
                    fallDistance = 0.0D;
                    onGround = true;
                }
            }

            states[tick] =
                    new VerticalState(
                            yOffset,
                            fallDistance > 0.0D
                                    && velocityY < CONFIRMED_DESCENT_VELOCITY
                                    && !onGround,
                            onGround,
                            jumpCycle);
        }
        return states;
    }
}
