package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.management.targeting.Targeting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Clears a cobweb around the local player with a hotbar water bucket. Its
 * RotationA is packet-only, using the same path as SilentAura: the camera never
 * moves, but the server sees a smooth turn before each bucket interaction.
 */
public final class AntiWeb {
    private static final int DEFAULT_HOLD_TICKS = 3;
    private static final int MIN_HOLD_TICKS = 1;
    private static final int MAX_HOLD_TICKS = 20;
    private static final int DEFAULT_DELAY_TICKS = 0;
    private static final int MAX_DELAY_TICKS = 20;
    private static final int DEFAULT_ACT_TICKS = 4;
    private static final int MIN_ACT_TICKS = 1;
    private static final int MAX_ACT_TICKS = 20;
    private static final int MAX_BUCKET_SYNC_TICKS = 20;
    private static final int MAX_COLLECT_ATTEMPTS = 3;
    private static final double BOX_EPSILON = 1.0E-4D;
    private static final double FACE_INSET = 0.08D;
    private static final double[] FACE_SAMPLES = {
            -0.38D, -0.19D, 0.0D, 0.19D, 0.38D
    };
    private static final Direction[] WEB_PLACEMENT_FACES = {
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };
    private static final long COUNTER_EVENT_LIFETIME_MS = 750L;
    private static final long SAME_COUNTER_SOURCE_DEBOUNCE_MS = 1000L;
    private static final long SAME_WEB_REARM_DEBOUNCE_MS = 1000L;
    private static final double ENEMY_WATER_REACH = 5.0D;
    private static final double ENEMY_WATER_LOOK_DOT = 0.45D;
    private static final double DEFAULT_COUNTER_RANGE = 4.5D;
    private static final double DEFAULT_COUNTER_FOV = 90.0D;
    private static final int DEFAULT_COUNTER_SMOOTH_TICKS = 2;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("antiweb.enabled")
                    .defaultValue(false)
                    .build();

    private static final IntSetting HOLD_TICKS =
            new IntSetting.Builder()
                    .name("antiweb.holdTicks")
                    .defaultValue(DEFAULT_HOLD_TICKS)
                    .range(MIN_HOLD_TICKS, MAX_HOLD_TICKS)
                    .build();

    private static final IntSetting DELAY_TICKS =
            new IntSetting.Builder()
                    .name("antiweb.delayTicks")
                    .defaultValue(DEFAULT_DELAY_TICKS)
                    .range(0, MAX_DELAY_TICKS)
                    .build();

    private static final IntSetting ACT_TICKS =
            new IntSetting.Builder()
                    .name("antiweb.actTicks")
                    .defaultValue(DEFAULT_ACT_TICKS)
                    .range(MIN_ACT_TICKS, MAX_ACT_TICKS)
                    .build();

    private static final BooleanSetting ANTI_ANTI_WEB_ENABLED =
            new BooleanSetting.Builder()
                    .name("antiweb.antiAntiWeb.enabled")
                    .defaultValue(false)
                    .build();

    private static final DoubleSetting ANTI_ANTI_WEB_RANGE =
            new DoubleSetting.Builder()
                    .name("antiweb.antiAntiWeb.range")
                    .defaultValue(DEFAULT_COUNTER_RANGE)
                    .range(1.0D, 6.0D)
                    .build();

    private static final DoubleSetting ANTI_ANTI_WEB_FOV =
            new DoubleSetting.Builder()
                    .name("antiweb.antiAntiWeb.fov")
                    .defaultValue(DEFAULT_COUNTER_FOV)
                    .range(1.0D, 360.0D)
                    .build();

    private static final IntSetting ANTI_ANTI_WEB_SMOOTH_TICKS =
            new IntSetting.Builder()
                    .name("antiweb.antiAntiWeb.smoothTicks")
                    .defaultValue(DEFAULT_COUNTER_SMOOTH_TICKS)
                    .range(MIN_ACT_TICKS, MAX_ACT_TICKS)
                    .build();

    private static BlockPos activeWebPos;
    private static BlockPos activeWaterPos;
    private static BlockHitResult activePlacementHit;
    private static int activeWaterSlot = -1;
    private static int originalSlot = -1;
    private static WaterCyclePhase waterCyclePhase = WaterCyclePhase.IDLE;
    private static int waterHoldRemainingTicks;
    private static boolean waterHoldStarted;
    private static int bucketSyncWaitTicks;
    private static int collectAttempts;
    private static int activeSmoothTicks;
    private static BlockPos pendingWebPos;
    private static BlockPos pendingRecoveryWaterPos;
    private static int pendingDelayTicks;
    private static volatile CounterEvent pendingCounterEvent;
    private static BlockPos lastCounterSource;
    private static long lastCounterSourceAtMs;
    private static BlockPos lastWebPos;
    private static long lastWebAtMs;

    private AntiWeb() {
    }

    public static void init() {
        EventBus.PLAYER_UPDATE.register("AntiWeb.playerUpdate", AntiWeb::tick);
    }

    /** Called after a client block update with the state that was replaced. */
    public static void onBlockUpdate(BlockPos pos, BlockState previous, BlockState state) {
        if (!ENABLED.get()
                || !ANTI_ANTI_WEB_ENABLED.get()
                || pos == null
                || state == null
                || !state.getFluidState().is(FluidTags.WATER)
                || !state.getFluidState().isSource()) {
            return;
        }
        if (isBusy() && pos.equals(activeWaterPos)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        boolean replacedWeb = previous != null && previous.is(Blocks.COBWEB);
        BlockPos relatedWeb = replacedWeb ? pos.immutable()
                : client.level == null ? null : adjacentCobweb(client, pos);
        if (relatedWeb == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pos.equals(lastCounterSource)
                && now - lastCounterSourceAtMs < SAME_COUNTER_SOURCE_DEBOUNCE_MS) {
            return;
        }
        pendingCounterEvent = new CounterEvent(
                pos.immutable(), relatedWeb.immutable(), now);
        lastCounterSource = pos.immutable();
        lastCounterSourceAtMs = now;
    }

    private static void tick(PlayerUpdateEvent event) {
        Minecraft client = event.client();
        if (!ENABLED.get() || !ready(client)) {
            reset(client);
            clearPendingCounterEvent();
            return;
        }

        if (isBusy()) {
            tickWaterCycle(client);
            return;
        }

        if (AutoLava.isBusy()
                || AntiLava.isBusy()
                || AutoWeb.isBusy()
                || AutoBed.isBusy()
                || AutoObsidian.isBusy()
                || client.level.dimension() == Level.NETHER) {
            return;
        }
        if (tryRecoverWater(client)) {
            return;
        }
        if (tryCounterAntiWeb(client)) {
            return;
        }

        WaterPlacementPlan waterPlan = findPlayerWaterPlan(client);
        int waterSlot = findHotbarSlot(client, Items.WATER_BUCKET);
        if (waterPlan == null || waterSlot < 0) {
            clearPendingWeb();
            return;
        }
        BlockPos webPos = waterPlan.webPos();

        long now = System.currentTimeMillis();
        if (webPos.equals(lastWebPos) && now - lastWebAtMs < SAME_WEB_REARM_DEBOUNCE_MS) {
            clearPendingWeb();
            return;
        }

        if (!webPos.equals(pendingWebPos)) {
            pendingWebPos = webPos.immutable();
            pendingDelayTicks = DELAY_TICKS.get();
            if (pendingDelayTicks > 0) {
                return;
            }
        }
        if (pendingDelayTicks > 0) {
            pendingDelayTicks--;
            return;
        }

        beginWaterCycle(
                client,
                webPos,
                waterPlan.waterPos(),
                waterSlot,
                waterPlan.hit(),
                HOLD_TICKS.get(),
                ACT_TICKS.get());
        clearPendingWeb();
    }

    private static boolean tryCounterAntiWeb(Minecraft client) {
        CounterEvent event = pendingCounterEvent;
        if (event == null) {
            return false;
        }
        if (!ANTI_ANTI_WEB_ENABLED.get()
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

        WaterPlacementPlan waterPlan = planCounterWaterOverwrite(
                client, event.source(), event.webPos());
        int waterSlot = findHotbarSlot(client, Items.WATER_BUCKET);
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
                ANTI_ANTI_WEB_SMOOTH_TICKS.get());
        return true;
    }

    private static void beginWaterCycle(
            Minecraft client,
            BlockPos webPos,
            BlockPos waterPos,
            int waterSlot,
            BlockHitResult placementHit,
            int waterHoldTicks,
            int smoothTicks
    ) {
        activeWebPos = webPos.immutable();
        // Vanilla places the source in the cell adjacent to the clicked web
        // face. Tracking that real server-side position avoids relying on a
        // client-only "water replaces cobweb" rule.
        activeWaterPos = waterPos.immutable();
        activePlacementHit = placementHit;
        activeWaterSlot = waterSlot;
        originalSlot = client.player.getInventory().getSelectedSlot();
        waterHoldRemainingTicks = Math.max(1, waterHoldTicks);
        waterHoldStarted = false;
        bucketSyncWaitTicks = 0;
        collectAttempts = 0;
        activeSmoothTicks = clampActTicks(smoothTicks);
        selectSlot(client, waterSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.ANTI_WEB);
        lastWebPos = activeWebPos;
        lastWebAtMs = System.currentTimeMillis();
        // The current camera look already targets the web cell: run the whole
        // place/hold/collect cycle without any packet RotationA (head stays put).
        if (currentLookMatchesHit(client, activePlacementHit)) {
            startNoRotationCycle(client);
            return;
        }
        waterCyclePhase = WaterCyclePhase.TURNING_TO_PLACE;
        SilentPacketRotation.beginRotation(
                client,
                placementHit.getLocation(),
                activeSmoothTicks,
                () -> waterCyclePhase = WaterCyclePhase.WAITING_FOR_PLACE_ROTATION);
    }

    private static void tickWaterCycle(Minecraft client) {
        if (activeWebPos == null
                || activeWaterPos == null
                || activePlacementHit == null
                || activeWaterSlot < 0) {
            restoreSlot(client);
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.TURNING_TO_PLACE
                || waterCyclePhase == WaterCyclePhase.TURNING_TO_COLLECT
                || waterCyclePhase == WaterCyclePhase.TURNING_BACK_TO_CAMERA) {
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.WAITING_FOR_RETURN_ROTATION) {
            if (SilentPacketRotation.isRotationPacketSent()) {
                float cameraYawDifference = Math.abs(Mth.wrapDegrees(
                        client.player.getYRot() - SilentPacketRotation.getSentYaw()));
                float cameraPitchDifference = Math.abs(
                        client.player.getXRot() - SilentPacketRotation.getSentPitch());
                if (cameraYawDifference <= 0.35F
                        && cameraPitchDifference <= 0.35F) {
                    restoreSlot(client);
                } else {
                    // The user moved the real camera after the final return
                    // packet was sampled. Follow the new angle smoothly rather
                    // than exposing that movement as a one-packet snap.
                    beginReturnRotation(client);
                }
            }
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.WAITING_FOR_PLACE_ROTATION) {
            selectSlot(client, activeWaterSlot);
            if (!client.player.getInventory().getItem(activeWaterSlot)
                    .is(Items.WATER_BUCKET)) {
                beginReturnRotation(client);
                return;
            }
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, activePlacementHit)) {
                waterCyclePhase = WaterCyclePhase.CLICKING_TO_PLACE;
            }
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.CLICKING_TO_PLACE) {
            if (!SilentPacketRotation.isUseDone()) {
                return;
            }
            waterCyclePhase = WaterCyclePhase.HOLDING_WATER;
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.HOLDING_WATER) {
            BlockPos detectedSource = findWaterSourceForPlan(
                    client, activeWebPos, activeWaterPos);
            boolean bucketEmptied = client.player.getInventory()
                    .getItem(activeWaterSlot).is(Items.BUCKET);
            if (!waterHoldStarted) {
                if (!bucketEmptied || detectedSource == null) {
                    if (++bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                        return;
                    }
                    beginReturnRotation(client);
                    return;
                }
                activeWaterPos = detectedSource;
                bucketSyncWaitTicks = 0;
                waterHoldStarted = true;
                // HOLD_TICKS measures real source lifetime. Inventory/block
                // synchronization no longer consumes the configured delay.
                return;
            }
            if (--waterHoldRemainingTicks > 0) {
                return;
            }
            if (!bucketEmptied || detectedSource == null) {
                beginReturnRotation(client);
                return;
            }
            activeWaterPos = detectedSource;
            bucketSyncWaitTicks = 0;
            if (!withinInteractionRange(client, Vec3.atCenterOf(activeWaterPos))) {
                deferWaterRecovery(client);
                return;
            }
            beginCollectAttempt(client);
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.WAITING_FOR_COLLECT_ROTATION) {
            if (!withinInteractionRange(client, Vec3.atCenterOf(activeWaterPos))) {
                deferWaterRecovery(client);
                return;
            }
            selectSlot(client, activeWaterSlot);
            if (!client.player.getInventory().getItem(activeWaterSlot).is(Items.BUCKET)) {
                if (client.player.getInventory().getItem(activeWaterSlot)
                        .is(Items.WATER_BUCKET)) {
                    beginReturnRotation(client);
                }
                return;
            }
            if (!sentLookReachesWaterSource(client, activeWaterPos)) {
                rotateToWaterSource(client);
                return;
            }
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, waterSourceHit(activeWaterPos))) {
                collectAttempts++;
                waterCyclePhase = WaterCyclePhase.CLICKING_TO_COLLECT;
            }
            return;
        }

        if (waterCyclePhase == WaterCyclePhase.CLICKING_TO_COLLECT) {
            if (!SilentPacketRotation.isUseDone()) {
                return;
            }
            boolean bucketRefilled = client.player.getInventory()
                    .getItem(activeWaterSlot).is(Items.WATER_BUCKET);
            if (bucketRefilled) {
                pendingRecoveryWaterPos = null;
                beginReturnRotation(client);
                return;
            }

            BlockPos remainingSource = findWaterSourceForPlan(
                    client, activeWebPos, activeWaterPos);
            if (remainingSource != null
                    && ++bucketSyncWaitTicks <= MAX_BUCKET_SYNC_TICKS) {
                activeWaterPos = remainingSource;
                if (!withinInteractionRange(client, Vec3.atCenterOf(activeWaterPos))) {
                    deferWaterRecovery(client);
                }
                return;
            }
            if (remainingSource != null && collectAttempts < MAX_COLLECT_ATTEMPTS) {
                activeWaterPos = remainingSource;
                bucketSyncWaitTicks = 0;
                beginCollectAttempt(client);
                return;
            }
            beginReturnRotation(client);
        }
    }

    private static void beginCollectAttempt(Minecraft client) {
        selectSlot(client, activeWaterSlot);
        ItemStack stack = client.player.getInventory().getItem(activeWaterSlot);
        if (stack.is(Items.WATER_BUCKET)) {
            beginReturnRotation(client);
            return;
        }
        if (!stack.is(Items.BUCKET)) {
            return;
        }

        rotateToWaterSource(client);
    }

    private static void rotateToWaterSource(Minecraft client) {
        BlockHitResult pickupHit = waterSourceHit(activeWaterPos);
        waterCyclePhase = WaterCyclePhase.TURNING_TO_COLLECT;
        SilentPacketRotation.beginRotation(
                client,
                Vec3.atCenterOf(activeWaterPos),
                1,
                SilentPacketRotation.Mode.INSTANT,
                () -> waterCyclePhase = WaterCyclePhase.WAITING_FOR_COLLECT_ROTATION);
        // Instant pickup rotation and vanilla empty-bucket use share this same
        // PLAYER_UPDATE. The following sendPosition confirms the exact pair.
        if (waterCyclePhase == WaterCyclePhase.WAITING_FOR_COLLECT_ROTATION
                && SilentPacketRotation.invokeUseInPlayerUpdate(client, pickupHit, false)) {
            collectAttempts++;
            waterCyclePhase = WaterCyclePhase.CLICKING_TO_COLLECT;
        }
    }

    private static void beginReturnRotation(Minecraft client) {
        waterCyclePhase = WaterCyclePhase.TURNING_BACK_TO_CAMERA;
        SilentPacketRotation.beginReturnToCamera(
                client,
                activeSmoothTicks,
                () -> waterCyclePhase = WaterCyclePhase.WAITING_FOR_RETURN_ROTATION);
    }

    private static BlockHitResult waterSourceHit(BlockPos waterPos) {
        // The normal crosshair ray does not treat water as a block hit. Giving
        // startUseItem a BLOCK hit here makes it first send USE_ITEM_ON against
        // liquid, which Grim's AirLiquidPlace correctly rejects. The empty
        // bucket's own use path performs its separate SOURCE_ONLY fluid ray.
        return BlockHitResult.miss(
                Vec3.atCenterOf(waterPos),
                Direction.UP,
                waterPos);
    }

    /** Starts the cycle without packet RotationA, reusing the current camera angles. */
    private static void startNoRotationCycle(Minecraft client) {
        SilentPacketRotation.markCurrentAsSent(client);
        waterCyclePhase = WaterCyclePhase.WAITING_FOR_PLACE_ROTATION;
    }

    private static void deferWaterRecovery(Minecraft client) {
        if (activeWaterPos != null && isWaterSource(client, activeWaterPos)) {
            pendingRecoveryWaterPos = activeWaterPos.immutable();
        }
        beginReturnRotation(client);
    }

    /** Resumes only the legal pickup half once the remembered source is in reach again. */
    private static boolean tryRecoverWater(Minecraft client) {
        BlockPos source = pendingRecoveryWaterPos;
        if (source == null) return false;
        if (!isWaterSource(client, source)) {
            pendingRecoveryWaterPos = null;
            return false;
        }
        if (!withinInteractionRange(client, Vec3.atCenterOf(source))) return false;
        int emptyBucketSlot = findHotbarSlot(client, Items.BUCKET);
        if (emptyBucketSlot < 0) return false;

        activeWebPos = source.immutable();
        activeWaterPos = source.immutable();
        activePlacementHit = waterSourceHit(source);
        activeWaterSlot = emptyBucketSlot;
        originalSlot = client.player.getInventory().getSelectedSlot();
        waterHoldRemainingTicks = 0;
        waterHoldStarted = false;
        bucketSyncWaitTicks = 0;
        collectAttempts = 0;
        activeSmoothTicks = clampActTicks(ACT_TICKS.get());
        selectSlot(client, emptyBucketSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.ANTI_WEB);
        rotateToWaterSource(client);
        return true;
    }

    /** Whether the already-sent placement angle can collect the source too. */
    private static boolean sentLookReachesWaterSource(
            Minecraft client,
            BlockPos source
    ) {
        Vec3 eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange();
        Vec3 end = eye.add(SilentPacketRotation.getInteractionLookVector(client).scale(reach));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY,
                client.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(source);
    }

    /** The no-RotationA fast path is only legal when it matches the planned face. */
    private static boolean currentLookMatchesHit(
            Minecraft client,
            BlockHitResult plannedHit
    ) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getLookAngle()
                .scale(client.player.blockInteractionRange()));
        BlockHitResult currentHit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return currentHit.getType() == HitResult.Type.BLOCK
                && currentHit.getBlockPos().equals(plannedHit.getBlockPos())
                && currentHit.getDirection() == plannedHit.getDirection();
    }

    private static WaterPlacementPlan findPlayerWaterPlan(Minecraft client) {
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
                    double score = plan.score()
                            + eye.distanceToSqr(Vec3.atCenterOf(web)) * 0.001D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = plan;
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos findCobwebInBox(
            Minecraft client,
            AABB box,
            BlockPos excludedPos,
            Vec3 referencePoint
    ) {
        int minX = Mth.floor(box.minX + BOX_EPSILON);
        int minY = Mth.floor(box.minY + BOX_EPSILON);
        int minZ = Mth.floor(box.minZ + BOX_EPSILON);
        int maxX = Mth.floor(box.maxX - BOX_EPSILON);
        int maxY = Mth.floor(box.maxY - BOX_EPSILON);
        int maxZ = Mth.floor(box.maxZ - BOX_EPSILON);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.equals(excludedPos)) {
                        continue;
                    }
                    if (!client.level.getBlockState(pos).is(Blocks.COBWEB)) {
                        continue;
                    }
                    double distance = referencePoint.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distance < bestDistance) {
                        best = pos;
                        bestDistance = distance;
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
            Minecraft client,
            BlockPos webPos,
            BlockPos plannedWaterPos
    ) {
        if (isWaterSource(client, plannedWaterPos)) {
            return plannedWaterPos.immutable();
        }
        BlockPos above = webPos.above();
        if (isWaterSource(client, above)) {
            return above.immutable();
        }
        if (isWaterSource(client, webPos)) {
            return webPos.immutable();
        }
        for (Direction direction : Direction.values()) {
            BlockPos candidate = webPos.relative(direction);
            if (!candidate.equals(above) && isWaterSource(client, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
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
    private static WaterPlacementPlan planCounterWaterOverwrite(
            Minecraft client,
            BlockPos source,
            BlockPos webPos
    ) {
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
            Vec3 hitLocation = new Vec3(
                    supportPos.getX() + 0.5D + supportFace.getStepX() * 0.5D,
                    supportPos.getY() + 0.5D + supportFace.getStepY() * 0.5D,
                    supportPos.getZ() + 0.5D + supportFace.getStepZ() * 0.5D);
            if (!withinCounterRange(client, hitLocation)
                    || !withinInteractionRange(client, hitLocation)) {
                continue;
            }
            Vec3 justInsideSupport = hitLocation.add(
                    supportDirection.getStepX() * BOX_EPSILON,
                    supportDirection.getStepY() * BOX_EPSILON,
                    supportDirection.getStepZ() * BOX_EPSILON);
            BlockHitResult visibleHit = client.level.clip(new ClipContext(
                    eye,
                    justInsideSupport,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    client.player));
            if (visibleHit.getType() != HitResult.Type.BLOCK
                    || !visibleHit.getBlockPos().equals(supportPos)
                    || visibleHit.getDirection() != supportFace) {
                continue;
            }
            return new WaterPlacementPlan(
                    webPos.immutable(),
                    source.immutable(),
                    new BlockHitResult(
                            visibleHit.getLocation(), supportFace, supportPos, false),
                    eye.distanceToSqr(hitLocation));
        }
        return null;
    }

    private static Player responsibleEnemy(
            Minecraft client,
            BlockPos source,
            BlockPos webPos
    ) {
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
            if (target.getLookAngle().dot(toSource.scale(1.0D / distance))
                    < ENEMY_WATER_LOOK_DOT) {
                continue;
            }
            if (distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean withinCounterRange(Minecraft client, Vec3 point) {
        double range = Math.min(ANTI_ANTI_WEB_RANGE.get(), client.player.blockInteractionRange());
        return client.player.getEyePosition().distanceToSqr(point) <= range * range;
    }

    private static boolean withinInteractionRange(Minecraft client, Vec3 point) {
        double range = client.player.blockInteractionRange();
        return client.player.getEyePosition().distanceToSqr(point) <= range * range;
    }

    private static boolean withinFov(Minecraft client, Vec3 point) {
        return MathUtils.withinFov(MathUtils.viewAngle(client.player.getEyePosition(),
                client.player.getLookAngle(), point), ANTI_ANTI_WEB_FOV.get());
    }

    private static WaterPlacementPlan planWaterPlacement(
            Minecraft client,
            BlockPos webPos
    ) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        double reach = client.player.blockInteractionRange();
        WaterPlacementPlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : WEB_PLACEMENT_FACES) {
            BlockPos waterPos = webPos.relative(face);
            BlockState waterState = client.level.getBlockState(waterPos);
            if (!waterState.canBeReplaced() || !waterState.getFluidState().isEmpty()) {
                continue;
            }
            for (double first : FACE_SAMPLES) {
                for (double second : FACE_SAMPLES) {
                    Vec3 requested = pointOnFace(
                            webPos, face, first, second);
                    if (!withinInteractionRange(client, requested)) {
                        continue;
                    }
                    BlockHitResult hit = visibleWebFaceHit(
                            client, webPos, face, requested);
                    if (hit == null) {
                        continue;
                    }
                    double score = squaredDistanceToViewRay(
                            eye, look, hit.getLocation(), reach)
                            + eye.distanceToSqr(hit.getLocation()) * 0.001D
                            + (face == Direction.UP ? 0.0D : 0.025D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new WaterPlacementPlan(
                                webPos.immutable(), waterPos.immutable(), hit, score);
                    }
                }
            }
        }
        return best;
    }

    private static Vec3 pointOnFace(
            BlockPos block, Direction face, double first, double second) {
        double limit = 0.5D - FACE_INSET;
        first = Mth.clamp(first, -limit, limit);
        second = Mth.clamp(second, -limit, limit);
        double x = block.getX() + 0.5D;
        double y = block.getY() + 0.5D;
        double z = block.getZ() + 0.5D;
        return switch (face.getAxis()) {
            case X -> new Vec3(
                    x + face.getStepX() * 0.5D, y + first, z + second);
            case Y -> new Vec3(
                    x + first, y + face.getStepY() * 0.5D, z + second);
            case Z -> new Vec3(
                    x + first, y + second, z + face.getStepZ() * 0.5D);
        };
    }

    private static BlockHitResult visibleWebFaceHit(
            Minecraft client,
            BlockPos web,
            Direction face,
            Vec3 requested) {
        Vec3 justInside = requested.add(
                -face.getStepX() * BOX_EPSILON,
                -face.getStepY() * BOX_EPSILON,
                -face.getStepZ() * BOX_EPSILON);
        BlockHitResult hit = client.level.clip(new ClipContext(
                client.player.getEyePosition(), justInside,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(web)
                || hit.getDirection() != face) {
            return null;
        }
        return new BlockHitResult(
                hit.getLocation(), face, web, hit.isInside());
    }

    private static double squaredDistanceToViewRay(
            Vec3 eye, Vec3 look, Vec3 point, double rayLength) {
        Vec3 direction = look.lengthSqr() > 1.0E-9D
                ? look.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
        double along = Mth.clamp(
                point.subtract(eye).dot(direction), 0.0D, rayLength);
        return point.distanceToSqr(eye.add(direction.scale(along)));
    }

    private static int findHotbarSlot(Minecraft client, net.minecraft.world.item.Item item) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean ready(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && MinecraftClientAccess.screen(client) == null
                && !client.player.isDeadOrDying();
    }

    public static boolean isBusy() {
        return activeWaterSlot >= 0 || waterCyclePhase != WaterCyclePhase.IDLE;
    }

    private static void reset(Minecraft client) {
        restoreSlot(client);
        clearPendingWeb();
        pendingRecoveryWaterPos = null;
    }

    private static void clearPendingCounterEvent() {
        pendingCounterEvent = null;
    }

    private static void clearPendingWeb() {
        pendingWebPos = null;
        pendingDelayTicks = 0;
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (client == null || client.player == null || slot < 0 || slot > 8) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() == slot) {
            return;
        }
        client.player.getInventory().setSelectedSlot(slot);
    }

    private static void restoreSlot(Minecraft client) {
        if (client != null
                && client.player != null
                && activeWaterSlot >= 0
                && originalSlot >= 0
                && client.player.getInventory().getSelectedSlot() == activeWaterSlot) {
            selectSlot(client, originalSlot);
        }
        activeWebPos = null;
        activeWaterPos = null;
        activePlacementHit = null;
        activeWaterSlot = -1;
        originalSlot = -1;
        waterCyclePhase = WaterCyclePhase.IDLE;
        waterHoldRemainingTicks = 0;
        waterHoldStarted = false;
        bucketSyncWaitTicks = 0;
        collectAttempts = 0;
        activeSmoothTicks = 0;
        CombatInputController.releaseAttack(client, CombatInputController.Owner.ANTI_WEB);
        SilentPacketRotation.reset();
    }

    public static String statusText() {
        return ENABLED.get() ? HOLD_TICKS.get() + "t" : "";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(client,
                "AntiWeb: " + (ENABLED.get() ? "enabled" : "disabled")
                        + ", water hold: " + HOLD_TICKS.get() + " ticks"
                        + ", delay: " + DELAY_TICKS.get() + " ticks"
                        + ", act: " + ACT_TICKS.get() + " ticks"
                        + ", anti-antiweb: "
                        + (ANTI_ANTI_WEB_ENABLED.get() ? "enabled" : "disabled")
                        + " (range " + format(ANTI_ANTI_WEB_RANGE.get())
                        + ", fov " + format(ANTI_ANTI_WEB_FOV.get())
                        + ", smooth " + ANTI_ANTI_WEB_SMOOTH_TICKS.get() + "t)"
                        + ". Usage: .moons antiweb <enable|disable|hold 1-20|antiantiweb>." );
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset(client);
        return showStatus(client);
    }

    public static int setHoldTicks(Minecraft client, int value) {
        HOLD_TICKS.set(value);
        ClientChat.send(client, "AntiWeb water hold set to " + HOLD_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setDelayTicks(Minecraft client, int value) {
        DELAY_TICKS.set(value);
        clearPendingWeb();
        ClientChat.send(client, "AntiWeb delay set to " + DELAY_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setActTicks(Minecraft client, int value) {
        ACT_TICKS.set(value);
        ClientChat.send(client, "AntiWeb act time set to " + ACT_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setAntiAntiWebEnabled(Minecraft client, boolean value) {
        ANTI_ANTI_WEB_ENABLED.set(value);
        clearPendingCounterEvent();
        return showStatus(client);
    }

    public static int setAntiAntiWebRange(Minecraft client, double value) {
        ANTI_ANTI_WEB_RANGE.set(value);
        return showStatus(client);
    }

    public static int setAntiAntiWebFov(Minecraft client, double value) {
        ANTI_ANTI_WEB_FOV.set(value);
        return showStatus(client);
    }

    public static int setAntiAntiWebSmoothTicks(Minecraft client, int value) {
        ANTI_ANTI_WEB_SMOOTH_TICKS.set(value);
        return showStatus(client);
    }

    private static int clampActTicks(int value) {
        return Math.max(MIN_ACT_TICKS, Math.min(MAX_ACT_TICKS, value));
    }

    private static String format(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }

    private record CounterEvent(
            BlockPos source,
            BlockPos webPos,
            long detectedAtMs
    ) {
    }

    private record WaterPlacementPlan(
            BlockPos webPos,
            BlockPos waterPos,
            BlockHitResult hit,
            double score
    ) {
    }

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
}
