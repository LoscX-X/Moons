package com.blanoir.tempos.client.module.impl.combat.critical.mode;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.manager.combat.CombatDecisionEngine;
import com.blanoir.tempos.client.manager.combat.CombatGeometry;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SubFeature;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;

import java.util.List;

/**
 * Delays an attack only when the vanilla critical predicate is expected to
 * become true inside a short, configured window. Every execution is rechecked
 * against the current crosshair and reach, so a stale prediction cannot attack.
 */
public final class Predict extends SubFeature.Switchable<Critical> {
    private static final long SAME_TICK_GUARD_NANOS = 5_000_000L;
    private static final int MAX_HORIZON_TICKS = 12;
    private static final int MAX_TRIGGER_WAIT_TICKS = 12;

    private final DoubleSetting window =
            new DoubleSetting.Builder()
                    .name("predictcritical.window")
                    .defaultValue(0.30D)
                    .range(0.05D, 0.60D)
                    .build();

    private final BooleanSetting stopSprint =
            new BooleanSetting.Builder()
                    .name("predictcritical.stopSprint")
                    .defaultValue(true)
                    .build();

    private final BooleanSetting syncEnabled =
            new BooleanSetting.Builder().name("predictcritical.sync").defaultValue(true).build();

    private final IntSetting maxOverchargeTicks =
            new IntSetting.Builder()
                    .name("predictcritical.overcharge")
                    .defaultValue(2)
                    .range(0, 6)
                    .build();

    private final IntSetting syncCycles =
            new IntSetting.Builder()
                    .name("predictcritical.cycles")
                    .defaultValue(2)
                    .range(1, 3)
                    .build();

    private int targetId = -1;
    private int remainingTicks;
    private int earliestAttackTicks;
    private boolean queuedManualIntent;
    private boolean queuedThroughBlock;
    private long lastAttackNanos;
    private boolean cooldownWasFull;
    private int currentOverchargeTicks;

    /** Old TriggerBot contract represented as absolute ticks in the gate-based pipeline. */
    private int automaticPlanTargetId = -1;

    private int automaticPlanDeadlineTick = Integer.MIN_VALUE;
    private int automaticPlanEarliestTick = Integer.MIN_VALUE;
    private CombatDecisionEngine.Decision plannedDecision =
            CombatDecisionEngine.Decision.abort("idle");

    public Predict(Critical parent) {
        super(parent);
    }

    public void addSettingsTo(List<Setting> settings) {
        var parent = parent();
        settings.add(
                window.describeStored("window", "Prediction window", .01, this::setWindow)
                        .visibleWhen(parent::predictMode));
        settings.add(
                stopSprint
                        .describeStored("stop_sprint", "Stop sprint", this::setStopSprint)
                        .visibleWhen(parent::predictMode));
        settings.add(
                syncEnabled
                        .describeStored("sync", "Sync", this::setSyncEnabled)
                        .visibleWhen(parent::predictMode));
        settings.add(
                maxOverchargeTicks
                        .describeStored(
                                "overcharge", "Overcharge ticks", 1, this::setMaxOverchargeTicks)
                        .visibleWhen(parent::predictMode));
        settings.add(
                syncCycles
                        .describeStored("cycles", "Sync cycles", 1, this::setSyncCycles)
                        .visibleWhen(parent::predictMode));
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register(
                "Predict.tick",
                event -> {
                    Minecraft client = event.client();
                    updateCooldownPhase(client);
                    tick(client);
                });
    }

    private void tick(Minecraft client) {
        var currentLevel = client == null ? null : client.level;
        if (client == null || unavailable(client)) {
            clear(client);
            return;
        }

        if (targetId == -1) {
            consumeNewManualIntent(client);
            return;
        }

        if (currentLevel != null) {
            Entity target = currentLevel.getEntity(targetId);
            if (!Targeting.isEnemyPlayer(client, target)) {
                clear(client);
                return;
            }
            if (!queuedManualIntent && CombatGeometry.outsideVanillaRange(client, target)) {
                clear(client);
                return;
            }

            Entity cameraTarget = crosshairEnemy(client, queuedThroughBlock);
            if (cameraTarget != null && client.options.keyAttack.consumeClick()) {
                if (cameraTarget != target) {
                    clear(client);
                    beginIntent(client, cameraTarget, true, 0, false);
                    return;
                }
                if (isImmediatelyAttackable(client, target, queuedThroughBlock)) {
                    executeAttack(
                            client,
                            target,
                            CombatDecisionEngine.AttackKind.NORMAL,
                            queuedThroughBlock,
                            true);
                    return;
                }
            }

            if (cameraTarget != target) {
                clear(client);
                return;
            }

            CombatInputController.suppressAttack(
                    client, CombatInputController.Owner.PREDICT_CRITICAL);

            // A queued intent is revisited on the next client tick, so one tick of
            // both the window and TriggerBot's cooldown lead has already elapsed.
            remainingTicks--;
            if (earliestAttackTicks > 0) {
                earliestAttackTicks--;
            }
            if (remainingTicks < 0) {
                fallbackOrClear(client, target);
                return;
            }

            plannedDecision =
                    CombatDecisionEngine.evaluate(
                            client,
                            target,
                            remainingTicks,
                            earliestAttackTicks,
                            stopSprint.get(),
                            queuedManualIntent,
                            syncPolicy());

            if (!queuedManualIntent
                    && plannedDecision.shouldWait()
                    && plannedDecision.ticksAhead() > remainingTicks) {
                // Do not let an automatic reservation slide from the current jump
                // into a later jump cycle as its original deadline counts down.
                // TriggerBot can immediately fall back to its normal cooldown path.
                clear(client);
                return;
            }

            if (plannedDecision.attackNow()) {
                if (!queuedManualIntent
                        && plannedDecision.attackKind() != CombatDecisionEngine.AttackKind.CRITICAL
                        && CombatDecisionEngine.isUnsafeLandingPhase(client)) {
                    clear(client);
                    return;
                }
                releasePredictionMovement(client);
                boolean executable = isImmediatelyAttackable(client, target, queuedThroughBlock);
                if (executable) {
                    executeAttack(
                            client,
                            target,
                            plannedDecision.attackKind(),
                            queuedThroughBlock,
                            queuedManualIntent);
                } else {
                    clear(client);
                }
                return;
            }

            if (!plannedDecision.shouldWait()) {
                fallbackOrClear(client, target);
                return;
            }

            prepareMovementForPlan(client, plannedDecision);
        }
    }

    private void consumeNewManualIntent(Minecraft client) {
        if (CombatModuleCoordinator.isAuraActive()) {
            return;
        }
        Entity target = crosshairEnemy(client, false);
        if (target == null || !client.options.keyAttack.consumeClick()) {
            return;
        }

        CombatInputController.suppressAttack(client, CombatInputController.Owner.PREDICT_CRITICAL);
        beginIntent(client, target, true, 0, false);
    }

    private Critical.AttackDecision beginIntent(
            Minecraft client,
            Entity target,
            boolean manualIntent,
            int earliestAttackTick,
            boolean throughBlock) {
        if (!manualIntent && CombatGeometry.outsideVanillaRange(client, target)) {
            return Critical.AttackDecision.ABORTED;
        }
        int horizon = configuredHorizonTicks();
        CombatDecisionEngine.Decision decision =
                CombatDecisionEngine.evaluate(
                        client,
                        target,
                        horizon,
                        Math.max(0, earliestAttackTick),
                        stopSprint.get(),
                        manualIntent,
                        syncPolicy());
        plannedDecision = decision;

        if (!manualIntent && decision.shouldWait() && decision.ticksAhead() > horizon) {
            // Sync forecasting may inspect later jump cycles beyond the
            // configured window.  Those are useful for scoring, but reserving
            // an automatic attack that long creates a conspicuous dead period.
            clear(client);
            return Critical.AttackDecision.NONE;
        }

        // An early TriggerBot request is only allowed to reserve a real future
        // critical. Ordinary cooldown timing remains TriggerBot's responsibility.
        if (!manualIntent
                && earliestAttackTick > 0
                && decision.attackKind() != CombatDecisionEngine.AttackKind.CRITICAL) {
            clear(client);
            return Critical.AttackDecision.NONE;
        }

        if (decision.attackNow()) {
            if (!manualIntent
                    && decision.attackKind() != CombatDecisionEngine.AttackKind.CRITICAL
                    && CombatDecisionEngine.isUnsafeLandingPhase(client)) {
                clear(client);
                return Critical.AttackDecision.ABORTED;
            }
            executeAttack(client, target, decision.attackKind(), throughBlock, manualIntent);
            return Critical.AttackDecision.ATTACKED;
        }
        if (decision.shouldWait()) {
            int intentHorizon = Math.max(horizon, decision.ticksAhead() + 1);
            if (!manualIntent) {
                intentHorizon = Math.min(intentHorizon, MAX_TRIGGER_WAIT_TICKS);
            }
            queue(client, target, manualIntent, throughBlock, intentHorizon, earliestAttackTick);
            prepareMovementForPlan(client, decision);
            return Critical.AttackDecision.DEFERRED;
        }
        if (manualIntent) {
            executeAttack(
                    client, target, CombatDecisionEngine.AttackKind.NORMAL, throughBlock, true);
            return Critical.AttackDecision.ATTACKED;
        }

        clear(client);
        return CombatDecisionEngine.isUnsafeLandingPhase(client)
                ? Critical.AttackDecision.ABORTED
                : Critical.AttackDecision.NONE;
    }

    public Critical.AttackDecision requestAttack(Minecraft client, Entity target) {
        return requestAttack(client, target, 0, false);
    }

    public Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return requestAttack(client, target, earliestAttackTick, false);
    }

    public Critical.AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        if (!parent().configuredEnabled() || unavailable(client)) {
            return Critical.AttackDecision.NONE;
        }
        if (targetId != -1
                || (PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack))
                || System.nanoTime() - lastAttackNanos < SAME_TICK_GUARD_NANOS) {
            return Critical.AttackDecision.DEFERRED;
        }
        if (!Targeting.isEnemyPlayer(client, target)) {
            return Critical.AttackDecision.ABORTED;
        }
        return beginIntent(client, target, false, Math.max(0, earliestAttackTick), throughBlock);
    }

    /**
     * Prediction-mode gate for SilentAura. SilentAura keeps ownership of its
     * packet ray and actual attack; Critical only decides whether the hit should
     * happen now or wait for a predicted vanilla critical tick.
     */
    public Critical.AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        if (!parent().configuredEnabled()) {
            clearAutomaticPlan();
            return Critical.AutomaticAttackGate.ALLOW;
        }
        // Predict is a player-critical implementation; configured SilentAura
        // mob targets keep their ordinary attack behavior.
        if (!Targeting.isEnemyPlayer(client, target)) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            return Critical.AutomaticAttackGate.ALLOW;
        }
        if (client == null || unavailable(client)) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            return Critical.AutomaticAttackGate.BLOCK;
        }
        if (targetId != -1 || System.nanoTime() - lastAttackNanos < SAME_TICK_GUARD_NANOS) {
            return Critical.AutomaticAttackGate.WAIT;
        }

        if (automaticPlanTargetId != -1 && automaticPlanTargetId != target.getId()) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
        }
        if (automaticPlanTargetId == target.getId()) {
            return advanceAutomaticPlan(client, target);
        }

        int horizon = configuredHorizonTicks();
        int earliest = Math.max(0, earliestAttackTick);
        CombatDecisionEngine.Decision decision =
                CombatDecisionEngine.evaluate(
                        client, target, horizon, earliest, stopSprint.get(), false, syncPolicy());
        plannedDecision = decision;
        // Keep the same division of responsibility as TriggerBot: Critical may
        // reserve a future critical, while ordinary cooldown waiting remains
        // owned by the attacking module.
        if (decision.shouldWait() && decision.ticksAhead() > horizon) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            return Critical.AutomaticAttackGate.ALLOW;
        }
        if (earliest > 0 && decision.attackKind() != CombatDecisionEngine.AttackKind.CRITICAL) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            return Critical.AutomaticAttackGate.ALLOW;
        }
        if (decision.attackNow()) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            if (decision.attackKind() != CombatDecisionEngine.AttackKind.CRITICAL
                    && CombatDecisionEngine.isUnsafeLandingPhase(client)) {
                return Critical.AutomaticAttackGate.BLOCK;
            }
            // Semantic equivalent of old requestAttack() == ATTACKED. The
            // caller still owns the current packet-ray and same-tick dispatch.
            return Critical.AutomaticAttackGate.ATTACK;
        }
        if (decision.shouldWait() && decision.ticksAhead() <= horizon) {
            int now = client.player.tickCount;
            int intentHorizon = Math.max(horizon, decision.ticksAhead() + 1);
            automaticPlanTargetId = target.getId();
            automaticPlanDeadlineTick =
                    now + Math.max(1, Math.min(intentHorizon, MAX_TRIGGER_WAIT_TICKS));
            automaticPlanEarliestTick = now + earliest;
            prepareMovementForPlan(client, decision);
            return Critical.AutomaticAttackGate.WAIT;
        }

        clearAutomaticPlan();
        releasePredictionMovement(client);
        return CombatDecisionEngine.isUnsafeLandingPhase(client)
                ? Critical.AutomaticAttackGate.BLOCK
                : Critical.AutomaticAttackGate.ALLOW;
    }

    /**
     * Advances the old DEFERRED queue without handing final packet-ray dispatch
     * to Critical. The remaining horizon and cooldown lead only count down;
     * they are never replaced by a fresh rolling window.
     */
    private Critical.AutomaticAttackGate advanceAutomaticPlan(Minecraft client, Entity target) {
        int now = client.player.tickCount;
        int remaining = automaticPlanDeadlineTick - now;
        int earliest = Math.max(0, automaticPlanEarliestTick - now);
        if (remaining < 0) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            plannedDecision = CombatDecisionEngine.Decision.abort("automatic-timeout");
            // Old automatic fallback clears and yields this tick. It never
            // converts an expired critical reservation into a surprise normal hit.
            return Critical.AutomaticAttackGate.WAIT;
        }

        CombatDecisionEngine.Decision decision =
                CombatDecisionEngine.evaluate(
                        client, target, remaining, earliest, stopSprint.get(), false, syncPolicy());
        plannedDecision = decision;

        if (decision.shouldWait() && decision.ticksAhead() > remaining) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            return Critical.AutomaticAttackGate.WAIT;
        }
        if (decision.attackNow()) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            if (decision.attackKind() != CombatDecisionEngine.AttackKind.CRITICAL
                    && CombatDecisionEngine.isUnsafeLandingPhase(client)) {
                return Critical.AutomaticAttackGate.BLOCK;
            }
            // A completed DEFERRED reservation is old ATTACKED, not a fresh
            // ALLOW that may be sampled by TriggerBot's charge gate again.
            return Critical.AutomaticAttackGate.ATTACK;
        }
        if (!decision.shouldWait()) {
            clearAutomaticPlan();
            releasePredictionMovement(client);
            return Critical.AutomaticAttackGate.WAIT;
        }

        prepareMovementForPlan(client, decision);
        return Critical.AutomaticAttackGate.WAIT;
    }

    public void cancelSilentAuraPrediction(Minecraft client) {
        clearAutomaticPlan();
        if (targetId == -1) {
            releasePredictionMovement(client);
            plannedDecision = CombatDecisionEngine.Decision.abort("idle");
        }
    }

    private void clearAutomaticPlan() {
        automaticPlanTargetId = -1;
        automaticPlanDeadlineTick = Integer.MIN_VALUE;
        automaticPlanEarliestTick = Integer.MIN_VALUE;
    }

    private void queue(
            Minecraft client,
            Entity target,
            boolean manualIntent,
            boolean throughBlock,
            int horizon,
            int earliestAttackTick) {
        targetId = target.getId();
        remainingTicks = Math.max(1, horizon);
        earliestAttackTicks = Math.max(0, earliestAttackTick);
        queuedManualIntent = manualIntent;
        queuedThroughBlock = throughBlock;
        CombatInputController.suppressAttack(client, CombatInputController.Owner.PREDICT_CRITICAL);
    }

    private void prepareMovementForPlan(Minecraft client, CombatDecisionEngine.Decision decision) {
        var currentPlayer = client == null ? null : client.player;
        boolean stopSprintNow =
                stopSprint.get()
                        && decision.attackKind() == CombatDecisionEngine.AttackKind.CRITICAL
                        && decision.ticksAhead() <= 1;
        if (stopSprintNow) {
            CombatInputController.suppressSprint(
                    client, CombatInputController.Owner.PREDICT_CRITICAL);
            CombatInputController.suppressForward(
                    client, CombatInputController.Owner.PREDICT_CRITICAL);
            // Ensure the STOP_SPRINTING state is submitted before the planned
            // falling attack; the Silent-only validator no longer blocks
            // ordinary running attacks globally.
            if (currentPlayer != null && currentPlayer.isSprinting()) {
                currentPlayer.setSprinting(false);
            }
        } else {
            releasePredictionMovement(client);
        }
    }

    private void releasePredictionMovement(Minecraft client) {
        CombatInputController.releaseSprint(client, CombatInputController.Owner.PREDICT_CRITICAL);
        CombatInputController.releaseForward(client, CombatInputController.Owner.PREDICT_CRITICAL);
    }

    private boolean isImmediatelyAttackable(Minecraft client, Entity target, boolean throughBlock) {
        return crosshairEnemy(client, throughBlock) == target;
    }

    private Entity crosshairEnemy(Minecraft client, boolean throughBlock) {
        Entity target =
                CombatGeometry.findTargetOnRay(
                        client,
                        client.player.getEyePosition(),
                        client.player.getViewVector(1.0F),
                        CombatReach.vanillaEntityInteractionRange(client.player),
                        EntitySelector.CAN_BE_PICKED,
                        throughBlock);
        return Targeting.isEnemyPlayer(client, target) ? target : null;
    }

    private void fallbackOrClear(Minecraft client, Entity target) {
        // TriggerBot-owned reservations must never turn into an unplanned
        // normal hit at the end of a critical window. It will ask again on a
        // later tick. Manual clicks retain the responsive normal fallback.
        if (queuedManualIntent && isImmediatelyAttackable(client, target, queuedThroughBlock)) {
            executeAttack(
                    client,
                    target,
                    CombatDecisionEngine.AttackKind.NORMAL,
                    queuedThroughBlock,
                    true);
        } else {
            clear(client);
        }
    }

    private void executeAttack(
            Minecraft client,
            Entity target,
            CombatDecisionEngine.AttackKind attackKind,
            boolean throughBlock,
            boolean manualIntent) {
        lastAttackNanos = System.nanoTime();
        cooldownWasFull = false;
        currentOverchargeTicks = 0;
        CombatInputController.releaseAll(client, CombatInputController.Owner.PREDICT_CRITICAL);
        CombatInputController.attackTarget(client, target, throughBlock);
        resetState();
    }

    private boolean unavailable(Minecraft client) {
        return CombatModuleCoordinator.automationSuspended() || !parent().predictMode()
                || !parent().configuredEnabled()
                || client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || MinecraftClientAccess.screen(client) != null;
    }

    private int configuredHorizonTicks() {
        return Math.clamp((int) Math.ceil(window.get() * 20.0D), 1, MAX_HORIZON_TICKS);
    }

    private void updateCooldownPhase(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (!parent().configuredEnabled()
                || client == null
                || currentPlayer == null
                || client.level == null) {
            cooldownWasFull = false;
            currentOverchargeTicks = 0;
            return;
        }

        boolean full = currentPlayer.getAttackStrengthScale(0.5F) >= 0.999F;
        if (!full) {
            cooldownWasFull = false;
            currentOverchargeTicks = 0;
        } else if (cooldownWasFull) {
            currentOverchargeTicks = Math.min(100, currentOverchargeTicks + 1);
        } else {
            cooldownWasFull = true;
            currentOverchargeTicks = 0;
        }
    }

    private CombatDecisionEngine.SyncPolicy syncPolicy() {
        return new CombatDecisionEngine.SyncPolicy(
                syncEnabled.get(),
                currentOverchargeTicks,
                maxOverchargeTicks.get(),
                syncCycles.get());
    }

    private void clear(Minecraft client) {
        CombatInputController.releaseAll(client, CombatInputController.Owner.PREDICT_CRITICAL);
        resetState();
    }

    private void resetState() {
        targetId = -1;
        remainingTicks = 0;
        earliestAttackTicks = 0;
        queuedManualIntent = false;
        queuedThroughBlock = false;
        clearAutomaticPlan();
        plannedDecision = CombatDecisionEngine.Decision.abort("idle");
    }

    public boolean isAimingWindowActive() {
        return targetId != -1;
    }

    @Override
    public boolean isActive() {
        return parent().configuredEnabled();
    }

    public CombatDecisionEngine.Decision getPlannedDecision() {
        return plannedDecision;
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Critical: "
                        + (parent().configuredEnabled() ? "enabled" : "disabled")
                        + ", mode: Predict, window: "
                        + NumberText.compactDouble(window.get())
                        + "s ("
                        + configuredHorizonTicks()
                        + "t), stop-sprint: "
                        + (stopSprint.get() ? "enabled" : "disabled")
                        + ", sync: "
                        + (syncEnabled.get() ? "enabled" : "disabled")
                        + ", overcharge: "
                        + maxOverchargeTicks.get()
                        + "t, cycles: "
                        + syncCycles.get()
                        + ". Usage: .tempos critical <enable|disable|window 0.05-0.6|stopsprint enable|disable|sync enable|disable|overcharge 0-6|cycles 1-3>.");
        return 1;
    }

    @Override
    public int setActive(Minecraft client, boolean value) {
        parent().setConfiguredEnabled(value);
        clear(client);
        return showStatus(client);
    }

    public int setWindow(Minecraft client, double value) {
        window.set(value);
        clear(client);
        return showStatus(client);
    }

    public int setStopSprint(Minecraft client, boolean value) {
        stopSprint.set(value);
        clear(client);
        return showStatus(client);
    }

    public int setSyncEnabled(Minecraft client, boolean value) {
        syncEnabled.set(value);
        clear(client);
        return showStatus(client);
    }

    public int setMaxOverchargeTicks(Minecraft client, int value) {
        maxOverchargeTicks.set(value);
        clear(client);
        return showStatus(client);
    }

    public int setSyncCycles(Minecraft client, int value) {
        syncCycles.set(value);
        clear(client);
        return showStatus(client);
    }
}
