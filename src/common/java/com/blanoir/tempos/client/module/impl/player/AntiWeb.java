package com.blanoir.tempos.client.module.impl.player;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.movement.PlayerUpdateEvent;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RayGeometry;
import com.blanoir.tempos.client.utils.math.ViewCone;
import com.blanoir.tempos.client.utils.player.HotbarQueries;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Clears a cobweb around the local player with a hotbar water bucket. Its
 * RotationA is packet-only, using the same path as SilentAura: the camera never
 * moves, but the server sees a smooth turn before each bucket interaction.
 */
@ModuleInfo(
        category = ModuleCategory.MOVEMENT,
        description = "Uses a water bucket to clear cobwebs around you.")
public final class AntiWeb extends ClientFeature {
    public static final AntiWeb INSTANCE = new AntiWeb();

    private final PlacementRayPolicy rays = new PlacementRayPolicy("antiweb");
    private static final int DEFAULT_HOLD_TICKS = 3;
    private static final int MIN_HOLD_TICKS = 1;
    private static final int MAX_HOLD_TICKS = 20;
    private static final int DEFAULT_DELAY_TICKS = 0;
    private static final int MAX_DELAY_TICKS = 20;
    private static final int DEFAULT_ACT_TICKS = 2;
    private static final int MIN_ACT_TICKS = 1;
    private static final int MAX_ACT_TICKS = 20;
    private static final int MAX_BUCKET_SYNC_TICKS = 20;
    private static final int MAX_COLLECT_ATTEMPTS = 3;
    private static final double BOX_EPSILON = 1.0E-4D;
    private static final double FACE_INSET = 0.08D;
    private final double[] faceSamples = {-0.38D, -0.19D, 0.0D, 0.19D, 0.38D};
    private final Direction[] webPlacementFaces = {
        Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final long COUNTER_EVENT_LIFETIME_MS = 750L;
    private static final long SAME_COUNTER_SOURCE_DEBOUNCE_MS = 1000L;
    private static final long SAME_WEB_REARM_DEBOUNCE_MS = 1000L;
    private static final double ENEMY_WATER_REACH = 5.0D;
    private static final double ENEMY_WATER_LOOK_DOT = 0.45D;
    private static final double DEFAULT_COUNTER_RANGE = 4.5D;
    private static final double DEFAULT_COUNTER_FOV = 90.0D;
    private static final int DEFAULT_COUNTER_SMOOTH_TICKS = 2;

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("antiweb.enabled").defaultValue(false).build();

    private final IntSetting holdTicks =
            new IntSetting.Builder()
                    .name("antiweb.holdTicks")
                    .defaultValue(DEFAULT_HOLD_TICKS)
                    .range(MIN_HOLD_TICKS, MAX_HOLD_TICKS)
                    .build();

    private final IntSetting delayTicks =
            new IntSetting.Builder()
                    .name("antiweb.delayTicks")
                    .defaultValue(DEFAULT_DELAY_TICKS)
                    .range(0, MAX_DELAY_TICKS)
                    .build();

    private final IntSetting actTicks =
            new IntSetting.Builder()
                    .name("antiweb.actTicks")
                    .defaultValue(DEFAULT_ACT_TICKS)
                    .range(MIN_ACT_TICKS, MAX_ACT_TICKS)
                    .build();

    private final BooleanSetting antiAntiWebEnabled =
            new BooleanSetting.Builder()
                    .name("antiweb.antiAntiWeb.enabled")
                    .defaultValue(false)
                    .build();

    private final DoubleSetting antiAntiWebRange =
            new DoubleSetting.Builder()
                    .name("antiweb.antiAntiWeb.range")
                    .defaultValue(DEFAULT_COUNTER_RANGE)
                    .range(1.0D, 6.0D)
                    .build();

    private final DoubleSetting antiAntiWebFov =
            new DoubleSetting.Builder()
                    .name("antiweb.antiAntiWeb.fov")
                    .defaultValue(DEFAULT_COUNTER_FOV)
                    .range(1.0D, 360.0D)
                    .build();

    private final IntSetting antiAntiWebSmoothTicks =
            new IntSetting.Builder()
                    .name("antiweb.antiAntiWeb.smoothTicks")
                    .defaultValue(DEFAULT_COUNTER_SMOOTH_TICKS)
                    .range(MIN_ACT_TICKS, MAX_ACT_TICKS)
                    .build();

    private BlockPos activeWebPos;
    private BlockPos activeWaterPos;
    private BlockHitResult activePlacementHit;
    private int activeWaterSlot = -1;
    private int originalSlot = -1;
    private WaterCyclePhase waterCyclePhase = WaterCyclePhase.IDLE;
    private int waterHoldRemainingTicks;
    private boolean waterHoldStarted;
    private int bucketSyncWaitTicks;
    private int collectAttempts;
    private int activeSmoothTicks;
    private final AntiWebCycleGuard cycleGuard = new AntiWebCycleGuard();
    private long recoveryExpiresAt;
    private int retryAfterTick;
    private BlockPos pendingWebPos;
    private BlockPos pendingRecoveryWaterPos;
    private int pendingDelayTicks;
    private volatile CounterEvent pendingCounterEvent;
    private BlockPos lastCounterSource;
    private long lastCounterSourceAtMs;
    private BlockPos lastWebPos;
    private long lastWebAtMs;

    private AntiWeb() {
        super("antiweb", "AntiWeb");
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AntiWeb.context", event -> shutdown(null));
        PlacementCoordinator.register(PlacementCoordinator.Owner.ANTI_WEB, this::isBusy);
        EventBus.PLAYER_UPDATE.register("AntiWeb.playerUpdate", this::tick);
    }

    /** Called after a client block update with the state that was replaced. */
    public void onBlockUpdate(BlockPos pos, BlockState previous, BlockState state) {
        if (!this.enabled.get()
                || !this.antiAntiWebEnabled.get()
                || pos == null
                || state == null
                || !state.getFluidState().is(FluidTags.WATER)
                || !state.getFluidState().isSource()) {
            return;
        }
        if (isBusy() && pos.equals(this.activeWaterPos)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        boolean replacedWeb = previous != null && previous.is(Blocks.COBWEB);
        BlockPos relatedWeb =
                replacedWeb
                        ? pos.immutable()
                        : client.level == null ? null : adjacentCobweb(client, pos);
        if (relatedWeb == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pos.equals(this.lastCounterSource)
                && now - this.lastCounterSourceAtMs < SAME_COUNTER_SOURCE_DEBOUNCE_MS) {
            return;
        }
        this.pendingCounterEvent = new CounterEvent(pos.immutable(), relatedWeb.immutable(), now);
        this.lastCounterSource = pos.immutable();
        this.lastCounterSourceAtMs = now;
    }

    private void tick(PlayerUpdateEvent event) {
        Minecraft client = event.client();
        if (!this.enabled.get() || !ClientReady.aliveGameplay(client)) {
            reset(client);
            clearPendingCounterEvent();
            return;
        }

        if (isBusy()) {
            if (this.activeWaterSlot >= 0
                    && client.player.getInventory().getSelectedSlot() != this.activeWaterSlot) {
                reset(client);
                this.retryAfterTick = client.player.tickCount + 10;
                return;
            }
            if (this.cycleGuard.expired(
                    this.waterCyclePhase,
                    this.activeSmoothTicks,
                    this.waterCyclePhase == WaterCyclePhase.HOLDING_WATER
                            ? this.holdTicks.get()
                            : 0)) {
                // SilentPacketRotation.reset() can defer while a use packet is pinned. An expired
                // cycle
                // must also cancel that transaction and its deferred callbacks.
                reset(client);
                this.retryAfterTick = client.player.tickCount + 10;
                return;
            }
            tickWaterCycle(client);
            return;
        }

        if (client.player.tickCount < this.retryAfterTick || SilentPacketRotation.isBusy()) return;

        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.ANTI_WEB)
                || client.level.dimension() == Level.NETHER) {
            return;
        }
        WaterPlacementPlan waterPlan = findPlayerWaterPlan(client);
        int waterSlot = HotbarQueries.firstItem(client, Items.WATER_BUCKET);
        if (waterPlan == null || waterSlot < 0) {
            clearPendingWeb();
            if (tryRecoverWater(client)) return;
            // Escaping our own web takes priority over interfering with other players.
            if (waterPlan == null) tryCounterAntiWeb(client);
            return;
        }
        BlockPos webPos = waterPlan.webPos();

        long now = System.currentTimeMillis();
        if (webPos.equals(this.lastWebPos) && now - this.lastWebAtMs < SAME_WEB_REARM_DEBOUNCE_MS) {
            clearPendingWeb();
            return;
        }

        if (!webPos.equals(this.pendingWebPos)) {
            this.pendingWebPos = webPos.immutable();
            this.pendingDelayTicks = this.delayTicks.get();
            if (this.pendingDelayTicks > 0) {
                return;
            }
        }
        if (this.pendingDelayTicks > 0) {
            this.pendingDelayTicks--;
            return;
        }

        beginWaterCycle(
                client,
                webPos,
                waterPlan.waterPos(),
                waterSlot,
                waterPlan.hit(),
                this.holdTicks.get(),
                this.actTicks.get());
        clearPendingWeb();
    }

    private boolean tryCounterAntiWeb(Minecraft client) {
        CounterEvent event = this.pendingCounterEvent;
        if (event == null) {
            return false;
        }
        if (!this.antiAntiWebEnabled.get()
                || System.currentTimeMillis() - event.detectedAtMs() > COUNTER_EVENT_LIFETIME_MS) {
            clearPendingCounterEvent();
            return false;
        }

        clearPendingCounterEvent();
        if (!isWaterSource(client, event.source())
                || !withinCounterRange(client, Vec3.atCenterOf(event.source()))
                || !withinFov(client, Vec3.atCenterOf(event.source()))) {
            return false;
        }

        Player target = responsibleEnemy(client, event.source(), event.webPos());
        if (target == null) {
            return false;
        }

        WaterPlacementPlan waterPlan =
                planCounterWaterOverwrite(client, event.source(), event.webPos());
        int waterSlot = HotbarQueries.firstItem(client, Items.WATER_BUCKET);
        if (waterPlan == null || waterSlot < 0) {
            return false;
        }

        if (!withinCounterRange(client, waterPlan.hit().getLocation())) {
            return false;
        }

        // One held tick gives the client/server inventory update time to turn
        // the water bucket into an empty bucket before collecting the source.
        beginWaterCycle(
                client,
                waterPlan.webPos(),
                waterPlan.waterPos(),
                waterSlot,
                waterPlan.hit(),
                1,
                this.antiAntiWebSmoothTicks.get());
        return true;
    }

    private void beginWaterCycle(
            Minecraft client,
            BlockPos webPos,
            BlockPos waterPos,
            int waterSlot,
            BlockHitResult placementHit,
            int waterHoldTicks,
            int smoothTicks) {
        this.activeWebPos = webPos.immutable();
        // Vanilla places the source in the cell adjacent to the clicked web
        // face. Tracking that real server-side position avoids relying on a
        // client-only "water replaces cobweb" rule.
        this.activeWaterPos = waterPos.immutable();
        this.activePlacementHit = placementHit;
        this.activeWaterSlot = waterSlot;
        this.originalSlot = client.player.getInventory().getSelectedSlot();
        this.waterHoldRemainingTicks = Math.max(1, waterHoldTicks);
        this.waterHoldStarted = false;
        this.bucketSyncWaitTicks = 0;
        this.collectAttempts = 0;
        this.activeSmoothTicks = clampActTicks(smoothTicks);
        selectSlot(client, waterSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.ANTI_WEB);
        this.lastWebPos = this.activeWebPos;
        this.lastWebAtMs = System.currentTimeMillis();
        // The current camera look already targets the web cell: run the whole
        // place/hold/collect cycle without any packet RotationA (head stays put).
        if (currentLookMatchesHit(client, this.activePlacementHit)) {
            startNoRotationCycle(client);
            return;
        }
        this.waterCyclePhase = WaterCyclePhase.TURNING_TO_PLACE;
        SilentPacketRotation.beginRotation(
                client,
                placementHit.getLocation(),
                this.activeSmoothTicks,
                () -> this.waterCyclePhase = WaterCyclePhase.WAITING_FOR_PLACE_ROTATION);
    }

    private void tickWaterCycle(Minecraft client) {
        if (this.activeWebPos == null
                || this.activeWaterPos == null
                || this.activePlacementHit == null
                || this.activeWaterSlot < 0) {
            restoreSlot(client);
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.TURNING_TO_PLACE
                || this.waterCyclePhase == WaterCyclePhase.TURNING_TO_COLLECT
                || this.waterCyclePhase == WaterCyclePhase.TURNING_BACK_TO_CAMERA) {
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.WAITING_FOR_RETURN_ROTATION) {
            if (SilentPacketRotation.isRotationPacketSent()) {
                // The return controller follows the live camera already. Do not
                // start another full return whenever the mouse moves after sampling.
                restoreSlot(client);
            }
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.WAITING_FOR_PLACE_ROTATION) {
            selectSlot(client, this.activeWaterSlot);
            if (!client.player
                    .getInventory()
                    .getItem(this.activeWaterSlot)
                    .is(Items.WATER_BUCKET)) {
                beginReturnRotation(client);
                return;
            }
            if (this.rays.invokeUseInPlayerUpdate(client, this.activePlacementHit)) {
                this.waterCyclePhase = WaterCyclePhase.CLICKING_TO_PLACE;
            }
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.CLICKING_TO_PLACE) {
            if (!SilentPacketRotation.isUseDone()) {
                return;
            }
            this.waterCyclePhase = WaterCyclePhase.HOLDING_WATER;
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.HOLDING_WATER) {
            BlockPos detectedSource =
                    findWaterSourceForPlan(client, this.activeWebPos, this.activeWaterPos);
            boolean bucketEmptied =
                    client.player.getInventory().getItem(this.activeWaterSlot).is(Items.BUCKET);
            if (!this.waterHoldStarted) {
                if (!bucketEmptied || detectedSource == null) {
                    if (++this.bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                        return;
                    }
                    beginReturnRotation(client);
                    return;
                }
                this.activeWaterPos = detectedSource;
                this.bucketSyncWaitTicks = 0;
                this.waterHoldStarted = true;
                // HOLD_TICKS measures real source lifetime. Inventory/block
                // synchronization no longer consumes the configured delay.
                return;
            }
            if (--this.waterHoldRemainingTicks > 0) {
                return;
            }
            if (!bucketEmptied || detectedSource == null) {
                beginReturnRotation(client);
                return;
            }
            this.activeWaterPos = detectedSource;
            this.bucketSyncWaitTicks = 0;
            if (!withinInteractionRange(client, Vec3.atCenterOf(this.activeWaterPos))) {
                deferWaterRecovery(client);
                return;
            }
            beginCollectAttempt(client);
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.WAITING_FOR_COLLECT_ROTATION) {
            if (!withinInteractionRange(client, Vec3.atCenterOf(this.activeWaterPos))) {
                deferWaterRecovery(client);
                return;
            }
            selectSlot(client, this.activeWaterSlot);
            if (!client.player.getInventory().getItem(this.activeWaterSlot).is(Items.BUCKET)) {
                beginReturnRotation(client);
                return;
            }
            if (!sentLookReachesWaterSource(client, this.activeWaterPos)) {
                rotateToWaterSource(client);
                return;
            }
            if (this.rays.invokeUseInPlayerUpdate(client, waterSourceHit(this.activeWaterPos))) {
                this.collectAttempts++;
                this.waterCyclePhase = WaterCyclePhase.CLICKING_TO_COLLECT;
            }
            return;
        }

        if (this.waterCyclePhase == WaterCyclePhase.CLICKING_TO_COLLECT) {
            if (!SilentPacketRotation.isUseDone()) {
                return;
            }
            boolean bucketRefilled =
                    client.player
                            .getInventory()
                            .getItem(this.activeWaterSlot)
                            .is(Items.WATER_BUCKET);
            if (bucketRefilled) {
                this.pendingRecoveryWaterPos = null;
                beginReturnRotation(client);
                return;
            }

            BlockPos remainingSource =
                    findWaterSourceForPlan(client, this.activeWebPos, this.activeWaterPos);
            if (remainingSource != null && ++this.bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                this.activeWaterPos = remainingSource;
                if (!withinInteractionRange(client, Vec3.atCenterOf(this.activeWaterPos))) {
                    deferWaterRecovery(client);
                }
                return;
            }
            if (remainingSource != null && this.collectAttempts < MAX_COLLECT_ATTEMPTS) {
                this.activeWaterPos = remainingSource;
                this.bucketSyncWaitTicks = 0;
                beginCollectAttempt(client);
                return;
            }
            beginReturnRotation(client);
        }
    }

    private void beginCollectAttempt(Minecraft client) {
        selectSlot(client, this.activeWaterSlot);
        ItemStack stack = client.player.getInventory().getItem(this.activeWaterSlot);
        if (stack.is(Items.WATER_BUCKET)) {
            beginReturnRotation(client);
            return;
        }
        if (!stack.is(Items.BUCKET)) {
            beginReturnRotation(client);
            return;
        }

        rotateToWaterSource(client);
    }

    private void rotateToWaterSource(Minecraft client) {
        BlockHitResult pickupHit = waterSourceHit(this.activeWaterPos);
        this.waterCyclePhase = WaterCyclePhase.TURNING_TO_COLLECT;
        SilentPacketRotation.beginRotation(
                client,
                Vec3.atCenterOf(this.activeWaterPos),
                1,
                SilentPacketRotation.Mode.INSTANT,
                () -> this.waterCyclePhase = WaterCyclePhase.WAITING_FOR_COLLECT_ROTATION);
        // Instant pickup rotation and vanilla empty-bucket use share this same
        // PLAYER_UPDATE. The following sendPosition confirms the exact pair.
        if (this.waterCyclePhase == WaterCyclePhase.WAITING_FOR_COLLECT_ROTATION
                && this.rays.invokeUseInPlayerUpdate(client, pickupHit, false)) {
            this.collectAttempts++;
            this.waterCyclePhase = WaterCyclePhase.CLICKING_TO_COLLECT;
        }
    }

    private void beginReturnRotation(Minecraft client) {
        this.waterCyclePhase = WaterCyclePhase.TURNING_BACK_TO_CAMERA;
        SilentPacketRotation.beginReturnToCamera(
                client,
                this.activeSmoothTicks,
                () -> this.waterCyclePhase = WaterCyclePhase.WAITING_FOR_RETURN_ROTATION);
    }

    private static BlockHitResult waterSourceHit(BlockPos waterPos) {
        // The normal crosshair ray does not treat water as a block hit. Giving
        // startUseItem a BLOCK hit here makes it first send USE_ITEM_ON against
        // liquid, which server-side placement checks reject. The empty
        // bucket's own use path performs its separate SOURCE_ONLY fluid ray.
        return BlockHitResult.miss(Vec3.atCenterOf(waterPos), Direction.UP, waterPos);
    }

    /** Starts the cycle without packet RotationA, reusing the current camera angles. */
    private void startNoRotationCycle(Minecraft client) {
        SilentPacketRotation.markCurrentAsSent(client);
        this.waterCyclePhase = WaterCyclePhase.WAITING_FOR_PLACE_ROTATION;
    }

    private void deferWaterRecovery(Minecraft client) {
        if (this.activeWaterPos != null && isWaterSource(client, this.activeWaterPos)) {
            this.pendingRecoveryWaterPos = this.activeWaterPos.immutable();
            this.recoveryExpiresAt = System.nanoTime() + 5_000_000_000L;
        }
        beginReturnRotation(client);
    }

    /** Resumes only the legal pickup half once the remembered source is in reach again. */
    private boolean tryRecoverWater(Minecraft client) {
        BlockPos source = this.pendingRecoveryWaterPos;
        if (source == null) return false;
        if (System.nanoTime() >= this.recoveryExpiresAt || !isWaterSource(client, source)) {
            this.pendingRecoveryWaterPos = null;
            return false;
        }
        if (!withinInteractionRange(client, Vec3.atCenterOf(source))) return false;
        int emptyBucketSlot = HotbarQueries.firstItem(client, Items.BUCKET);
        if (emptyBucketSlot < 0) return false;

        this.pendingRecoveryWaterPos = null; // A remembered source gets one bounded recovery cycle.
        this.activeWebPos = source.immutable();
        this.activeWaterPos = source.immutable();
        this.activePlacementHit = waterSourceHit(source);
        this.activeWaterSlot = emptyBucketSlot;
        this.originalSlot = client.player.getInventory().getSelectedSlot();
        this.waterHoldRemainingTicks = 0;
        this.waterHoldStarted = false;
        this.bucketSyncWaitTicks = 0;
        this.collectAttempts = 0;
        this.activeSmoothTicks = clampActTicks(this.actTicks.get());
        selectSlot(client, emptyBucketSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.ANTI_WEB);
        rotateToWaterSource(client);
        return true;
    }

    /** Whether the already-sent placement angle can collect the source too. */
    private boolean sentLookReachesWaterSource(Minecraft client, BlockPos source) {
        Vec3 eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange();
        BlockHitResult hit =
                this.rays.traceOutline(
                        client,
                        eye,
                        SilentPacketRotation.getInteractionLookVector(client),
                        reach,
                        ClipContext.Fluid.SOURCE_ONLY,
                        source);
        return BlockPlacementUtils.matchesBlock(hit, source);
    }

    /** The no-RotationA fast path is only legal when it matches the planned face. */
    private boolean currentLookMatchesHit(Minecraft client, BlockHitResult plannedHit) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult currentHit =
                this.rays.traceOutline(
                        client,
                        eye,
                        client.player.getLookAngle(),
                        client.player.blockInteractionRange(),
                        ClipContext.Fluid.NONE,
                        plannedHit.getBlockPos());
        return BlockPlacementUtils.matchesFace(currentHit, plannedHit);
    }

    private WaterPlacementPlan findPlayerWaterPlan(Minecraft client) {
        // Evaluate every web intersecting the player. A head web whose top face
        // is hidden must not suppress a reachable feet/edge web candidate.
        AABB box = client.player.getBoundingBox();
        int minX = Mth.floor(box.minX + BOX_EPSILON);
        int minY = Mth.floor(box.minY + BOX_EPSILON);
        int minZ = Mth.floor(box.minZ + BOX_EPSILON);
        int maxX = Mth.floor(box.maxX - BOX_EPSILON);
        int maxY = Mth.floor(box.maxY - BOX_EPSILON);
        int maxZ = Mth.floor(box.maxZ - BOX_EPSILON);
        WaterPlacementPlan best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 eye = client.player.getEyePosition();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos web = new BlockPos(x, y, z);
                    if (!client.level.getBlockState(web).is(Blocks.COBWEB)) {
                        continue;
                    }
                    WaterPlacementPlan plan = planWaterPlacement(client, web);
                    if (plan == null) {
                        continue;
                    }
                    double score = plan.score() + eye.distanceToSqr(Vec3.atCenterOf(web)) * 0.001D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = plan;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isWaterSource(Minecraft client, BlockPos pos) {
        return client.level.getFluidState(pos).is(FluidTags.WATER)
                && client.level.getFluidState(pos).isSource();
    }

    private static BlockPos findWaterSourceForPlan(
            Minecraft client, BlockPos webPos, BlockPos plannedWaterPos) {
        // A nearby source may belong to someone else. Only recover the water this
        // cycle actually planned, so a stale adjacent source cannot prolong the cycle.
        return isWaterSource(client, plannedWaterPos) ? plannedWaterPos.immutable() : null;
    }

    private static BlockPos adjacentCobweb(Minecraft client, BlockPos source) {
        for (Direction direction : Direction.values()) {
            BlockPos candidate = source.relative(direction);
            if (client.level.getBlockState(candidate).is(Blocks.COBWEB)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    /**
     * Uses a solid face next to the existing source so the water bucket replaces
     * that exact water voxel. The bucket then becomes empty and the shared cycle
     * immediately collects the same source.
     */
    private WaterPlacementPlan planCounterWaterOverwrite(
            Minecraft client, BlockPos source, BlockPos webPos) {
        if (!isWaterSource(client, source)) {
            return null;
        }
        Direction[] supportOrder = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.UP
        };
        Vec3 eye = client.player.getEyePosition();
        for (Direction supportDirection : supportOrder) {
            BlockPos supportPos = source.relative(supportDirection);
            BlockState supportState = client.level.getBlockState(supportPos);
            if (supportState.getCollisionShape(client.level, supportPos).isEmpty()) {
                continue;
            }
            Direction supportFace = supportDirection.getOpposite();
            Vec3 hitLocation =
                    new Vec3(
                            supportPos.getX() + 0.5D + supportFace.getStepX() * 0.5D,
                            supportPos.getY() + 0.5D + supportFace.getStepY() * 0.5D,
                            supportPos.getZ() + 0.5D + supportFace.getStepZ() * 0.5D);
            if (!withinCounterRange(client, hitLocation)
                    || !withinInteractionRange(client, hitLocation)) {
                continue;
            }
            BlockHitResult visibleHit =
                    this.rays.visibleFaceHit(
                            client, eye, supportPos, supportFace, hitLocation, BOX_EPSILON);
            if (visibleHit == null) continue;
            return new WaterPlacementPlan(
                    webPos.immutable(),
                    source.immutable(),
                    new BlockHitResult(visibleHit.getLocation(), supportFace, supportPos, false),
                    eye.distanceToSqr(hitLocation));
        }
        return null;
    }

    private static Player responsibleEnemy(Minecraft client, BlockPos source, BlockPos webPos) {
        Vec3 center = Vec3.atCenterOf(source);
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player target : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, target)) {
                continue;
            }
            AABB targetBox = target.getBoundingBox().inflate(0.05D);
            if (!targetBox.intersects(new AABB(source))
                    && !targetBox.intersects(new AABB(webPos))) {
                continue;
            }

            Vec3 toSource = center.subtract(target.getEyePosition());
            double distance = toSource.length();
            if (distance > ENEMY_WATER_REACH || distance < 1.0E-5D) {
                continue;
            }
            if (target.getLookAngle().dot(toSource.scale(1.0D / distance)) < ENEMY_WATER_LOOK_DOT) {
                continue;
            }
            if (distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean withinCounterRange(Minecraft client, Vec3 point) {
        double range = Math.min(this.antiAntiWebRange.get(), client.player.blockInteractionRange());
        return client.player.getEyePosition().distanceToSqr(point) <= range * range;
    }

    private static boolean withinInteractionRange(Minecraft client, Vec3 point) {
        double range = client.player.blockInteractionRange();
        return client.player.getEyePosition().distanceToSqr(point) <= range * range;
    }

    private boolean withinFov(Minecraft client, Vec3 point) {
        return ViewCone.withinFov(
                ViewCone.viewAngle(
                        client.player.getEyePosition(), client.player.getLookAngle(), point),
                this.antiAntiWebFov.get());
    }

    private WaterPlacementPlan planWaterPlacement(Minecraft client, BlockPos webPos) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        double reach = client.player.blockInteractionRange();
        WaterPlacementPlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : this.webPlacementFaces) {
            BlockPos waterPos = webPos.relative(face);
            BlockState waterState = client.level.getBlockState(waterPos);
            if (!waterState.canBeReplaced() || !waterState.getFluidState().isEmpty()) {
                continue;
            }
            for (double first : this.faceSamples) {
                for (double second : this.faceSamples) {
                    Vec3 requested = pointOnFace(webPos, face, first, second);
                    if (!withinInteractionRange(client, requested)) {
                        continue;
                    }
                    BlockHitResult hit = visibleWebFaceHit(client, webPos, face, requested);
                    if (hit == null) {
                        continue;
                    }
                    double score =
                            squaredDistanceToViewRay(eye, look, hit.getLocation(), reach)
                                    + eye.distanceToSqr(hit.getLocation()) * 0.001D
                                    + (face == Direction.UP ? 0.0D : 0.025D);
                    if (score < bestScore) {
                        bestScore = score;
                        best =
                                new WaterPlacementPlan(
                                        webPos.immutable(), waterPos.immutable(), hit, score);
                    }
                }
            }
        }
        return best;
    }

    private static Vec3 pointOnFace(BlockPos block, Direction face, double first, double second) {
        double limit = 0.5D - FACE_INSET;
        first = Math.clamp(first, -limit, limit);
        second = Math.clamp(second, -limit, limit);
        return BlockPlacementUtils.fullBlockFaceOffset(block, face, first, second);
    }

    private BlockHitResult visibleWebFaceHit(
            Minecraft client, BlockPos web, Direction face, Vec3 requested) {
        return this.rays.visibleFaceHit(
                client, client.player.getEyePosition(), web, face, requested, BOX_EPSILON);
    }

    private static double squaredDistanceToViewRay(
            Vec3 eye, Vec3 look, Vec3 point, double rayLength) {
        return RayGeometry.squaredDistanceToRay(eye, look, point, rayLength);
    }

    public boolean isBusy() {
        return this.activeWaterSlot >= 0 || this.waterCyclePhase != WaterCyclePhase.IDLE;
    }

    private void reset(Minecraft client) {
        if (isBusy()) SilentPacketRotation.discard();
        restoreSlot(client);
        clearPendingWeb();
        this.pendingRecoveryWaterPos = null;
    }

    private void clearPendingCounterEvent() {
        this.pendingCounterEvent = null;
    }

    private void clearPendingWeb() {
        this.pendingWebPos = null;
        this.pendingDelayTicks = 0;
    }

    private static void selectSlot(Minecraft client, int slot) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || slot < 0 || slot > 8) {
            return;
        }
        if (currentPlayer.getInventory().getSelectedSlot() == slot) {
            return;
        }
        currentPlayer.getInventory().setSelectedSlot(slot);
    }

    private void restoreSlot(Minecraft client) {
        boolean ownedRotation = isBusy();
        var currentPlayer = client == null ? null : client.player;
        if (client != null
                && currentPlayer != null
                && this.activeWaterSlot >= 0
                && this.originalSlot >= 0
                && currentPlayer.getInventory().getSelectedSlot() == this.activeWaterSlot) {
            selectSlot(client, this.originalSlot);
        }
        this.activeWebPos = null;
        this.activeWaterPos = null;
        this.activePlacementHit = null;
        this.activeWaterSlot = -1;
        this.originalSlot = -1;
        this.waterCyclePhase = WaterCyclePhase.IDLE;
        this.waterHoldRemainingTicks = 0;
        this.waterHoldStarted = false;
        this.bucketSyncWaitTicks = 0;
        this.collectAttempts = 0;
        this.activeSmoothTicks = 0;
        this.cycleGuard.reset();
        CombatInputController.releaseAttack(client, CombatInputController.Owner.ANTI_WEB);
        if (ownedRotation) SilentPacketRotation.reset();
    }

    public String statusText() {
        return this.enabled.get() ? this.holdTicks.get() + "t" : "";
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AntiWeb: "
                        + (this.enabled.get() ? "enabled" : "disabled")
                        + ", water hold: "
                        + this.holdTicks.get()
                        + " ticks"
                        + ", delay: "
                        + this.delayTicks.get()
                        + " ticks"
                        + ", act: "
                        + this.actTicks.get()
                        + " ticks"
                        + ", anti-antiweb: "
                        + (this.antiAntiWebEnabled.get() ? "enabled" : "disabled")
                        + " (range "
                        + NumberText.compactDouble(this.antiAntiWebRange.get())
                        + ", fov "
                        + NumberText.compactDouble(this.antiAntiWebFov.get())
                        + ", smooth "
                        + this.antiAntiWebSmoothTicks.get()
                        + "t)"
                        + ". Usage: .tempos antiweb <enable|disable|hold 1-20|antiantiweb>.");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        reset(client);
        return showStatus(client);
    }

    public int setHoldTicks(Minecraft client, int value) {
        this.holdTicks.set(value);
        ClientChat.send(client, "AntiWeb water hold set to " + this.holdTicks.get() + " ticks.");
        return 1;
    }

    public int setDelayTicks(Minecraft client, int value) {
        this.delayTicks.set(value);
        clearPendingWeb();
        ClientChat.send(client, "AntiWeb delay set to " + this.delayTicks.get() + " ticks.");
        return 1;
    }

    public int setActTicks(Minecraft client, int value) {
        this.actTicks.set(value);
        ClientChat.send(client, "AntiWeb act time set to " + this.actTicks.get() + " ticks.");
        return 1;
    }

    public int setAntiAntiWebEnabled(Minecraft client, boolean value) {
        this.antiAntiWebEnabled.set(value);
        clearPendingCounterEvent();
        return showStatus(client);
    }

    public int setAntiAntiWebRange(Minecraft client, double value) {
        this.antiAntiWebRange.set(value);
        return showStatus(client);
    }

    public int setAntiAntiWebFov(Minecraft client, double value) {
        this.antiAntiWebFov.set(value);
        return showStatus(client);
    }

    public int setAntiAntiWebSmoothTicks(Minecraft client, int value) {
        this.antiAntiWebSmoothTicks.set(value);
        return showStatus(client);
    }

    private static int clampActTicks(int value) {
        return Math.max(MIN_ACT_TICKS, Math.min(MAX_ACT_TICKS, value));
    }

    private record CounterEvent(BlockPos source, BlockPos webPos, long detectedAtMs) {}

    private record WaterPlacementPlan(
            BlockPos webPos, BlockPos waterPos, BlockHitResult hit, double score) {}

    private enum WaterCyclePhase {
        IDLE,
        TURNING_TO_PLACE,
        WAITING_FOR_PLACE_ROTATION,
        CLICKING_TO_PLACE,
        HOLDING_WATER,
        TURNING_TO_COLLECT,
        WAITING_FOR_COLLECT_ROTATION,
        CLICKING_TO_COLLECT,
        TURNING_BACK_TO_CAMERA,
        WAITING_FOR_RETURN_ROTATION
    }

    /** End this feature's pending work without changing its configured toggle. */
    public void shutdown(Minecraft client) {
        reset(client);
        this.retryAfterTick = 0;
        clearPendingCounterEvent();
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("antiweb.enabled", false);
    }

    @Override
    protected String statusTag() {
        return "Predict";
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "antiweb",
                new Setting[] {
                    holdTicks.describeStored(
                            "hold_ticks", "Water hold ticks", 1, this::setHoldTicks),
                    delayTicks.describeStored("delay_ticks", "Delay ticks", 1, this::setDelayTicks),
                    actTicks.describeStored("act_ticks", "Act ticks", 1, this::setActTicks),
                    antiAntiWebEnabled.describeStored(
                            "anti_antiweb", "Anti-antiweb", this::setAntiAntiWebEnabled),
                    antiAntiWebRange.describeStored(
                            "anti_antiweb_range",
                            "Anti-antiweb range",
                            .1,
                            this::setAntiAntiWebRange),
                    antiAntiWebFov.describeStored(
                            "anti_antiweb_fov", "Anti-antiweb FOV", 1, this::setAntiAntiWebFov),
                    antiAntiWebSmoothTicks.describeStored(
                            "anti_antiweb_smooth",
                            "Anti-antiweb smooth ticks",
                            1,
                            this::setAntiAntiWebSmoothTicks)
                });
    }
}
