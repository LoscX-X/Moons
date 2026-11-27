package com.blanoir.tempos.client.module.impl.player;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.combat.CombatDecisionEngine;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.targeting.PostHitLandingWindow;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;
import com.blanoir.tempos.client.module.impl.player.autolava.KnockbackPrediction;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RandomChance;
import com.blanoir.tempos.client.utils.math.RayGeometry;
import com.blanoir.tempos.client.utils.math.ViewCone;
import com.blanoir.tempos.client.utils.player.HotbarQueries;
import com.blanoir.tempos.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.tempos.client.utils.rotation.aim.RayNearestPoint;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.blanoir.tempos.client.utils.world.FluidQueries;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

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
@ModuleInfo(
        category = ModuleCategory.WORLD,
        description = "Places and collects lava near a target using a bucket.")
public final class AutoLava extends ClientFeature {
    public static final AutoLava INSTANCE = new AutoLava();

    private final PlacementRayPolicy rays = new PlacementRayPolicy("autolava");
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
    private final Direction[] supportDirections = {
        Direction.DOWN,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP
    };
    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("autolava.enabled").defaultValue(false).build();

    private final DoubleSetting chance =
            new DoubleSetting.Builder()
                    .name("autolava.chance")
                    .defaultValue(0.50D)
                    .range(0.0D, 1.0D)
                    .build();

    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("autolava.range")
                    .defaultValue(4.5D)
                    .range(2.0D, 6.0D)
                    .build();

    private final DoubleSetting fov =
            new DoubleSetting.Builder()
                    .name("autolava.fov")
                    .defaultValue(90.0D)
                    .range(1.0D, 360.0D)
                    .build();

    private final DoubleSetting prediction =
            new DoubleSetting.Builder()
                    .name("autolava.prediction")
                    .defaultValue(1.0D)
                    .range(0.0D, 1.5D)
                    .build();

    private final IntSetting predictionDelayMs =
            new IntSetting.Builder()
                    .name("autolava.predictionDelayMs")
                    .defaultValue(20)
                    .range(0, 250)
                    .build();

    private final IntSetting triggerDelayMs =
            new IntSetting.Builder()
                    .name("autolava.triggerDelayMs")
                    .defaultValue(100)
                    .range(0, 1000)
                    .build();

    private final IntSetting switchDelayMs =
            new IntSetting.Builder()
                    .name("autolava.switchDelayMs")
                    .defaultValue(50)
                    .range(0, 500)
                    .build();

    private final IntSetting pickupDelayMs =
            new IntSetting.Builder()
                    .name("autolava.pickupDelayMs")
                    .defaultValue(15)
                    .range(0, 1000)
                    .build();

    private final BooleanSetting wallEnabled =
            new BooleanSetting.Builder().name("autolava.wall").defaultValue(true).build();

    private final BooleanSetting groundEnabled =
            new BooleanSetting.Builder().name("autolava.ground").defaultValue(true).build();

    private final IntSetting returnSwitchDelayMs =
            new IntSetting.Builder()
                    .name("autolava.returnSwitchDelayMs")
                    .defaultValue(0)
                    .range(0, 500)
                    .build();

    private final IntSetting smoothTicks =
            new IntSetting.Builder()
                    .name("autolava.smoothTicks")
                    .defaultValue(3)
                    .range(1, 10)
                    .build();

    private final DoubleSetting cooldownSeconds =
            new DoubleSetting.Builder()
                    .name("autolava.cooldown")
                    .defaultValue(1.0D)
                    .range(0.0D, 30.0D)
                    .build();

    private CyclePhase phase = CyclePhase.IDLE;
    private int pendingTargetId = -1;
    private long pendingExecuteAtNanos;
    private boolean pendingWallAttempted;
    private final PostHitLandingWindow groundLandingWindow = new PostHitLandingWindow();
    private long slotReadyAtNanos;
    private int activeTargetId = -1;
    private PlacementRoute activePlacementRoute = PlacementRoute.NONE;
    private BlockPos lavaPos;
    private BlockHitResult placementHit;
    private int bucketSlot = -1;
    private int originalSlot = -1;
    private int cooldownTicks;
    private long pickupReadyAtNanos;
    private int bucketSyncWaitTicks;
    private int activeSmoothTicks;
    private int phaseTicks;
    private int cycleTicks;
    private final KnockbackPrediction targetMotion = new KnockbackPrediction();
    private Vec3 planningViewDirection;
    private int cycleGeneration;
    private int activeGeneration;
    private boolean returnSwitchScheduled;
    private int deferredOriginalSlot = -1;
    private int deferredBucketSlot = -1;
    private long deferredRestoreAtNanos;

    private AutoLava() {
        super("autolava", "AutoLava");
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoLava.context", event -> shutdown(null));
        PlacementCoordinator.register(PlacementCoordinator.Owner.AUTO_LAVA, this::isBusy);
        EventBus.PLAYER_UPDATE.register("AutoLava.playerUpdate", event -> tick(event.client()));
    }

    /** Samples the critical gate before vanilla resets attack strength. */
    public boolean isCriticalTriggerEligible(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        return entity instanceof Player target
                && ClientReady.aliveGameplay(client)
                && (this.wallEnabled.get() || this.groundEnabled.get())
                && Targeting.isValidTargetPlayer(client, target)
                && CombatDecisionEngine.canCriticalNow(client, target);
    }

    /** Called after the vanilla attack path has dispatched its packet. */
    public void onAttackDispatched(Entity entity, boolean criticalEligibleBeforeAttack) {
        Minecraft client = Minecraft.getInstance();
        if (!this.enabled.get()
                || !ClientReady.aliveGameplay(client)
                || isBusy()
                || this.cooldownTicks > 0
                || PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_LAVA)
                || !(entity instanceof Player target)
                || !Targeting.isValidTargetPlayer(client, target)
                || target.isOnFire()
                || (!this.wallEnabled.get() && !this.groundEnabled.get())
                || !criticalEligibleBeforeAttack
                || !RandomChance.chance(this.chance.get())
                || HotbarQueries.firstItem(client, Items.LAVA_BUCKET) < 0) {
            return;
        }
        this.pendingTargetId = target.getId();
        this.pendingExecuteAtNanos = System.nanoTime() + this.triggerDelayMs.get() * 1_000_000L;
        this.pendingWallAttempted = false;
        if (this.groundEnabled.get()) {
            this.groundLandingWindow.arm(
                    target, ATTACK_REQUEST_LIFETIME_TICKS, GROUND_LANDING_WINDOW_TICKS);
        } else {
            this.groundLandingWindow.clear();
        }
        this.targetMotion.begin(client, target);
    }

    private void tick(Minecraft client) {
        restoreOriginalSlotWhenReady(client);
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
        }
        if (!this.enabled.get() || !ClientReady.aliveGameplay(client)) {
            if (isBusy() || this.pendingTargetId >= 0) {
                reset(client);
            }
            return;
        }

        if (this.phase == CyclePhase.IDLE) {
            if (this.pendingTargetId >= 0) {
                Player target = targetById(client, this.pendingTargetId);
                if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
                    clearPendingTrigger();
                    this.targetMotion.reset();
                } else {
                    this.targetMotion.observe(client, target);
                    PostHitLandingWindow.Snapshot landing =
                            this.groundLandingWindow.update(
                                    target, client.player.getDeltaMovement());
                    if (this.groundEnabled.get()
                            && landing.expired()
                            && (this.pendingWallAttempted || !this.wallEnabled.get())) {
                        clearPendingTrigger();
                        this.targetMotion.reset();
                    } else if (System.nanoTime() >= this.pendingExecuteAtNanos) {
                        tryBeginPendingCycle(client, target, landing);
                    }
                }
            }
            return;
        }
        Player activeTarget = targetById(client, this.activeTargetId);
        if (activeTarget != null) {
            this.targetMotion.observe(client, activeTarget);
        }
        this.phaseTicks++;
        this.cycleTicks++;
        if (this.cycleTicks > MAX_CYCLE_TICKS) {
            abortCycle(client);
            return;
        }
        tickCycle(client);
    }

    private void tryBeginPendingCycle(
            Minecraft client, Player target, PostHitLandingWindow.Snapshot landing) {
        this.planningViewDirection = client.player.getLookAngle();
        if (!this.pendingWallAttempted) {
            this.pendingWallAttempted = true;
            if (this.wallEnabled.get()) {
                PlacementPlan wallPlan = planAtTarget(client, target, PlacementRoute.WALL);
                if (wallPlan != null) {
                    beginPendingCycle(client, PlacementRoute.WALL, wallPlan);
                    return;
                }
            }
        }
        if (!this.groundEnabled.get()) {
            clearPendingTrigger();
            this.targetMotion.reset();
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

    private void beginPendingCycle(
            Minecraft client, PlacementRoute route, PlacementPlan initialPlan) {
        if (this.pendingTargetId < 0) {
            return;
        }
        int pending = this.pendingTargetId;
        clearPendingTrigger();
        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_LAVA)) {
            this.targetMotion.reset();
            return;
        }

        Player target = targetById(client, pending);
        if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
            this.targetMotion.reset();
            return;
        }
        int slot = HotbarQueries.firstItem(client, Items.LAVA_BUCKET);
        if (slot < 0) {
            this.targetMotion.reset();
            return;
        }

        this.activeTargetId = target.getId();
        this.activePlacementRoute = route;
        this.lavaPos = initialPlan.lavaPos().immutable();
        this.placementHit = initialPlan.hit();
        this.bucketSlot = slot;
        this.originalSlot = client.player.getInventory().getSelectedSlot();
        selectSlot(client, this.bucketSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_LAVA);

        this.bucketSyncWaitTicks = 0;
        this.activeSmoothTicks = this.smoothTicks.get();
        this.activeGeneration = ++this.cycleGeneration;
        this.cycleTicks = 0;
        this.slotReadyAtNanos = System.nanoTime() + this.switchDelayMs.get() * 1_000_000L;
        if (this.switchDelayMs.get() <= 0) {
            // Planning normally happens when the slot delay expires.  The
            // zero-delay path must go through the same planning/validation
            // step instead of rotating with an uninitialised placement hit.
            beginAfterSlotDelay(client);
        } else {
            transition(CyclePhase.WAITING_FOR_SLOT_DELAY);
        }
    }

    private void beginPlacementRotation(Minecraft client) {
        BlockHitResult hit = this.placementHit;
        if (hit == null || this.lavaPos == null) {
            abortCycle(client);
            return;
        }
        int generation = this.activeGeneration;
        transition(CyclePhase.TURNING_TO_PLACE);
        SilentPacketRotation.beginRotation(
                client,
                hit.getLocation(),
                this.activeSmoothTicks,
                () ->
                        transitionIfCurrent(
                                generation,
                                CyclePhase.TURNING_TO_PLACE,
                                CyclePhase.WAITING_FOR_PLACE_ROTATION));
    }

    /** Logical validation and item interactions advance in vanilla tick order. */
    private void tickCycle(Minecraft client) {
        if (this.phase == CyclePhase.WAITING_FOR_SLOT_DELAY) {
            if (System.nanoTime() >= this.slotReadyAtNanos) {
                beginAfterSlotDelay(client);
            }
            return;
        }
        if (this.phase == CyclePhase.TURNING_TO_PLACE
                || this.phase == CyclePhase.TURNING_TO_PICKUP
                || this.phase == CyclePhase.TURNING_BACK_TO_CAMERA) {
            if (this.phaseTicks > Math.max(MAX_ACTION_WAIT_TICKS, this.activeSmoothTicks * 4)) {
                abortCycle(client);
            }
            return;
        }

        if (this.phase == CyclePhase.WAITING_FOR_RETURN_ROTATION) {
            if (!SilentPacketRotation.isRotationPacketSent()) {
                if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
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

        if (this.phase == CyclePhase.WAITING_FOR_PLACE_ROTATION) {
            Player target = targetById(client, this.activeTargetId);
            if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
                beginReturnRotation(client);
                return;
            }
            if (!validatePlacement(client)) {
                beginReturnRotation(client);
                return;
            }
            if (!sentLookMatchesPlacement(client, this.placementHit)) {
                // Frame aiming continues following the same world point while
                // the player walks or jumps.  Wait for its next real packet;
                // restarting the rotation here caused recursive re-plan loops.
                if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    beginReturnRotation(client);
                }
                return;
            }
            selectSlot(client, this.bucketSlot);
            if (this.rays.invokeUseInPlayerUpdate(client, this.placementHit)) {
                transition(CyclePhase.CLICKING_TO_PLACE);
            } else if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
                abortCycle(client);
            }
            return;
        }

        if (this.phase == CyclePhase.CLICKING_TO_PLACE) {
            if (!SilentPacketRotation.isUseInvocationDone()) {
                if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    abortCycle(client);
                }
                return;
            }
            beginLavaHold();
            return;
        }

        if (this.phase == CyclePhase.HOLDING_LAVA) {
            continueHoldingLava(client, true);
            return;
        }

        if (this.phase == CyclePhase.WAITING_FOR_PICKUP_ROTATION) {
            if (!isLavaSource(client, this.lavaPos)
                    || this.bucketSlot < 0
                    || !client.player.getInventory().getItem(this.bucketSlot).is(Items.BUCKET)) {
                beginReturnRotation(client);
                return;
            }
            if (this.rays.invokeUseInPlayerUpdate(client, lavaSourceHit(this.lavaPos))) {
                transition(CyclePhase.CLICKING_TO_PICKUP);
            } else if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
                abortCycle(client);
            }
            return;
        }

        if (this.phase == CyclePhase.CLICKING_TO_PICKUP) {
            if (!SilentPacketRotation.isUseInvocationDone()) {
                if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
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
                if (this.phaseTicks > MAX_ACTION_WAIT_TICKS) {
                    abortCycle(client);
                }
                return;
            }
            boolean bucketRefilled =
                    this.bucketSlot >= 0
                            && client.player
                                    .getInventory()
                                    .getItem(this.bucketSlot)
                                    .is(Items.LAVA_BUCKET);
            if (!bucketRefilled
                    && isLavaSource(client, this.lavaPos)
                    && ++this.bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                return;
            }
            beginReturnRotation(client);
        }
    }

    private void beginLavaHold() {
        if (this.phase != CyclePhase.CLICKING_TO_PLACE) {
            return;
        }
        // The configured hold starts only after the server-confirmed source is
        // visible. Starting it at the local click lets network latency consume
        // the whole delay and makes every value look like instant pickup.
        this.pickupReadyAtNanos = 0L;
        transition(CyclePhase.HOLDING_LAVA);
    }

    private void continueHoldingLava(Minecraft client, boolean advanceSyncTimeout) {
        if (this.phase != CyclePhase.HOLDING_LAVA) {
            return;
        }
        boolean bucketEmptied =
                this.bucketSlot >= 0
                        && client.player.getInventory().getItem(this.bucketSlot).is(Items.BUCKET);
        if (!bucketEmptied || !isLavaSource(client, this.lavaPos)) {
            if (!advanceSyncTimeout || ++this.bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                return;
            }
            beginReturnRotation(client);
            return;
        }
        this.bucketSyncWaitTicks = 0;
        long now = System.nanoTime();
        if (this.pickupReadyAtNanos == 0L) {
            this.pickupReadyAtNanos = now + this.pickupDelayMs.get() * 1_000_000L;
        }
        if (now < this.pickupReadyAtNanos) {
            return;
        }
        BlockHitResult pickupHit = lavaSourceHit(this.lavaPos);
        if (sentLookReachesLavaSource(client, this.lavaPos)) {
            if (this.rays.invokeUseInPlayerUpdate(client, pickupHit)) {
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
        int generation = this.activeGeneration;
        SilentPacketRotation.beginRotation(
                client,
                pickupHit.getLocation(),
                this.activeSmoothTicks,
                () ->
                        transitionIfCurrent(
                                generation,
                                CyclePhase.TURNING_TO_PICKUP,
                                CyclePhase.WAITING_FOR_PICKUP_ROTATION));
    }

    private void beginAfterSlotDelay(Minecraft client) {
        if (this.bucketSlot < 0
                || !client.player.getInventory().getItem(this.bucketSlot).is(Items.LAVA_BUCKET)) {
            abortCycle(client);
            return;
        }
        selectSlot(client, this.bucketSlot);
        Player target = targetById(client, this.activeTargetId);
        if (!Targeting.isValidTargetPlayer(client, target) || target.isOnFire()) {
            finishCycle(client);
            return;
        }
        this.targetMotion.observe(client, target);
        // This is the cycle's one and only placement plan.  It is deliberately
        // made after both configurable delays, from the current camera ray and
        // the latest sampled motion, so later phases cannot jump between cells.
        this.planningViewDirection = client.player.getLookAngle();
        PlacementPlan plan = planAtTarget(client, target, this.activePlacementRoute);
        if (plan == null) {
            finishCycle(client);
            return;
        }
        this.lavaPos = plan.lavaPos().immutable();
        this.placementHit = plan.hit();
        beginPlacementRotation(client);
    }

    private void beginReturnRotation(Minecraft client) {
        if (this.phase == CyclePhase.TURNING_BACK_TO_CAMERA
                || this.phase == CyclePhase.WAITING_FOR_RETURN_ROTATION) {
            return;
        }
        int generation = this.activeGeneration;
        transition(CyclePhase.TURNING_BACK_TO_CAMERA);
        SilentPacketRotation.beginReturnToCamera(
                client,
                Math.max(1, this.activeSmoothTicks),
                () ->
                        transitionIfCurrent(
                                generation,
                                CyclePhase.TURNING_BACK_TO_CAMERA,
                                CyclePhase.WAITING_FOR_RETURN_ROTATION));
    }

    private void scheduleOriginalSlotRestore() {
        if (this.returnSwitchScheduled || this.originalSlot < 0 || this.bucketSlot < 0) {
            return;
        }
        this.returnSwitchScheduled = true;
        this.deferredOriginalSlot = this.originalSlot;
        this.deferredBucketSlot = this.bucketSlot;
        // Pickup completion has its own timing. Reusing the pre-placement slot
        // delay made the return switch feel much slower than the initial swap.
        this.deferredRestoreAtNanos =
                System.nanoTime() + this.returnSwitchDelayMs.get() * 1_000_000L;
    }

    private void restoreOriginalSlotWhenReady(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (this.deferredOriginalSlot < 0
                || System.nanoTime() < this.deferredRestoreAtNanos
                || client == null
                || currentPlayer == null) {
            return;
        }
        if (currentPlayer.getInventory().getSelectedSlot() == this.deferredBucketSlot) {
            selectSlot(client, this.deferredOriginalSlot);
        }
        this.deferredOriginalSlot = -1;
        this.deferredBucketSlot = -1;
        this.deferredRestoreAtNanos = 0L;
    }

    private void finishCycle(Minecraft client) {
        this.cooldownTicks = (int) Math.ceil(this.cooldownSeconds.get() * 20.0D);
        reset(client);
    }

    /** Hard failure path: discard a queued synthetic use and release all ownership. */
    private void abortCycle(Minecraft client) {
        if (client != null && client.options != null) {
            while (client.options.keyUse.consumeClick()) {
                // Prevent a timed-out synthetic click from firing later at the camera ray.
            }
        }
        this.cooldownTicks = Math.max(10, (int) Math.ceil(this.cooldownSeconds.get() * 20.0D));
        reset(client);
    }

    private void transition(CyclePhase next) {
        this.phase = next;
        this.phaseTicks = 0;
    }

    /** Rejects callbacks left behind by a timed-out or replaced cycle. */
    private void transitionIfCurrent(int generation, CyclePhase expected, CyclePhase next) {
        if (this.activeGeneration == generation && this.phase == expected) {
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

    private boolean sentLookReachesLavaSource(Minecraft client, BlockPos source) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult hit =
                this.rays.traceOutline(
                        client,
                        eye,
                        SilentPacketRotation.getInteractionLookVector(client),
                        client.player.blockInteractionRange(),
                        ClipContext.Fluid.SOURCE_ONLY,
                        source);
        return BlockPlacementUtils.matchesBlock(hit, source);
    }

    /** The quantized packet ray must hit the exact planned support face. */
    private boolean sentLookMatchesPlacement(Minecraft client, BlockHitResult plannedHit) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult hit =
                this.rays.traceOutline(
                        client,
                        eye,
                        SilentPacketRotation.getInteractionLookVector(client),
                        client.player.blockInteractionRange(),
                        ClipContext.Fluid.NONE,
                        plannedHit.getBlockPos());
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

    private void clearPendingTrigger() {
        this.pendingTargetId = -1;
        this.pendingExecuteAtNanos = 0L;
        this.pendingWallAttempted = false;
        this.groundLandingWindow.clear();
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

    private PlacementPlan planAtTarget(Minecraft client, Player target, PlacementRoute route) {
        double strength = this.prediction.get();
        Vec3 velocity =
                this.targetMotion.matches(target)
                        ? this.targetMotion.velocity()
                        : target.getDeltaMovement();
        Vec3 acceleration =
                this.targetMotion.matches(target) ? this.targetMotion.acceleration() : Vec3.ZERO;
        double predictionDelayTicks = this.predictionDelayMs.get() / 50.0D;
        double leadTicks = (this.smoothTicks.get() + 1.5D + predictionDelayTicks) * strength;
        Vec3 predicted =
                TrajectoryPrediction.horizontalPosition(
                        client, target, velocity, acceleration, leadTicks);
        BlockPos desired =
                BlockPos.containing(
                        predicted.x, target.getBoundingBox().minY + 1.0E-4D, predicted.z);
        Vec3 eye = client.player.getEyePosition();
        Vec3 look =
                this.planningViewDirection != null
                                && this.planningViewDirection.lengthSqr() > 1.0E-9D
                        ? this.planningViewDirection.normalize()
                        : client.player.getLookAngle();
        double rayLength = Math.min(this.range.get(), client.player.blockInteractionRange());
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
                for (Direction supportDirection : this.supportDirections) {
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
                    Vec3 nearest = RayNearestPoint.closest(facePlane, eye, look, rayLength);
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
    private PlacementPlan planAboveTargetCobweb(
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
                Vec3 nearest = RayNearestPoint.closest(topFace, eye, look, rayLength);
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
    private BlockHitResult visibleFaceHit(
            Minecraft client, BlockPos supportPos, Direction face, Vec3 planePoint) {
        return this.rays.visibleFaceHit(
                client, client.player.getEyePosition(), supportPos, face, planePoint, RAY_EPSILON);
    }

    private static double squaredDistanceToViewRay(
            Vec3 eye, Vec3 look, Vec3 point, double rayLength) {
        return RayGeometry.squaredDistanceToRay(eye, look, point, rayLength);
    }

    private boolean validatePlacement(Minecraft client) {
        if (this.lavaPos == null
                || this.placementHit == null
                || this.bucketSlot < 0
                || !withinRange(client, this.placementHit.getLocation())
                || !client.player.getInventory().getItem(this.bucketSlot).is(Items.LAVA_BUCKET)) {
            return false;
        }
        BlockState state = client.level.getBlockState(this.lavaPos);
        if (!state.canBeReplaced()
                || !state.getFluidState().isEmpty()
                || !safeForLocalPlayer(
                        client, this.lavaPos, Math.max(1.0D, this.activeSmoothTicks + 1.0D))) {
            return false;
        }
        Direction supportDirection = this.placementHit.getDirection().getOpposite();
        BlockPos supportPos = this.lavaPos.relative(supportDirection);
        return supportPos.equals(this.placementHit.getBlockPos())
                && isUsableSupport(
                        client,
                        supportPos,
                        client.level.getBlockState(supportPos),
                        this.placementHit.getDirection())
                && visibleFaceHit(
                                client,
                                supportPos,
                                this.placementHit.getDirection(),
                                this.placementHit.getLocation())
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

    private boolean withinRange(Minecraft client, Vec3 point) {
        double reach = Math.min(this.range.get(), client.player.blockInteractionRange());
        return BlockPlacementUtils.withinReach(client.player.getEyePosition(), point, reach);
    }

    private boolean withinFov(Minecraft client, Vec3 point, Vec3 look) {
        return ViewCone.withinFov(
                ViewCone.viewAngle(client.player.getEyePosition(), look, point), this.fov.get());
    }

    public boolean isBusy() {
        // Waiting for a post-hit landing owns no slot, rotation, or input.
        // Only the active placement/pickup cycle participates in module
        // mutual exclusion.
        return this.phase != CyclePhase.IDLE;
    }

    private void reset(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (isBusy()) SilentPacketRotation.reset();
        boolean deferredForCurrentCycle =
                this.returnSwitchScheduled
                        && this.deferredOriginalSlot == this.originalSlot
                        && this.deferredBucketSlot == this.bucketSlot;
        if (client != null
                && currentPlayer != null
                && this.originalSlot >= 0
                && this.bucketSlot >= 0
                && currentPlayer.getInventory().getSelectedSlot() == this.bucketSlot
                && !deferredForCurrentCycle) {
            selectSlot(client, this.originalSlot);
        }
        CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_LAVA);
        clearPendingTrigger();
        this.activeTargetId = -1;
        this.activePlacementRoute = PlacementRoute.NONE;
        this.activeGeneration = 0;
        this.returnSwitchScheduled = false;
        this.slotReadyAtNanos = 0L;
        this.targetMotion.reset();
        this.planningViewDirection = null;
        this.lavaPos = null;
        this.placementHit = null;
        this.bucketSlot = -1;
        this.originalSlot = -1;
        this.pickupReadyAtNanos = 0L;
        this.bucketSyncWaitTicks = 0;
        this.activeSmoothTicks = 0;
        this.phaseTicks = 0;
        this.cycleTicks = 0;
        transition(CyclePhase.IDLE);
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public String hudTag() {
        return NumberText.compactDouble(this.chance.get() * 100.0D) + "%";
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoLava: "
                        + (this.enabled.get() ? "enabled" : "disabled")
                        + ", critical chance: "
                        + NumberText.compactDouble(this.chance.get())
                        + ", range: "
                        + NumberText.compactDouble(this.range.get())
                        + ", fov: "
                        + NumberText.compactDouble(this.fov.get())
                        + ", wall: "
                        + (this.wallEnabled.get() ? "enabled" : "disabled")
                        + ", ground: "
                        + (this.groundEnabled.get() ? "enabled" : "disabled")
                        + ", trigger delay: "
                        + this.triggerDelayMs.get()
                        + "ms"
                        + ", switch delay: "
                        + this.switchDelayMs.get()
                        + "ms"
                        + ", prediction: "
                        + NumberText.compactDouble(this.prediction.get())
                        + "x"
                        + ", prediction delay: "
                        + this.predictionDelayMs.get()
                        + "ms"
                        + ", pickup delay: "
                        + this.pickupDelayMs.get()
                        + "ms"
                        + ", return switch delay: "
                        + this.returnSwitchDelayMs.get()
                        + "ms"
                        + ", smooth: "
                        + this.smoothTicks.get()
                        + "t"
                        + ", cooldown: "
                        + NumberText.compactDouble(this.cooldownSeconds.get())
                        + "s.");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        if (!value) {
            this.cooldownTicks = 0;
            if (isBusy() || this.pendingTargetId >= 0) {
                reset(client);
            }
        }
        ClientChat.send(client, "AutoLava " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setChance(Minecraft client, double value) {
        this.chance.set(value);
        ClientChat.send(
                client,
                "AutoLava critical chance set to "
                        + NumberText.compactDouble(this.chance.get())
                        + ".");
        return 1;
    }

    public int setRange(Minecraft client, double value) {
        this.range.set(value);
        ClientChat.send(
                client,
                "AutoLava range set to " + NumberText.compactDouble(this.range.get()) + ".");
        return 1;
    }

    public int setFov(Minecraft client, double value) {
        this.fov.set(value);
        ClientChat.send(
                client,
                "AutoLava FOV set to " + NumberText.compactDouble(this.fov.get()) + " degrees.");
        return 1;
    }

    public int setPrediction(Minecraft client, double value) {
        this.prediction.set(value);
        ClientChat.send(
                client,
                "AutoLava prediction set to "
                        + NumberText.compactDouble(this.prediction.get())
                        + "x.");
        return 1;
    }

    public int setPredictionDelayMs(Minecraft client, int value) {
        this.predictionDelayMs.set(value);
        ClientChat.send(
                client,
                "AutoLava prediction delay set to " + this.predictionDelayMs.get() + " ms.");
        return 1;
    }

    public int setTriggerDelayMs(Minecraft client, int value) {
        this.triggerDelayMs.set(value);
        ClientChat.send(
                client, "AutoLava trigger delay set to " + this.triggerDelayMs.get() + " ms.");
        return 1;
    }

    public int setSwitchDelayMs(Minecraft client, int value) {
        this.switchDelayMs.set(value);
        ClientChat.send(
                client, "AutoLava item switch delay set to " + this.switchDelayMs.get() + " ms.");
        return 1;
    }

    public int setPickupDelayMs(Minecraft client, int value) {
        this.pickupDelayMs.set(value);
        ClientChat.send(
                client, "AutoLava pickup delay set to " + this.pickupDelayMs.get() + " ms.");
        return 1;
    }

    public int setWallEnabled(Minecraft client, boolean value) {
        this.wallEnabled.set(value);
        ClientChat.send(
                client,
                "AutoLava immediate wall route "
                        + (this.wallEnabled.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public int setGroundEnabled(Minecraft client, boolean value) {
        this.groundEnabled.set(value);
        ClientChat.send(
                client,
                "AutoLava post-hit landing route "
                        + (this.groundEnabled.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public int setReturnSwitchDelayMs(Minecraft client, int value) {
        this.returnSwitchDelayMs.set(value);
        ClientChat.send(
                client,
                "AutoLava return switch delay set to " + this.returnSwitchDelayMs.get() + " ms.");
        return 1;
    }

    public int setSmoothTicks(Minecraft client, int value) {
        this.smoothTicks.set(value);
        ClientChat.send(
                client, "AutoLava smooth turn set to " + this.smoothTicks.get() + " ticks.");
        return 1;
    }

    public int setCooldown(Minecraft client, double value) {
        this.cooldownSeconds.set(value);
        ClientChat.send(
                client,
                "AutoLava cooldown set to "
                        + NumberText.compactDouble(this.cooldownSeconds.get())
                        + " seconds.");
        return 1;
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

    /** End this feature's pending work without changing its configured toggle. */
    public void shutdown(Minecraft client) {
        reset(client);
        this.cooldownTicks = 0;
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "autolava",
                new Setting[] {
                    chance.describeStored("chance", "Critical chance", .01, this::setChance),
                    range.describeStored("range", "Range", .1, this::setRange),
                    fov.describeStored("fov", "FOV", 1, this::setFov),
                    wallEnabled.describeStored("wall", "Wall", this::setWallEnabled),
                    groundEnabled.describeStored("ground", "Ground", this::setGroundEnabled),
                    triggerDelayMs.describeStored(
                            "trigger_delay", "Trigger delay ms", 5, this::setTriggerDelayMs),
                    switchDelayMs.describeStored(
                            "switch_delay", "Switch delay ms", 5, this::setSwitchDelayMs),
                    prediction.describeStored("prediction", "Prediction", .05, this::setPrediction),
                    predictionDelayMs.describeStored(
                            "prediction_delay",
                            "Prediction delay ms",
                            5,
                            this::setPredictionDelayMs),
                    pickupDelayMs.describeStored(
                            "pickup_delay", "Pickup delay ms", 1, this::setPickupDelayMs),
                    returnSwitchDelayMs.describeStored(
                            "return_switch_delay",
                            "Return switch delay ms",
                            1,
                            this::setReturnSwitchDelayMs),
                    smoothTicks.describeStored("smooth", "Smooth turn", 1, this::setSmoothTicks),
                    cooldownSeconds.describeStored("cooldown", "Cooldown", .05, this::setCooldown)
                });
    }
}
