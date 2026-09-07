package com.blanoir.moons.client.management.combat;

import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CombatDecisionEngine {
    private static final double FULL_STRENGTH = 0.90D;
    private static final double GRAVITY = 0.08D;
    private static final double SLOW_FALLING_GRAVITY = 0.01D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double REACH_TOLERANCE = 0.12D;
    private static final int CRITICAL_LANDING_MARGIN_TICKS = 1;
    private static final int IMMINENT_CRITICAL_GRACE_TICKS = 1;
    private static final int MAX_FORECAST_TICKS = 48;
    private static final double CONFIRMED_DESCENT_VELOCITY = -0.01D;

    public enum AttackKind {
        NORMAL,
        CRITICAL,
        SPRINT,
        NONE
    }

    public record Decision(
            AttackKind attackKind, int ticksAhead, double score, double confidence, String reason) {
        public boolean attackNow() {
            return attackKind != AttackKind.NONE && ticksAhead == 0;
        }

        public boolean shouldWait() {
            return attackKind != AttackKind.NONE && ticksAhead > 0;
        }

        public static Decision abort(String reason) {
            return new Decision(AttackKind.NONE, 0, Double.NEGATIVE_INFINITY, 0.0D, reason);
        }
    }

    /** Controls cooldown/jump phase alignment without changing vanilla crit rules. */
    public record SyncPolicy(
            boolean enabled, int currentOverchargeTicks, int maxOverchargeTicks, int cycles) {
        public static SyncPolicy disabled() {
            return new SyncPolicy(false, 0, 0, 1);
        }
    }

    private CombatDecisionEngine() {}

    /** Exact movement/target portion of Player.canCriticalAttack in MC 26.1.2. */
    public static boolean hasVanillaCriticalMovement(Minecraft client, Entity target) {
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer != null) {
            return valid(client, target)
                    && target instanceof LivingEntity
                    && currentPlayer.fallDistance > 0.0D
                    && !currentPlayer.onGround()
                    && !currentPlayer.onClimbable()
                    && !currentPlayer.isInWater()
                    && !currentPlayer.isMobilityRestricted()
                    && !currentPlayer.isPassenger()
                    && !currentPlayer.isSprinting();
        }
        return false;
    }

    /**
     * Server-safe execution predicate. Vanilla uses fallDistance, but also
     * requiring negative vertical velocity prevents a stale rising state from
     * being treated as the start of a critical while repeatedly jumping.
     */
    public static boolean canCriticalNow(Minecraft client, Entity target) {
        return hasVanillaCriticalMovement(client, target)
                && client.player.getDeltaMovement().y < CONFIRMED_DESCENT_VELOCITY
                && client.player.getAttackStrengthScale(0.5F) > FULL_STRENGTH;
    }

    /**
     * True only during the final falling slice where a click started now can
     * be consumed after landing. Holding jump or having a JumpReset forecast
     * is deliberately not enough: those broad signals used to suppress
     * TriggerBot repeatedly even when no critical was actually reachable.
     */
    public static boolean isUnsafeLandingPhase(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        return client != null
                && currentPlayer != null
                && client.level != null
                && !currentPlayer.onGround()
                && currentPlayer.fallDistance > 0.0F
                && !hasCriticalLandingMargin(client);
    }

    /** Conditions that cannot be fixed merely by waiting for the falling phase. */
    public static boolean allowsFutureCritical(Minecraft client, Entity target) {
        return valid(client, target)
                && target instanceof LivingEntity
                && !client.player.onClimbable()
                && !client.player.isInWater()
                && !client.player.isMobilityRestricted()
                && !client.player.isPassenger();
    }

    public static boolean wouldCurrentNormalKill(Minecraft client, Entity target) {
        if (!valid(client, target) || !(target instanceof LivingEntity livingTarget)) {
            return false;
        }
        double targetHealth = livingTarget.getHealth() + livingTarget.getAbsorptionAmount();
        double charge = client.player.getAttackStrengthScale(0.5F);
        return estimateDamage(client, livingTarget, charge, false) >= targetHealth;
    }

    public static Decision evaluate(
            Minecraft client,
            Entity target,
            int lookaheadTicks,
            boolean stopSprintForCritical,
            boolean manualIntent) {
        return evaluate(
                client,
                target,
                lookaheadTicks,
                0,
                stopSprintForCritical,
                manualIntent,
                SyncPolicy.disabled());
    }

    public static Decision evaluate(
            Minecraft client,
            Entity target,
            int lookaheadTicks,
            int earliestAttackTick,
            boolean stopSprintForCritical,
            boolean manualIntent) {
        return evaluate(
                client,
                target,
                lookaheadTicks,
                earliestAttackTick,
                stopSprintForCritical,
                manualIntent,
                SyncPolicy.disabled());
    }

    public static Decision evaluate(
            Minecraft client,
            Entity target,
            int lookaheadTicks,
            int earliestAttackTick,
            boolean stopSprintForCritical,
            boolean manualIntent,
            SyncPolicy syncPolicy) {
        if (!valid(client, target) || !(target instanceof LivingEntity livingTarget)) {
            return Decision.abort("invalid-target");
        }

        int horizon = Mth.clamp(lookaheadTicks, 0, MAX_FORECAST_TICKS);
        int earliest = Mth.clamp(earliestAttackTick, 0, horizon);
        double currentCharge = client.player.getAttackStrengthScale(0.5F);
        boolean currentReach = Targeting.isWithinInteractionRange(client, target);
        boolean currentDamageable = livingTarget.hurtTime <= 0;

        // A real critical window is more valuable than a future cooldown or
        // synchronization preference. In particular, do not require the player
        // to remain critical on the following tick: that used to miss the final
        // falling slice of a jump attack.
        if (currentReach && currentDamageable && canCriticalNow(client, target)) {
            return new Decision(
                    AttackKind.CRITICAL,
                    0,
                    estimateDamage(client, livingTarget, currentCharge, true),
                    1.0D,
                    "vanilla-critical-now");
        }

        AttackKind currentKind =
                client.player.isSprinting() ? AttackKind.SPRINT : AttackKind.NORMAL;
        Decision current =
                earliest == 0 && currentReach && currentDamageable
                        ? new Decision(
                                currentKind,
                                0,
                                estimateDamage(client, livingTarget, currentCharge, false),
                                1.0D,
                                "vanilla-normal-now")
                        : Decision.abort("normal-not-ready");

        if (current.attackNow() && wouldCurrentNormalKill(client, target)) {
            return new Decision(current.attackKind(), 0, current.score(), 1.0D, "normal-lethal");
        }

        Decision futureCritical =
                findFutureCritical(
                        client,
                        livingTarget,
                        horizon,
                        earliest,
                        stopSprintForCritical,
                        syncPolicy == null ? SyncPolicy.disabled() : syncPolicy);
        if (futureCritical.shouldWait()) {
            if (!current.attackNow()
                    || !manualIntent
                    || hasActiveJumpCycle(client)
                    || worthWaitingForCritical(current, futureCritical, true)) {
                return futureCritical;
            }
        }

        return current.attackNow() ? current : Decision.abort("no-near-critical");
    }

    private static Decision findFutureCritical(
            Minecraft client,
            LivingEntity target,
            int horizon,
            int earliest,
            boolean stopSprintForCritical,
            SyncPolicy syncPolicy) {
        if (horizon <= 0 || !allowsFutureCritical(client, target)) {
            return Decision.abort("critical-blocked");
        }
        if (client.player.isSprinting() && !stopSprintForCritical) {
            return Decision.abort("sprinting");
        }

        double attackDelay = Math.max(1.0D, client.player.getCurrentItemAttackStrengthDelay());
        double currentCharge = client.player.getAttackStrengthScale(0.5F);
        boolean physicalJumpHeld =
                CombatInputController.isPhysicallyDown(client, client.options.keyJump);
        boolean jumpPhaseActive =
                !client.player.onGround()
                        || physicalJumpHeld
                        || CombatInputController.isDown(client, client.options.keyJump);
        int searchHorizon = horizon;
        if (syncPolicy.enabled() && physicalJumpHeld && syncPolicy.cycles() > 1) {
            searchHorizon =
                    Math.min(
                            MAX_FORECAST_TICKS,
                            horizon
                                    + (int) Math.ceil(attackDelay) * (syncPolicy.cycles() - 1)
                                    + Math.max(0, syncPolicy.maxOverchargeTicks())
                                    + 8);
        }
        int forecastHorizon =
                Math.min(
                        MAX_FORECAST_TICKS + CRITICAL_LANDING_MARGIN_TICKS,
                        searchHorizon + CRITICAL_LANDING_MARGIN_TICKS);
        VerticalState[] states = forecastVerticalStates(client, forecastHorizon);
        int activeJumpCycle = Math.max(1, states[0].jumpCycle());
        Vec3 playerVelocity = client.player.getDeltaMovement();
        Vec3 targetVelocity = target.getDeltaMovement();
        double reach =
                Math.max(
                        1.0D, client.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));

        for (int tick = Math.max(1, earliest); tick <= searchHorizon; tick++) {
            VerticalState vertical = states[tick];
            double charge = Mth.clamp(currentCharge + tick / attackDelay, 0.0D, 1.0D);
            if (!vertical.critical()
                    || !hasForecastLandingMargin(states, tick)
                    || charge <= FULL_STRENGTH
                    || target.hurtTime > tick) {
                continue;
            }

            int projectedOvercharge =
                    projectedOverchargeTicks(
                            currentCharge,
                            attackDelay,
                            tick,
                            Math.max(0, syncPolicy.currentOverchargeTicks()));
            if (syncPolicy.enabled()
                    && jumpPhaseActive
                    && vertical.jumpCycle() > activeJumpCycle
                    && tick > IMMINENT_CRITICAL_GRACE_TICKS
                    && projectedOvercharge > Math.max(0, syncPolicy.maxOverchargeTicks())) {
                // Overcharge limits synchronization across later jump cycles.
                // Never use it to turn an already committed current jump into
                // an immediate rising normal hit.
                continue;
            }

            Vec3 futureEye =
                    client.player
                            .getEyePosition()
                            .add(
                                    playerVelocity.x * tick,
                                    vertical.yOffset(),
                                    playerVelocity.z * tick);
            AABB targetBox = target.getBoundingBox().move(targetVelocity.scale(tick));
            if (distanceToAabb(futureEye, targetBox) > reach + REACH_TOLERANCE) {
                continue;
            }

            double damage = estimateDamage(client, target, charge, true);
            double confidence =
                    Mth.clamp(
                            1.0D - targetVelocity.horizontalDistance() * tick * 0.035D,
                            0.55D,
                            1.0D);
            int syncedFollowUps =
                    syncPolicy.enabled() && physicalJumpHeld
                            ? countSyncedFollowUps(
                                    states,
                                    tick,
                                    attackDelay,
                                    Math.max(1, syncPolicy.cycles()),
                                    Math.max(0, syncPolicy.maxOverchargeTicks()))
                            : 0;
            double continuityBonus = damage * confidence * syncedFollowUps * 0.10D;
            return new Decision(
                    AttackKind.CRITICAL,
                    tick,
                    damage * confidence + continuityBonus,
                    confidence,
                    syncedFollowUps > 0
                            ? "synced-critical-chain"
                            : vertical.jumpCycle() > 1
                                    ? "next-jump-critical"
                                    : "predicted-vanilla-critical");
        }
        return Decision.abort("no-near-critical");
    }

    private static boolean hasCriticalLandingMargin(Minecraft client) {
        VerticalState[] states = forecastVerticalStates(client, CRITICAL_LANDING_MARGIN_TICKS);
        return hasForecastLandingMargin(states, 0);
    }

    private static boolean hasForecastLandingMargin(VerticalState[] states, int criticalTick) {
        int marginTick = criticalTick + CRITICAL_LANDING_MARGIN_TICKS;
        return marginTick < states.length && states[marginTick].critical();
    }

    private static int projectedOverchargeTicks(
            double currentCharge, double attackDelay, int futureTick, int currentOverchargeTicks) {
        if (currentCharge >= 0.999D) {
            return currentOverchargeTicks + futureTick;
        }
        int ticksUntilFull = Math.max(0, (int) Math.ceil((1.0D - currentCharge) * attackDelay));
        return Math.max(0, futureTick - ticksUntilFull);
    }

    private static int countSyncedFollowUps(
            VerticalState[] states,
            int firstAttackTick,
            double attackDelay,
            int cycles,
            int maxOverchargeTicks) {
        int matches = 0;
        int previousAttackTick = firstAttackTick;
        int fullCooldownTicks = Math.max(1, (int) Math.ceil(attackDelay));

        for (int cycle = 1; cycle < cycles; cycle++) {
            int fullyChargedTick = previousAttackTick + fullCooldownTicks;
            int latestTick = Math.min(states.length - 1, fullyChargedTick + maxOverchargeTicks);
            int matchedTick = -1;
            for (int tick = fullyChargedTick; tick <= latestTick; tick++) {
                if (states[tick].critical()) {
                    matchedTick = tick;
                    break;
                }
            }
            if (matchedTick < 0) {
                break;
            }
            matches++;
            previousAttackTick = matchedTick;
        }
        return matches;
    }

    private static boolean worthWaitingForCritical(
            Decision current, Decision futureCritical, boolean manualIntent) {
        double waitPenalty = futureCritical.ticksAhead() * (manualIntent ? 0.055D : 0.035D);
        double requiredGain = 1.08D + waitPenalty;
        return futureCritical.score() >= current.score() * requiredGain;
    }

    private static boolean hasActiveJumpCycle(Minecraft client) {
        return !client.player.onGround()
                || CombatInputController.isPhysicallyDown(client, client.options.keyJump)
                || CombatInputController.isDown(client, client.options.keyJump);
    }

    private static VerticalState[] forecastVerticalStates(Minecraft client, int count) {
        VerticalState[] states = new VerticalState[count + 1];
        double velocityY = client.player.getDeltaMovement().y;
        double fallDistance = client.player.fallDistance;
        double yOffset = 0.0D;
        boolean onGround = client.player.onGround();
        double groundDistance = measureGroundDistance(client);
        double groundYOffset = -groundDistance;
        boolean physicalJumpHeld =
                CombatInputController.isPhysicallyDown(client, client.options.keyJump);
        boolean effectiveJumpHeld =
                physicalJumpHeld || CombatInputController.isDown(client, client.options.keyJump);
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
                velocityY = LivingEntity.BASE_JUMP_POWER + client.player.getJumpBoostPower();
                fallDistance = 0.0D;
                onGround = false;
                jumpCycle++;
            }

            if (!onGround) {
                var levitation = client.player.getEffect(MobEffects.LEVITATION);
                if (levitation != null) {
                    int amplifier = levitation.getAmplifier();
                    velocityY += (0.05D * (amplifier + 1) - velocityY) * 0.2D;
                    fallDistance = 0.0D;
                } else {
                    double gravity =
                            client.player.hasEffect(MobEffects.SLOW_FALLING) && velocityY <= 0.0D
                                    ? SLOW_FALLING_GRAVITY
                                    : GRAVITY;
                    velocityY = (velocityY - gravity) * VERTICAL_DRAG;
                    if (velocityY < 0.0D) {
                        if (client.player.hasEffect(MobEffects.SLOW_FALLING)) {
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

    private static double measureGroundDistance(Minecraft client) {
        Vec3 start = client.player.position().add(0.0D, 0.05D, 0.0D);
        Vec3 end = start.add(0.0D, -8.0D, 0.0D);
        return Math.max(0.05D, RaytraceUtils.distanceToBlock(client, start, end, 8.0D));
    }

    private static double estimateDamage(
            Minecraft client, LivingEntity target, double charge, boolean critical) {
        double attackDamage =
                Math.max(0.0D, client.player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        double rawDamage = attackDamage * (0.2D + charge * charge * 0.8D);
        if (critical) {
            rawDamage *= 1.5D;
        }

        double armor = Math.max(0.0D, target.getArmorValue());
        double toughness = Math.max(0.0D, target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        double armorPoints =
                Math.min(
                        20.0D,
                        Math.max(armor / 5.0D, armor - rawDamage / (2.0D + toughness / 4.0D)));
        double damage = rawDamage * (1.0D - armorPoints / 25.0D);
        var resistance = target.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            int amplifier = resistance.getAmplifier() + 1;
            damage *= Math.max(0.0D, 1.0D - amplifier * 0.2D);
        }
        return Math.max(0.0D, damage);
    }

    private static boolean valid(Minecraft client, Entity target) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && target != null
                && target.isAlive()
                && Targeting.isEnemyPlayer(client, target);
    }

    private static double distanceToAabb(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record VerticalState(
            double yOffset, boolean critical, boolean onGround, int jumpCycle) {}
}
