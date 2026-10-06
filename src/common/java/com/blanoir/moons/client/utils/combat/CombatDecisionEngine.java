package com.blanoir.moons.client.utils.combat;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.targeting.Targeting;
import com.blanoir.moons.client.utils.prediction.DamagePrediction;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.prediction.VerticalPrediction;
import com.blanoir.moons.client.utils.prediction.VerticalPrediction.VerticalState;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

public final class CombatDecisionEngine {

    private static final double REACH_TOLERANCE = 0.12D;
    private static final int CRITICAL_LANDING_MARGIN_TICKS = 1;

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

    /** Exact critical predicate in EntityPlayer.attackTargetEntityWithCurrentItem for 1.8.9. */
    public static boolean hasVanillaCriticalMovement(Minecraft client, Entity target) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (currentPlayer != null) {
            return valid(client, target)
                    && target instanceof EntityLivingBase
                    && currentPlayer.fallDistance > 0.0D
                    && !currentPlayer.onGround
                    && !currentPlayer.isOnLadder()
                    && !currentPlayer.isInWater()
                    && !currentPlayer.isPotionActive(Potion.blindness)
                    && !currentPlayer.isRiding();
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
                && VecMath.motion(client.thePlayer).yCoord < CONFIRMED_DESCENT_VELOCITY;
    }

    /**
     * True only during the final falling slice where a click started now can
     * be consumed after landing. Holding jump or having a JumpReset forecast
     * is deliberately not enough: those broad signals used to suppress
     * TriggerBot repeatedly even when no critical was actually reachable.
     */
    public static boolean isUnsafeLandingPhase(Minecraft client) {
        var currentPlayer = client == null ? null : client.thePlayer;
        return client != null
                && currentPlayer != null
                && client.theWorld != null
                && !currentPlayer.onGround
                && currentPlayer.fallDistance > 0.0F
                && !hasCriticalLandingMargin(client);
    }

    /** Conditions that cannot be fixed merely by waiting for the falling phase. */
    public static boolean allowsFutureCritical(Minecraft client, Entity target) {
        return valid(client, target)
                && target instanceof EntityLivingBase
                && !client.thePlayer.isOnLadder()
                && !client.thePlayer.isInWater()
                && !client.thePlayer.isPotionActive(Potion.blindness)
                && !client.thePlayer.isRiding();
    }

    public static boolean wouldCurrentNormalKill(Minecraft client, Entity target) {
        if (!valid(client, target) || !(target instanceof EntityLivingBase livingTarget)) {
            return false;
        }
        double targetHealth = livingTarget.getHealth() + livingTarget.getAbsorptionAmount();
        double charge = 1.0D;
        return DamagePrediction.meleeDamage(client, livingTarget, charge, false) >= targetHealth;
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
        if (!valid(client, target) || !(target instanceof EntityLivingBase livingTarget)) {
            return Decision.abort("invalid-target");
        }

        int horizon = Mth.clamp(lookaheadTicks, 0, MAX_FORECAST_TICKS);
        int earliest = Mth.clamp(earliestAttackTick, 0, horizon);
        double currentCharge = 1.0D;
        boolean currentReach = !CombatGeometry.outsideVanillaRange(client, target);
        boolean currentDamageable = livingTarget.hurtTime <= 0;

        // A real critical window is more valuable than a future cooldown or
        // synchronization preference. In particular, do not require the player
        // to remain critical on the following tick: that used to miss the final
        // falling slice of a jump attack.
        if (currentReach && currentDamageable && canCriticalNow(client, target)) {
            return new Decision(
                    AttackKind.CRITICAL,
                    0,
                    DamagePrediction.meleeDamage(client, livingTarget, currentCharge, true),
                    1.0D,
                    "vanilla-critical-now");
        }

        AttackKind currentKind =
                client.thePlayer.isSprinting() ? AttackKind.SPRINT : AttackKind.NORMAL;
        Decision current =
                earliest == 0 && currentReach && currentDamageable
                        ? new Decision(
                                currentKind,
                                0,
                                DamagePrediction.meleeDamage(
                                        client, livingTarget, currentCharge, false),
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
            EntityLivingBase target,
            int horizon,
            int earliest,
            boolean stopSprintForCritical,
            SyncPolicy syncPolicy) {
        if (horizon <= 0 || !allowsFutureCritical(client, target)) {
            return Decision.abort("critical-blocked");
        }
        int searchHorizon = horizon;
        int forecastHorizon =
                Math.min(
                        MAX_FORECAST_TICKS + CRITICAL_LANDING_MARGIN_TICKS,
                        searchHorizon + CRITICAL_LANDING_MARGIN_TICKS);
        VerticalState[] states = forecastVerticalStates(client, forecastHorizon);

        Vec3 playerVelocity = VecMath.motion(client.thePlayer);
        Vec3 targetVelocity = VecMath.motion(target);
        AxisAlignedBB currentTargetBox = CombatGeometry.box(client, target);
        double reach = CombatReach.vanillaEntityInteractionRange(client.thePlayer);

        for (int tick = Math.max(1, earliest); tick <= searchHorizon; tick++) {
            VerticalState vertical = states[tick];
            double charge = 1.0D;
            if (!vertical.critical()
                    || !hasForecastLandingMargin(states, tick)
                    || target.hurtTime > tick) {
                continue;
            }

            Vec3 futureEye =
                    client.thePlayer
                            .getPositionEyes(1F)
                            .addVector(
                                    playerVelocity.xCoord * tick,
                                    vertical.yOffset(),
                                    playerVelocity.zCoord * tick);
            AxisAlignedBB targetBox =
                    TrajectoryPrediction.linearBox(currentTargetBox, targetVelocity, tick);
            if (distanceToAabb(futureEye, targetBox) > reach + REACH_TOLERANCE) {
                continue;
            }

            double damage = DamagePrediction.meleeDamage(client, target, charge, true);
            double confidence =
                    Mth.clamp(
                            1.0D - VecMath.horizontalDistance(targetVelocity) * tick * 0.035D,
                            0.55D,
                            1.0D);
            return new Decision(
                    AttackKind.CRITICAL,
                    tick,
                    damage * confidence,
                    confidence,
                    vertical.jumpCycle() > 1 ? "next-jump-critical" : "predicted-vanilla-critical");
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

    private static boolean worthWaitingForCritical(
            Decision current, Decision futureCritical, boolean manualIntent) {
        double waitPenalty = futureCritical.ticksAhead() * (manualIntent ? 0.055D : 0.035D);
        double requiredGain = 1.08D + waitPenalty;
        return futureCritical.score() >= current.score() * requiredGain;
    }

    private static boolean hasActiveJumpCycle(Minecraft client) {
        return !client.thePlayer.onGround
                || com.blanoir.moons.client.input.PhysicalInput.isGameplayKeyDown(
                        client, client.gameSettings.keyBindJump)
                || CombatInputController.isDown(client, client.gameSettings.keyBindJump);
    }

    private static VerticalState[] forecastVerticalStates(Minecraft client, int count) {
        Vec3 start = VecMath.position(client.thePlayer).addVector(0.0D, 0.05D, 0.0D);
        double groundDistance =
                Math.max(
                        0.05D,
                        RaytraceUtils.distanceToBlock(
                                client, start, start.addVector(0.0D, -8.0D, 0.0D), 8.0D));
        boolean physicalJumpHeld =
                com.blanoir.moons.client.input.PhysicalInput.isGameplayKeyDown(
                        client, client.gameSettings.keyBindJump);
        return VerticalPrediction.forecast(
                client.thePlayer,
                count,
                groundDistance,
                physicalJumpHeld,
                physicalJumpHeld
                        || CombatInputController.isDown(client, client.gameSettings.keyBindJump));
    }

    private static boolean valid(Minecraft client, Entity target) {
        return client != null
                && client.thePlayer != null
                && client.theWorld != null
                && client.playerController != null
                && target != null
                && target.isEntityAlive()
                && Targeting.isEnemyPlayer(client, target);
    }

    private static double distanceToAabb(Vec3 point, AxisAlignedBB box) {
        double dx = Math.max(Math.max(box.minX - point.xCoord, 0.0D), point.xCoord - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.yCoord, 0.0D), point.yCoord - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.zCoord, 0.0D), point.zCoord - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
