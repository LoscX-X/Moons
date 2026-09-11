package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.PostHitLandingWindow;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatDecisionEngine;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.prediction.KnockbackPrediction;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.rotation.aim.AimPointUtils;
import com.blanoir.moons.client.utils.world.FluidQueries;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Critical-triggered lava place/collect cycle using vanilla bucket actions. */
public final class AutoLava {
    private static final int MAX_BUCKET_SYNC_TICKS = 10;
    private static final int MAX_ACTION_WAIT_TICKS = 24;
    private static final int MAX_CYCLE_TICKS = 60;
    private static final double FACE_EDGE_INSET = 0.06D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final double SELF_SAFETY_MARGIN = 0.12D;
    private static final double TARGET_PATH_PADDING = 0.42D;
    private static final int PLACEMENT_SCAN_RADIUS = 4;
    private static final int ATTACK_REQUEST_LIFETIME_TICKS = 30;
    private static final int GROUND_LANDING_WINDOW_TICKS = 3;
    private static final double MAX_GROUND_TARGET_SPEED = 0.15D;
    private static final double MAX_GROUND_RELATIVE_SPEED = 0.25D;
    private static final Direction[] SUPPORT_DIRECTIONS = {
        Direction.DOWN,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP
    };
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autolava.enabled").defaultValue(false).build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("autolava.chance")
                    .defaultValue(0.50D)
                    .range(0.0D, 1.0D)
                    .build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("autolava.range")
                    .defaultValue(4.5D)
                    .range(2.0D, 6.0D)
                    .build();

    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("autolava.fov")
                    .defaultValue(90.0D)
                    .range(1.0D, 360.0D)
                    .build();

    private static final DoubleSetting PREDICTION =
            new DoubleSetting.Builder()
                    .name("autolava.prediction")
                    .defaultValue(1.0D)
                    .range(0.0D, 1.5D)
                    .build();

    private static final IntSetting PREDICTION_DELAY_MS =
            new IntSetting.Builder()
                    .name("autolava.predictionDelayMs")
                    .defaultValue(20)
                    .range(0, 250)
                    .build();

    private static final IntSetting TRIGGER_DELAY_MS =
            new IntSetting.Builder()
                    .name("autolava.triggerDelayMs")
                    .defaultValue(100)
                    .range(0, 1000)
                    .build();

    private static final IntSetting SWITCH_DELAY_MS =
            new IntSetting.Builder()
                    .name("autolava.switchDelayMs")
                    .defaultValue(50)
                    .range(0, 500)
                    .build();

    private static final IntSetting PICKUP_DELAY_MS =
            new IntSetting.Builder()
                    .name("autolava.pickupDelayMs")
                    .defaultValue(15)
                    .range(0, 1000)
                    .build();

    private static final BooleanSetting WALL_ENABLED =
            new BooleanSetting.Builder().name("autolava.wall").defaultValue(true).build();

    private static final BooleanSetting GROUND_ENABLED =
            new BooleanSetting.Builder().name("autolava.ground").defaultValue(true).build();

    private static final IntSetting RETURN_SWITCH_DELAY_MS =
            new IntSetting.Builder()
                    .name("autolava.returnSwitchDelayMs")
                    .defaultValue(0)
                    .range(0, 500)
                    .build();

    private static final IntSetting SMOOTH_TICKS =
            new IntSetting.Builder()
                    .name("autolava.smoothTicks")
                    .defaultValue(3)
                    .range(1, 10)
                    .build();

    private static final DoubleSetting COOLDOWN_SECONDS =
            new DoubleSetting.Builder()
                    .name("autolava.cooldown")
                    .defaultValue(1.0D)
                    .range(0.0D, 30.0D)
                    .build();

    private static CyclePhase phase = CyclePhase.IDLE;
    private static int pendingTargetId = -1;
    private static long pendingExecuteAtNanos;
    private static boolean pendingWallAttempted;
    private static final PostHitLandingWindow GROUND_LANDING_WINDOW = new PostHitLandingWindow();
    private static long slotReadyAtNanos;
    private static int activeTargetId = -1;
    private static PlacementRoute activePlacementRoute = PlacementRoute.NONE;
    private static BlockPos lavaPos;
    private static BlockHitResult placementHit;
    private static int bucketSlot = -1;
    private static int originalSlot = -1;
    private static int cooldownTicks;
    private static long pickupReadyAtNanos;
    private static int bucketSyncWaitTicks;
    private static int activeSmoothTicks;
    private static int phaseTicks;
    private static int cycleTicks;
    private static final KnockbackPrediction TARGET_MOTION = new KnockbackPrediction();
    private static Vec3 planningViewDirection;
    private static int cycleGeneration;
    private static int activeGeneration;
    private static boolean returnSwitchScheduled;
    private static int deferredOriginalSlot = -1;
    private static int deferredBucketSlot = -1;
    private static long deferredRestoreAtNanos;

    private AutoLava() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoLava.context", event -> shutdown(null));
        PlacementCoordinator.register(PlacementCoordinator.Owner.AUTO_LAVA, AutoLava::isBusy);
        EventBus.PLAYER_UPDATE.register("AutoLava.playerUpdate", event -> tick(event.client()));
    }

    /** Samples the critical gate before vanilla resets attack strength. */
    public static boolean isCriticalTriggerEligible(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        return entity instanceof Player target
                && ClientReady.aliveGameplay(client)
                && (WALL_ENABLED.get() || GROUND_ENABLED.get())
                && Targeting.isValidTargetPlayer(client, target)
                && CombatDecisionEngine.canCriticalNow(client, target);
    }

    /** Called after the vanilla attack path has dispatched its packet. */
    public static void onAttackDispatched(Entity entity, boolean criticalEligibleBeforeAttack) {
        Minecraft client = Minecraft.getInstance();
        if (!ENABLED.get()
                || !ClientReady.aliveGameplay(client)
                || isBusy()
                || cooldownTicks > 0
                || PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_LAVA)
                || !(entity instanceof Player target)
                || !Targeting.isValidTargetPlayer(client, target)
                || target.isOnFire()
                || (!WALL_ENABLED.get() && !GROUND_ENABLED.get())
                || !criticalEligibleBeforeAttack
                || !RandomMath.chance(CHANCE.get())
                || HotbarQueries.firstItem(client, Items.LAVA_BUCKET) < 0) {
            return;
        }
        pendingTargetId = target.getId();
        pendingExecuteAtNanos = System.nanoTime() + TRIGGER_DELAY_MS.get() * 1_000_000L;
        pendingWallAttempted = false;
        if (GROUND_ENABLED.get()) {
            GROUND_LANDING_WINDOW.arm(
                    target, ATTACK_REQUEST_LIFETIME_TICKS, GROUND_LANDING_WINDOW_TICKS);
        } else {
            GROUND_LANDING_WINDOW.clear();
        }
        TARGET_MOTION.begin(client, target);
    }

    private static void tick(Minecraft client) {
        restoreOriginalSlotWhenReady(client);
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
        if (!ENABLED.get() || !ClientReady.aliveGameplay(client)) {
            if (isBusy() || pendingTargetId >= 0) {
                reset(client);
            }
            return;
        }

        if (phase == CyclePhase.IDLE) {
            if (pendingTargetId >= 0) {
                Player target = targetById(client, pendingTargetId);
                if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
                    clearPendingTrigger();
                    TARGET_MOTION.reset();
                } else {
                    TARGET_MOTION.observe(client, target);
                    PostHitLandingWindow.Snapshot landing =
                            GROUND_LANDING_WINDOW.update(target, client.player.getDeltaMovement());
                    if (GROUND_ENABLED.get()
                            && landing.expired()
                            && (pendingWallAttempted || !WALL_ENABLED.get())) {
                        clearPendingTrigger();
                        TARGET_MOTION.reset();
                    } else if (System.nanoTime() >= pendingExecuteAtNanos) {
                        tryBeginPendingCycle(client, target, landing);
                    }
                }
            }
            return;
        }
        Player activeTarget = targetById(client, activeTargetId);
        if (activeTarget != null) {
            TARGET_MOTION.observe(client, activeTarget);
        }
        phaseTicks++;
        cycleTicks++;
        if (cycleTicks > MAX_CYCLE_TICKS) {
            abortCycle(client);
            return;
        }
        tickCycle(client);
    }

    private static void tryBeginPendingCycle(
            Minecraft client, Player target, PostHitLandingWindow.Snapshot landing) {
        planningViewDirection = client.player.getLookAngle();
        if (!pendingWallAttempted) {
            pendingWallAttempted = true;
            if (WALL_ENABLED.get()) {
                PlacementPlan wallPlan = planAtTarget(client, target, PlacementRoute.WALL);
                if (wallPlan != null) {
                    beginPendingCycle(client, PlacementRoute.WALL, wallPlan);
                    return;
                }
            }
        }
        if (!GROUND_ENABLED.get()) {
            clearPendingTrigger();
            TARGET_MOTION.reset();
            return;
        }
        if (!landing.insideLandingWindow()
                || landing.targetHorizontalSpeed() > MAX_GROUND_TARGET_SPEED
                || landing.relativeHorizontalSpeed() > MAX_GROUND_RELATIVE_SPEED) {
            return;
        }
        PlacementPlan groundPlan = planAtTarget(client, target, PlacementRoute.GROUND);
        if (groundPlan != null) {
            beginPendingCycle(client, PlacementRoute.GROUND, groundPlan);
        }
    }

    private static void beginPendingCycle(
            Minecraft client, PlacementRoute route, PlacementPlan initialPlan) {
        if (pendingTargetId < 0) {
            return;
        }
        int pending = pendingTargetId;
        clearPendingTrigger();
        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_LAVA)) {
            TARGET_MOTION.reset();
            return;
        }

        Player target = targetById(client, pending);
        if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
            TARGET_MOTION.reset();
            return;
        }
        int slot = HotbarQueries.firstItem(client, Items.LAVA_BUCKET);
        if (slot < 0) {
            TARGET_MOTION.reset();
            return;
        }

        activeTargetId = target.getId();
        activePlacementRoute = route;
        lavaPos = initialPlan.lavaPos().immutable();
        placementHit = initialPlan.hit();
        bucketSlot = slot;
        originalSlot = client.player.getInventory().getSelectedSlot();
        selectSlot(client, bucketSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_LAVA);

        bucketSyncWaitTicks = 0;
        activeSmoothTicks = SMOOTH_TICKS.get();
        activeGeneration = ++cycleGeneration;
        cycleTicks = 0;
        slotReadyAtNanos = System.nanoTime() + SWITCH_DELAY_MS.get() * 1_000_000L;
        if (SWITCH_DELAY_MS.get() <= 0) {
            // Planning normally happens when the slot delay expires.  The
            // zero-delay path must go through the same planning/validation
            // step instead of rotating with an uninitialised placement hit.
            beginAfterSlotDelay(client);
        } else {
            transition(CyclePhase.WAITING_FOR_SLOT_DELAY);
        }
    }

    private static void beginPlacementRotation(Minecraft client) {
        BlockHitResult hit = placementHit;
        if (hit == null || lavaPos == null) {
            abortCycle(client);
            return;
        }
        int generation = activeGeneration;
        transition(CyclePhase.TURNING_TO_PLACE);
        SilentPacketRotation.beginRotation(
                client,
                hit.getLocation(),
                activeSmoothTicks,
                () ->
                        transitionIfCurrent(
                                generation,
                                CyclePhase.TURNING_TO_PLACE,
                                CyclePhase.WAITING_FOR_PLACE_ROTATION));
    }

    /** Logical validation and item interactions advance in vanilla tick order. */
    private static void tickCycle(Minecraft client) {
        if (phase == CyclePhase.WAITING_FOR_SLOT_DELAY) {
            if (System.nanoTime() >= slotReadyAtNanos) {
                beginAfterSlotDelay(client);
            }
            return;
        }
        if (phase == CyclePhase.TURNING_TO_PLACE
                || phase == CyclePhase.TURNING_TO_PICKUP
                || phase == CyclePhase.TURNING_BACK_TO_CAMERA) {
            if (phaseTicks > Math.max(MAX_ACTION_WAIT_TICKS, activeSmoothTicks * 4)) {
                abortCycle(client);
            }
            return;
        }

        if (phase == CyclePhase.WAITING_FOR_RETURN_ROTATION) {
            if (!SilentPacketRotation.isRotationPacketSent()) {
                if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    abortCycle(client);
                }
                return;
            }
            // beginReturnToCamera already follows the live camera every frame.
            // Once its final packet is confirmed, release ownership immediately
            // instead of chasing a camera that the player is still moving.
            finishCycle(client);
            return;
        }

        if (phase == CyclePhase.WAITING_FOR_PLACE_ROTATION) {
            Player target = targetById(client, activeTargetId);
            if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
                beginReturnRotation(client);
                return;
            }
            if (!validatePlacement(client)) {
                beginReturnRotation(client);
                return;
            }
            if (!sentLookMatchesPlacement(client, placementHit)) {
                // Frame aiming continues following the same world point while
                // the player walks or jumps.  Wait for its next real packet;
                // restarting the rotation here caused recursive re-plan loops.
                if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    beginReturnRotation(client);
                }
                return;
            }
            selectSlot(client, bucketSlot);
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, placementHit)) {
                transition(CyclePhase.CLICKING_TO_PLACE);
            } else if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                abortCycle(client);
            }
            return;
        }

        if (phase == CyclePhase.CLICKING_TO_PLACE) {
            if (!SilentPacketRotation.isUseInvocationDone()) {
                if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    abortCycle(client);
                }
                return;
            }
            beginLavaHold();
            return;
        }

        if (phase == CyclePhase.HOLDING_LAVA) {
            continueHoldingLava(client, true);
            return;
        }

        if (phase == CyclePhase.WAITING_FOR_PICKUP_ROTATION) {
            if (!isLavaSource(client, lavaPos)
                    || bucketSlot < 0
                    || !client.player.getInventory().getItem(bucketSlot).is(Items.BUCKET)) {
                beginReturnRotation(client);
                return;
            }
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, lavaSourceHit(lavaPos))) {
                transition(CyclePhase.CLICKING_TO_PICKUP);
            } else if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                abortCycle(client);
            }
            return;
        }

        if (phase == CyclePhase.CLICKING_TO_PICKUP) {
            if (!SilentPacketRotation.isUseInvocationDone()) {
                if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    abortCycle(client);
                }
                return;
            }
            // Pickup has already left the vanilla use path, so the bucket no
            // longer has to remain selected while the rotation lock closes and
            // the inventory update arrives. Restore the sword-facing slot now.
            scheduleOriginalSlotRestore();
            restoreOriginalSlotWhenReady(client);
            if (!SilentPacketRotation.isUseDone()) {
                if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    abortCycle(client);
                }
                return;
            }
            boolean bucketRefilled =
                    bucketSlot >= 0
                            && client.player
                                    .getInventory()
                                    .getItem(bucketSlot)
                                    .is(Items.LAVA_BUCKET);
            if (!bucketRefilled
                    && isLavaSource(client, lavaPos)
                    && ++bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                return;
            }
            beginReturnRotation(client);
        }
    }

    private static void beginLavaHold() {
        if (phase != CyclePhase.CLICKING_TO_PLACE) {
            return;
        }
        // The configured hold starts only after the server-confirmed source is
        // visible. Starting it at the local click lets network latency consume
        // the whole delay and makes every value look like instant pickup.
        pickupReadyAtNanos = 0L;
        transition(CyclePhase.HOLDING_LAVA);
    }

    private static void continueHoldingLava(Minecraft client, boolean advanceSyncTimeout) {
        if (phase != CyclePhase.HOLDING_LAVA) {
            return;
        }
        boolean bucketEmptied =
                bucketSlot >= 0
                        && client.player.getInventory().getItem(bucketSlot).is(Items.BUCKET);
        if (!bucketEmptied || !isLavaSource(client, lavaPos)) {
            if (!advanceSyncTimeout || ++bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                return;
            }
            beginReturnRotation(client);
            return;
        }
        bucketSyncWaitTicks = 0;
        long now = System.nanoTime();
        if (pickupReadyAtNanos == 0L) {
            pickupReadyAtNanos = now + PICKUP_DELAY_MS.get() * 1_000_000L;
        }
        if (now < pickupReadyAtNanos) {
            return;
        }
        BlockHitResult pickupHit = lavaSourceHit(lavaPos);
        if (sentLookReachesLavaSource(client, lavaPos)) {
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, pickupHit)) {
                transition(CyclePhase.CLICKING_TO_PICKUP);
            }
            return;
        }

        // A different USE_ITEM angle before a natural movement tick violates
        // the interaction's angle lock. Wait for that movement packet;
        // the common placement path reuses the original ray and never waits.
        if (SilentPacketRotation.isUseRotationLocked()) {
            return;
        }

        transition(CyclePhase.TURNING_TO_PICKUP);
        int generation = activeGeneration;
        SilentPacketRotation.beginRotation(
                client,
                pickupHit.getLocation(),
                activeSmoothTicks,
                () ->
                        transitionIfCurrent(
                                generation,
                                CyclePhase.TURNING_TO_PICKUP,
                                CyclePhase.WAITING_FOR_PICKUP_ROTATION));
    }

    private static void beginAfterSlotDelay(Minecraft client) {
        if (bucketSlot < 0
                || !client.player.getInventory().getItem(bucketSlot).is(Items.LAVA_BUCKET)) {
            abortCycle(client);
            return;
        }
        selectSlot(client, bucketSlot);
        Player target = targetById(client, activeTargetId);
        if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
            finishCycle(client);
            return;
        }
        TARGET_MOTION.observe(client, target);
        // This is the cycle's one and only placement plan.  It is deliberately
        // made after both configurable delays, from the current camera ray and
        // the latest sampled motion, so later phases cannot jump between cells.
        planningViewDirection = client.player.getLookAngle();
        PlacementPlan plan = planAtTarget(client, target, activePlacementRoute);
        if (plan == null) {
            finishCycle(client);
            return;
        }
        lavaPos = plan.lavaPos().immutable();
        placementHit = plan.hit();
        beginPlacementRotation(client);
    }

    private static void beginReturnRotation(Minecraft client) {
        if (phase == CyclePhase.TURNING_BACK_TO_CAMERA
                || phase == CyclePhase.WAITING_FOR_RETURN_ROTATION) {
            return;
        }
        int generation = activeGeneration;
        transition(CyclePhase.TURNING_BACK_TO_CAMERA);
        SilentPacketRotation.beginReturnToCamera(
                client,
                Math.max(1, activeSmoothTicks),
                () ->
                        transitionIfCurrent(
                                generation,
                                CyclePhase.TURNING_BACK_TO_CAMERA,
                                CyclePhase.WAITING_FOR_RETURN_ROTATION));
    }

    private static void scheduleOriginalSlotRestore() {
        if (returnSwitchScheduled || originalSlot < 0 || bucketSlot < 0) {
            return;
        }
        returnSwitchScheduled = true;
        deferredOriginalSlot = originalSlot;
        deferredBucketSlot = bucketSlot;
        // Pickup completion has its own timing. Reusing the pre-placement slot
        // delay made the return switch feel much slower than the initial swap.
        deferredRestoreAtNanos = System.nanoTime() + RETURN_SWITCH_DELAY_MS.get() * 1_000_000L;
    }

    private static void restoreOriginalSlotWhenReady(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (deferredOriginalSlot < 0
                || System.nanoTime() < deferredRestoreAtNanos
                || client == null
                || currentPlayer == null) {
            return;
        }
        if (currentPlayer.getInventory().getSelectedSlot() == deferredBucketSlot) {
            selectSlot(client, deferredOriginalSlot);
        }
        deferredOriginalSlot = -1;
        deferredBucketSlot = -1;
        deferredRestoreAtNanos = 0L;
    }

    private static void finishCycle(Minecraft client) {
        cooldownTicks = (int) Math.ceil(COOLDOWN_SECONDS.get() * 20.0D);
        reset(client);
    }

    /** Hard failure path: discard a queued synthetic use and release all ownership. */
    private static void abortCycle(Minecraft client) {
        if (client != null && client.options != null) {
            while (client.options.keyUse.consumeClick()) {
                // Prevent a timed-out synthetic click from firing later at the camera ray.
            }
        }
        cooldownTicks = Math.max(10, (int) Math.ceil(COOLDOWN_SECONDS.get() * 20.0D));
        reset(client);
    }

    private static void transition(CyclePhase next) {
        phase = next;
        phaseTicks = 0;
    }

    /** Rejects callbacks left behind by a timed-out or replaced cycle. */
    private static void transitionIfCurrent(int generation, CyclePhase expected, CyclePhase next) {
        if (activeGeneration == generation && phase == expected) {
            transition(next);
        }
    }

    /** Empty BucketItem performs its own SOURCE_ONLY ray along the sent aim. */
    private static BlockHitResult lavaSourceHit(BlockPos source) {
        return BlockHitResult.miss(Vec3.atCenterOf(source), Direction.UP, source);
    }

    private static boolean isLavaSource(Minecraft client, BlockPos source) {
        if (source == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(source);
        return FluidQueries.isSource(state.getFluidState(), FluidTags.LAVA);
    }

    private static boolean sentLookReachesLavaSource(Minecraft client, BlockPos source) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult hit =
                BlockPlacementUtils.traceOutline(
                        client,
                        eye,
                        SilentPacketRotation.getInteractionLookVector(client),
                        client.player.blockInteractionRange(),
                        ClipContext.Fluid.SOURCE_ONLY);
        return BlockPlacementUtils.matchesBlock(hit, source);
    }

    /** The quantized packet ray must hit the exact planned support face. */
    private static boolean sentLookMatchesPlacement(Minecraft client, BlockHitResult plannedHit) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult hit =
                BlockPlacementUtils.traceOutline(
                        client,
                        eye,
                        SilentPacketRotation.getInteractionLookVector(client),
                        client.player.blockInteractionRange(),
                        ClipContext.Fluid.NONE);
        return BlockPlacementUtils.matchesFace(hit, plannedHit);
    }

    private static Player targetById(Minecraft client, int id) {
        var currentLevel = client == null ? null : client.level;
        return client != null
                        && currentLevel != null
                        && id >= 0
                        && currentLevel.getEntity(id) instanceof Player player
                ? player
                : null;
    }

    private static void clearPendingTrigger() {
        pendingTargetId = -1;
        pendingExecuteAtNanos = 0L;
        pendingWallAttempted = false;
        GROUND_LANDING_WINDOW.clear();
    }

    /** Vanilla-style horizontal velocity immediately after this attack. */
    private static boolean safeForLocalPlayer(Minecraft client, BlockPos source, double leadTicks) {
        Vec3 predicted =
                TrajectoryPrediction.horizontalPosition(
                        client,
                        client.player,
                        client.player.getDeltaMovement(),
                        Vec3.ZERO,
                        Math.min(3.0D, Math.max(0.0D, leadTicks)));
        Vec3 movement = predicted.subtract(client.player.position());
        AABB current =
                client.player
                        .getBoundingBox()
                        .inflate(SELF_SAFETY_MARGIN, 0.05D, SELF_SAFETY_MARGIN);
        AABB future = current.move(movement.x, 0.0D, movement.z);
        AABB swept =
                new AABB(
                        Math.min(current.minX, future.minX),
                        Math.min(current.minY, future.minY),
                        Math.min(current.minZ, future.minZ),
                        Math.max(current.maxX, future.maxX),
                        Math.max(current.maxY, future.maxY),
                        Math.max(current.maxZ, future.maxZ));
        return !swept.intersects(new AABB(source));
    }

    private static PlacementPlan planAtTarget(
            Minecraft client, Player target, PlacementRoute route) {
        double strength = PREDICTION.get();
        Vec3 velocity =
                TARGET_MOTION.matches(target)
                        ? TARGET_MOTION.velocity()
                        : target.getDeltaMovement();
        Vec3 acceleration =
                TARGET_MOTION.matches(target) ? TARGET_MOTION.acceleration() : Vec3.ZERO;
        double predictionDelayTicks = PREDICTION_DELAY_MS.get() / 50.0D;
        double leadTicks = (SMOOTH_TICKS.get() + 1.5D + predictionDelayTicks) * strength;
        Vec3 predicted =
                TrajectoryPrediction.horizontalPosition(
                        client, target, velocity, acceleration, leadTicks);
        BlockPos desired =
                BlockPos.containing(
                        predicted.x, target.getBoundingBox().minY + 1.0E-4D, predicted.z);
        Vec3 eye = client.player.getEyePosition();
        Vec3 look =
                planningViewDirection != null && planningViewDirection.lengthSqr() > 1.0E-9D
                        ? planningViewDirection.normalize()
                        : client.player.getLookAngle();
        double rayLength = Math.min(RANGE.get(), client.player.blockInteractionRange());
        PlacementPlan bestPlan = null;
        double bestScore = Double.POSITIVE_INFINITY;
        Vec3 targetMovement = predicted.subtract(target.position());
        AABB currentTarget = target.getBoundingBox();
        AABB predictedTarget = currentTarget.move(targetMovement.x, 0.0D, targetMovement.z);
        AABB targetSweep =
                new AABB(
                                Math.min(currentTarget.minX, predictedTarget.minX),
                                Math.min(currentTarget.minY, predictedTarget.minY),
                                Math.min(currentTarget.minZ, predictedTarget.minZ),
                                Math.max(currentTarget.maxX, predictedTarget.maxX),
                                Math.max(currentTarget.maxY, predictedTarget.maxY),
                                Math.max(currentTarget.maxZ, predictedTarget.maxZ))
                        .inflate(TARGET_PATH_PADDING, 0.05D, TARGET_PATH_PADDING);

        // A web already covering the target is a deliberate support block:
        // click its UP face and place lava in webPos.above(). Do this before
        // wall/ground routing so the broad scan cannot prefer a side cell and
        // so the web itself is preserved instead of treated as the destination.
        PlacementPlan cobwebTopPlan =
                planAboveTargetCobweb(
                        client, desired, targetSweep, predicted, eye, look, rayLength, leadTicks);
        if (cobwebTopPlan != null) {
            return cobwebTopPlan;
        }

        // Enumerate multiple place cells
        // first, then every legal support face. Target coverage dominates the
        // score; aim continuity is only a tie-breaker.
        for (int x = -PLACEMENT_SCAN_RADIUS; x <= PLACEMENT_SCAN_RADIUS; x++) {
            for (int z = -PLACEMENT_SCAN_RADIUS; z <= PLACEMENT_SCAN_RADIUS; z++) {
                BlockPos placePos = desired.offset(x, 0, z);
                if (!new AABB(placePos).intersects(targetSweep)) {
                    continue;
                }
                BlockState state = client.level.getBlockState(placePos);
                if (!state.canBeReplaced()
                        || !state.getFluidState().isEmpty()
                        || !safeForLocalPlayer(client, placePos, leadTicks)) {
                    continue;
                }
                Vec3 placeCenter = Vec3.atCenterOf(placePos);
                double targetDistance =
                        Math.pow(placeCenter.x - predicted.x, 2.0D)
                                + Math.pow(placeCenter.z - predicted.z, 2.0D);
                for (Direction supportDirection : SUPPORT_DIRECTIONS) {
                    if (route == PlacementRoute.GROUND && supportDirection != Direction.DOWN) {
                        continue;
                    }
                    if (route == PlacementRoute.WALL
                            && !supportDirection.getAxis().isHorizontal()) {
                        continue;
                    }
                    BlockPos supportPos = placePos.relative(supportDirection);
                    BlockState support = client.level.getBlockState(supportPos);
                    Direction face = supportDirection.getOpposite();
                    if (!isUsableSupport(client, supportPos, support, face)) {
                        continue;
                    }
                    AABB facePlane = insetFacePlane(supportPos, face);
                    Vec3 nearest = AimPointUtils.closest(facePlane, eye, look, rayLength);
                    BlockHitResult visibleHit = visibleFaceHit(client, supportPos, face, nearest);
                    if (visibleHit == null
                            || !withinRange(client, visibleHit.getLocation())
                            || !withinFov(client, visibleHit.getLocation(), look)) {
                        continue;
                    }
                    double rayDistance =
                            squaredDistanceToViewRay(
                                    eye, look, visibleHit.getLocation(), rayLength);
                    double eyeDistance = eye.distanceToSqr(visibleHit.getLocation());
                    double score =
                            targetDistance * 8.0D
                                    + rayDistance * 0.8D
                                    + eyeDistance * 0.015D
                                    - (placePos.equals(desired) ? 0.35D : 0.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        bestPlan = new PlacementPlan(placePos, visibleHit);
                    }
                }
            }
        }
        return bestPlan;
    }

    /** Highest-priority lava placement: the air cell directly above a target web. */
    private static PlacementPlan planAboveTargetCobweb(
            Minecraft client,
            BlockPos desired,
            AABB targetSweep,
            Vec3 predicted,
            Vec3 eye,
            Vec3 look,
            double rayLength,
            double leadTicks) {
        PlacementPlan bestPlan = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int x = -PLACEMENT_SCAN_RADIUS; x <= PLACEMENT_SCAN_RADIUS; x++) {
            for (int z = -PLACEMENT_SCAN_RADIUS; z <= PLACEMENT_SCAN_RADIUS; z++) {
                BlockPos webPos = desired.offset(x, 0, z);
                if (!client.level.getBlockState(webPos).is(Blocks.COBWEB)
                        || !new AABB(webPos).intersects(targetSweep)) {
                    continue;
                }
                BlockPos placePos = webPos.above();
                BlockState placeState = client.level.getBlockState(placePos);
                if (!new AABB(placePos).intersects(targetSweep)
                        || !placeState.canBeReplaced()
                        || !placeState.getFluidState().isEmpty()
                        || !safeForLocalPlayer(client, placePos, leadTicks)) {
                    continue;
                }
                AABB topFace = insetFacePlane(webPos, Direction.UP);
                Vec3 nearest = AimPointUtils.closest(topFace, eye, look, rayLength);
                BlockHitResult hit = visibleFaceHit(client, webPos, Direction.UP, nearest);
                if (hit == null
                        || !withinRange(client, hit.getLocation())
                        || !withinFov(client, hit.getLocation(), look)) {
                    continue;
                }
                Vec3 webCenter = Vec3.atCenterOf(webPos);
                double score =
                        Math.pow(webCenter.x - predicted.x, 2.0D)
                                + Math.pow(webCenter.z - predicted.z, 2.0D)
                                + squaredDistanceToViewRay(eye, look, hit.getLocation(), rayLength)
                                        * 0.1D;
                if (score < bestScore) {
                    bestScore = score;
                    bestPlan = new PlacementPlan(placePos, hit);
                }
            }
        }
        return bestPlan;
    }

    /** Bounded support-face plane, inset so an edge cannot resolve to another face. */
    private static AABB insetFacePlane(BlockPos supportPos, Direction face) {
        double minX = supportPos.getX() + FACE_EDGE_INSET;
        double maxX = supportPos.getX() + 1.0D - FACE_EDGE_INSET;
        double minY = supportPos.getY() + FACE_EDGE_INSET;
        double maxY = supportPos.getY() + 1.0D - FACE_EDGE_INSET;
        double minZ = supportPos.getZ() + FACE_EDGE_INSET;
        double maxZ = supportPos.getZ() + 1.0D - FACE_EDGE_INSET;
        return switch (face.getAxis()) {
            case X -> {
                double x = face == Direction.EAST ? supportPos.getX() + 1.0D : supportPos.getX();
                yield new AABB(x, minY, minZ, x, maxY, maxZ);
            }
            case Y -> {
                double y = face == Direction.UP ? supportPos.getY() + 1.0D : supportPos.getY();
                yield new AABB(minX, y, minZ, maxX, y, maxZ);
            }
            case Z -> {
                double z = face == Direction.SOUTH ? supportPos.getZ() + 1.0D : supportPos.getZ();
                yield new AABB(minX, minY, z, maxX, maxY, z);
            }
        };
    }

    /** Confirms that aiming at the selected plane point really reaches that face. */
    private static BlockHitResult visibleFaceHit(
            Minecraft client, BlockPos supportPos, Direction face, Vec3 planePoint) {
        return BlockPlacementUtils.visibleFaceHit(
                client, client.player.getEyePosition(), supportPos, face, planePoint, RAY_EPSILON);
    }

    private static double squaredDistanceToViewRay(
            Vec3 eye, Vec3 look, Vec3 point, double rayLength) {
        return MathUtils.squaredDistanceToRay(eye, look, point, rayLength);
    }

    private static boolean validatePlacement(Minecraft client) {
        if (lavaPos == null
                || placementHit == null
                || bucketSlot < 0
                || !withinRange(client, placementHit.getLocation())
                || !client.player.getInventory().getItem(bucketSlot).is(Items.LAVA_BUCKET)) {
            return false;
        }
        BlockState state = client.level.getBlockState(lavaPos);
        if (!state.canBeReplaced()
                || !state.getFluidState().isEmpty()
                || !safeForLocalPlayer(client, lavaPos, Math.max(1.0D, activeSmoothTicks + 1.0D))) {
            return false;
        }
        Direction supportDirection = placementHit.getDirection().getOpposite();
        BlockPos supportPos = lavaPos.relative(supportDirection);
        return supportPos.equals(placementHit.getBlockPos())
                && isUsableSupport(
                        client,
                        supportPos,
                        client.level.getBlockState(supportPos),
                        placementHit.getDirection())
                && visibleFaceHit(
                                client,
                                supportPos,
                                placementHit.getDirection(),
                                placementHit.getLocation())
                        != null;
    }

    /** Cobweb has no normal collision support, but its top outline is clickable. */
    private static boolean isUsableSupport(
            Minecraft client, BlockPos supportPos, BlockState support, Direction face) {
        if (support.is(Blocks.COBWEB)) {
            return face == Direction.UP;
        }
        return !support.getCollisionShape(client.level, supportPos).isEmpty();
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (slot >= 0 && slot < 9 && client.player.getInventory().getSelectedSlot() != slot) {
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    private static boolean withinRange(Minecraft client, Vec3 point) {
        double reach = Math.min(RANGE.get(), client.player.blockInteractionRange());
        return BlockPlacementUtils.withinReach(client.player.getEyePosition(), point, reach);
    }

    private static boolean withinFov(Minecraft client, Vec3 point, Vec3 look) {
        return MathUtils.withinFov(
                MathUtils.viewAngle(client.player.getEyePosition(), look, point), FOV.get());
    }

    public static boolean isBusy() {
        // Waiting for a post-hit landing owns no slot, rotation, or input.
        // Only the active placement/pickup cycle participates in module
        // mutual exclusion.
        return phase != CyclePhase.IDLE;
    }

    private static void reset(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (isBusy()) SilentPacketRotation.reset();
        boolean deferredForCurrentCycle =
                returnSwitchScheduled
                        && deferredOriginalSlot == originalSlot
                        && deferredBucketSlot == bucketSlot;
        if (client != null
                && currentPlayer != null
                && originalSlot >= 0
                && bucketSlot >= 0
                && currentPlayer.getInventory().getSelectedSlot() == bucketSlot
                && !deferredForCurrentCycle) {
            selectSlot(client, originalSlot);
        }
        CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_LAVA);
        clearPendingTrigger();
        activeTargetId = -1;
        activePlacementRoute = PlacementRoute.NONE;
        activeGeneration = 0;
        returnSwitchScheduled = false;
        slotReadyAtNanos = 0L;
        TARGET_MOTION.reset();
        planningViewDirection = null;
        lavaPos = null;
        placementHit = null;
        bucketSlot = -1;
        originalSlot = -1;
        pickupReadyAtNanos = 0L;
        bucketSyncWaitTicks = 0;
        activeSmoothTicks = 0;
        phaseTicks = 0;
        cycleTicks = 0;
        transition(CyclePhase.IDLE);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String hudTag() {
        return format(CHANCE.get() * 100.0D) + "%";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoLava: "
                        + (ENABLED.get() ? "enabled" : "disabled")
                        + ", critical chance: "
                        + format(CHANCE.get())
                        + ", range: "
                        + format(RANGE.get())
                        + ", fov: "
                        + format(FOV.get())
                        + ", wall: "
                        + (WALL_ENABLED.get() ? "enabled" : "disabled")
                        + ", ground: "
                        + (GROUND_ENABLED.get() ? "enabled" : "disabled")
                        + ", trigger delay: "
                        + TRIGGER_DELAY_MS.get()
                        + "ms"
                        + ", switch delay: "
                        + SWITCH_DELAY_MS.get()
                        + "ms"
                        + ", prediction: "
                        + format(PREDICTION.get())
                        + "x"
                        + ", prediction delay: "
                        + PREDICTION_DELAY_MS.get()
                        + "ms"
                        + ", pickup delay: "
                        + PICKUP_DELAY_MS.get()
                        + "ms"
                        + ", return switch delay: "
                        + RETURN_SWITCH_DELAY_MS.get()
                        + "ms"
                        + ", smooth: "
                        + SMOOTH_TICKS.get()
                        + "t"
                        + ", cooldown: "
                        + format(COOLDOWN_SECONDS.get())
                        + "s.");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) {
            cooldownTicks = 0;
            if (isBusy() || pendingTargetId >= 0) {
                reset(client);
            }
        }
        ClientChat.send(client, "AutoLava " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setChance(Minecraft client, double value) {
        CHANCE.set(value);
        ClientChat.send(client, "AutoLava critical chance set to " + format(CHANCE.get()) + ".");
        return 1;
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        ClientChat.send(client, "AutoLava range set to " + format(RANGE.get()) + ".");
        return 1;
    }

    public static int setFov(Minecraft client, double value) {
        FOV.set(value);
        ClientChat.send(client, "AutoLava FOV set to " + format(FOV.get()) + " degrees.");
        return 1;
    }

    public static int setPrediction(Minecraft client, double value) {
        PREDICTION.set(value);
        ClientChat.send(client, "AutoLava prediction set to " + format(PREDICTION.get()) + "x.");
        return 1;
    }

    public static int setPredictionDelayMs(Minecraft client, int value) {
        PREDICTION_DELAY_MS.set(value);
        ClientChat.send(
                client, "AutoLava prediction delay set to " + PREDICTION_DELAY_MS.get() + " ms.");
        return 1;
    }

    public static int setTriggerDelayMs(Minecraft client, int value) {
        TRIGGER_DELAY_MS.set(value);
        ClientChat.send(client, "AutoLava trigger delay set to " + TRIGGER_DELAY_MS.get() + " ms.");
        return 1;
    }

    public static int setSwitchDelayMs(Minecraft client, int value) {
        SWITCH_DELAY_MS.set(value);
        ClientChat.send(
                client, "AutoLava item switch delay set to " + SWITCH_DELAY_MS.get() + " ms.");
        return 1;
    }

    public static int setPickupDelayMs(Minecraft client, int value) {
        PICKUP_DELAY_MS.set(value);
        ClientChat.send(client, "AutoLava pickup delay set to " + PICKUP_DELAY_MS.get() + " ms.");
        return 1;
    }

    public static int setWallEnabled(Minecraft client, boolean value) {
        WALL_ENABLED.set(value);
        ClientChat.send(
                client,
                "AutoLava immediate wall route "
                        + (WALL_ENABLED.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public static int setGroundEnabled(Minecraft client, boolean value) {
        GROUND_ENABLED.set(value);
        ClientChat.send(
                client,
                "AutoLava post-hit landing route "
                        + (GROUND_ENABLED.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public static int setReturnSwitchDelayMs(Minecraft client, int value) {
        RETURN_SWITCH_DELAY_MS.set(value);
        ClientChat.send(
                client,
                "AutoLava return switch delay set to " + RETURN_SWITCH_DELAY_MS.get() + " ms.");
        return 1;
    }

    public static int setSmoothTicks(Minecraft client, int value) {
        SMOOTH_TICKS.set(value);
        ClientChat.send(client, "AutoLava smooth turn set to " + SMOOTH_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setCooldown(Minecraft client, double value) {
        COOLDOWN_SECONDS.set(value);
        ClientChat.send(
                client, "AutoLava cooldown set to " + format(COOLDOWN_SECONDS.get()) + " seconds.");
        return 1;
    }

    private static String format(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }

    private enum CyclePhase {
        IDLE,
        WAITING_FOR_SLOT_DELAY,
        TURNING_TO_PLACE,
        WAITING_FOR_PLACE_ROTATION,
        CLICKING_TO_PLACE,
        HOLDING_LAVA,
        TURNING_TO_PICKUP,
        WAITING_FOR_PICKUP_ROTATION,
        CLICKING_TO_PICKUP,
        TURNING_BACK_TO_CAMERA,
        WAITING_FOR_RETURN_ROTATION
    }

    private enum PlacementRoute {
        NONE,
        WALL,
        GROUND
    }

    private record PlacementPlan(BlockPos lavaPos, BlockHitResult hit) {}

    // Debug
    public static String debugState() {
        return phase.name().toLowerCase(java.util.Locale.ROOT)
                + " phase="
                + phaseTicks
                + "t"
                + " cycle="
                + cycleTicks
                + "t"
                + " switch="
                + (returnSwitchScheduled ? "queued" : "none")
                + " use="
                + SilentPacketRotation.isUseInvocationDone()
                + "/"
                + SilentPacketRotation.isUseDone();
    }

    /** End this feature's pending work without changing its configured toggle. */
    public static void shutdown(Minecraft client) {
        reset(client);
        cooldownTicks = 0;
    }
}
