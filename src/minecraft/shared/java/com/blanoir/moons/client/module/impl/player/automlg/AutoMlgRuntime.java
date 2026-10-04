package com.blanoir.moons.client.module.impl.player.automlg;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.FluidQueries;
import com.blanoir.moons.client.utils.world.LegacyRay;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.LegacyWorld.FluidState;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** AutoMLG timing, placement and recovery state machine. */
public final class AutoMlgRuntime {
    private static final PlacementRaycast RAYS = new PlacementRaycast("nofall");
    private float accumulatedFall;
    private double lastY;
    private Integer slotToRestore;
    private boolean waterPlaced;
    private BlockPos placedWaterPos;
    private int postPlaceCooldown;
    private int postActionCooldown;
    private SilentUsePhase silentUsePhase = SilentUsePhase.IDLE;
    private SilentUseAction silentUseAction;
    private MovingObjectPosition silentUseHit;
    private LegacyRay.Fluid silentUseFluid;
    private int silentUseSlot = -1;
    private boolean silentUseRecovery;
    private int silentUseTicks;
    private int silentUseTick;
    private SilentPacketRotation.Mode configuredRotation = SilentPacketRotation.Mode.INSTANT;
    private SilentPacketRotation.Mode actionRotation = SilentPacketRotation.Mode.INSTANT;
    private int configuredTurnTicks = 2;
    private int configuredReturnTicks = 2;
    private int actionTurnTicks = 2;
    private int actionReturnTicks = 2;
    private int placementPredictTicks;
    private boolean placementSolidCheck;

    public void tick(
            Minecraft client,
            double triggerDistance,
            int predictTicks,
            boolean solidCheck,
            boolean recovery,
            SilentPacketRotation.Mode rotationMode,
            int smoothTurnTicks,
            int smoothReturnTicks) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || client.theWorld == null) return;
        configuredRotation = rotationMode;
        configuredTurnTicks = smoothTurnTicks;
        configuredReturnTicks = smoothReturnTicks;
        placementPredictTicks = predictTicks;
        placementSolidCheck = solidCheck;

        if (currentPlayer.onGround
                || currentPlayer.capabilities.isFlying
                || currentPlayer.isWet()
                || currentPlayer.isInLava()) {
            accumulatedFall = 0.0F;
        } else {
            double deltaY = currentPlayer.posY - lastY;
            if (deltaY < 0.0D) accumulatedFall -= (float) deltaY;
        }
        lastY = currentPlayer.posY;

        if (postPlaceCooldown > 0) postPlaceCooldown--;
        if (postActionCooldown > 0) postActionCooldown--;
        if (currentPlayer.onGround || accumulatedFall <= 0.0F) {
            waterPlaced = false;
        }

        if (silentUsePhase != SilentUsePhase.IDLE) {
            tickSilentUse(client);
            return;
        }

        int emptyBucketSlot;
        BlockPos bucketPos;
        if (!waterPlaced
                && placedWaterPos == null
                && postPlaceCooldown == 0
                && postActionCooldown == 0
                && accumulatedFall <= 0.5F
                && HotbarQueries.firstItem(client, Items.water_bucket) < 0
                && (emptyBucketSlot = HotbarQueries.firstItem(client, Items.bucket)) >= 0
                && (bucketPos = findBucketPos(client)) != null) {
            Rotation rotation = rotationToBlock(client, bucketPos);
            MovingObjectPosition hit =
                    RAYS.traceOutline(
                            client,
                            rotation,
                            Minecraft.getMinecraft().playerController.getBlockReachDistance(),
                            LegacyRay.Fluid.SOURCE_ONLY,
                            bucketPos);
            if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.MISS
                    && hit.getBlockPos().equals(bucketPos)) {
                startSilentUse(
                        client,
                        SilentUseAction.FILL_BUCKET,
                        hit,
                        LegacyRay.Fluid.SOURCE_ONLY,
                        emptyBucketSlot,
                        false);
                return;
            }
        }

        if (waterPlaced || accumulatedFall < triggerDistance) return;

        int waterSlot = HotbarQueries.firstItem(client, Items.water_bucket);
        if (waterSlot < 0) return;
        boolean smooth = configuredRotation == SilentPacketRotation.Mode.SMOOTH;
        MovingObjectPosition hit =
                AutoMlgLanding.find(
                        client,
                        smooth ? Math.max(8, predictTicks + 1) : predictTicks + 1,
                        solidCheck,
                        !smooth);
        if (hit == null) return;
        placeWaterBucket(client, waterSlot, hit, recovery);
    }

    public void reset(Minecraft client) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (slotToRestore != null && client != null && currentPlayer != null) {
            currentPlayer.inventory.currentItem = slotToRestore;
        }
        if (silentUsePhase != SilentUsePhase.IDLE) {
            SilentPacketRotation.reset();
        }
        clearSilentUse();
        slotToRestore = null;
        waterPlaced = false;
        placedWaterPos = null;
        postPlaceCooldown = 0;
        postActionCooldown = 0;
        accumulatedFall = 0.0F;
        lastY = client != null && currentPlayer != null ? currentPlayer.posY : 0.0D;
    }

    private void placeWaterBucket(
            Minecraft client, int slot, MovingObjectPosition hit, boolean recovery) {
        startSilentUse(
                client, SilentUseAction.PLACE_WATER, hit, LegacyRay.Fluid.NONE, slot, recovery);
    }

    private void startSilentUse(
            Minecraft client,
            SilentUseAction action,
            MovingObjectPosition hit,
            LegacyRay.Fluid fluid,
            int slot,
            boolean recovery) {
        if (silentUsePhase != SilentUsePhase.IDLE || SilentPacketRotation.isBusy()) {
            return;
        }
        silentUseAction = action;
        silentUseHit = hit;
        silentUseFluid = fluid;
        silentUseSlot = slot;
        silentUseRecovery = recovery;
        actionRotation = configuredRotation;
        actionTurnTicks = configuredTurnTicks;
        actionReturnTicks = configuredReturnTicks;
        silentUseTicks = 0;
        turnForUse(client, hit.hitVec);
    }

    private void turnForUse(Minecraft client, Vec3 target) {
        silentUsePhase = SilentUsePhase.TURNING;
        SilentPacketRotation.beginRotation(
                client,
                target,
                actionRotation == SilentPacketRotation.Mode.SMOOTH ? actionTurnTicks : 1,
                actionRotation,
                () -> {
                    if (silentUsePhase == SilentUsePhase.TURNING) {
                        silentUsePhase = SilentUsePhase.READY_TO_USE;
                    }
                });
        if (silentUsePhase == SilentUsePhase.READY_TO_USE) {
            tickSilentUse(client);
        }
    }

    private void tickSilentUse(Minecraft client) {
        if (++silentUseTicks > 12) {
            abortSilentUse(client);
            return;
        }
        switch (silentUsePhase) {
            case TURNING, RETURNING -> {
                return;
            }
            case READY_TO_USE -> {
                if (silentUseAction == SilentUseAction.PLACE_WATER
                        && actionRotation == SilentPacketRotation.Mode.SMOOTH) {
                    // Smooth may finish while the predicted landing is still outside use range.
                    // Wait for the normal placement window, then verify the first support again.
                    if (client.thePlayer.onGround
                            || client.thePlayer.isInWater()
                            || client.thePlayer.isInLava()) {
                        abortSilentUse(client);
                        return;
                    }
                    MovingObjectPosition landing =
                            AutoMlgLanding.find(
                                    client, placementPredictTicks + 1, placementSolidCheck);
                    if (landing == null) return;
                    if (silentUseHit == null
                            || !landing.getBlockPos().equals(silentUseHit.getBlockPos())
                            || landing.sideHit != silentUseHit.sideHit) {
                        abortSilentUse(client);
                        return;
                    }
                }
                if (actionRotation == SilentPacketRotation.Mode.SMOOTH
                        && !SilentPacketRotation.isRotationPacketSent()) return;
                Rotation sent =
                        new Rotation(
                                SilentPacketRotation.getInteractionYaw(client),
                                SilentPacketRotation.getInteractionPitch(client));
                double range = Minecraft.getMinecraft().playerController.getBlockReachDistance();
                MovingObjectPosition currentHit =
                        RAYS.traceOutline(
                                client,
                                sent,
                                range,
                                silentUseFluid,
                                silentUseHit == null ? null : silentUseHit.getBlockPos());
                if (currentHit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS
                        || silentUseHit == null
                        || !currentHit.getBlockPos().equals(silentUseHit.getBlockPos())
                        || (silentUseAction == SilentUseAction.PLACE_WATER
                                && currentHit.sideHit != silentUseHit.sideHit)) {
                    abortSilentUse(client);
                    return;
                }
                var expectedItem =
                        silentUseAction == SilentUseAction.PLACE_WATER
                                ? Items.water_bucket
                                : Items.bucket;
                if (!LegacyItems.is(
                        client.thePlayer.inventory.getStackInSlot(silentUseSlot), expectedItem)) {
                    abortSilentUse(client);
                    return;
                }
                silentUseHit = currentHit;
                selectSlot(client, silentUseSlot);
                // A bucket has its own fluid ray. A fluid BLOCK hit would also send
                // USE_ITEM_ON against the water, which is not a block interaction.
                MovingObjectPosition useHit =
                        silentUseAction == SilentUseAction.PLACE_WATER
                                ? currentHit
                                : LegacyWorld.miss(
                                        currentHit.hitVec,
                                        currentHit.sideHit,
                                        currentHit.getBlockPos());
                // Keep slot sync/use/swing before this tick's movement. The instant
                // angle stays pinned until that following movement is sent.
                if (!RAYS.invokeUseInPlayerUpdate(
                        client, useHit, actionRotation == SilentPacketRotation.Mode.SMOOTH)) {
                    abortSilentUse(client);
                    return;
                }
                silentUseTick = client.thePlayer.ticksExisted;
                completeSilentUse(client);
                silentUsePhase = SilentUsePhase.WAITING_FOR_USE;
            }
            case WAITING_FOR_USE -> {
                if (!SilentPacketRotation.isUseDone()) {
                    return;
                }
                if (silentUseAction == SilentUseAction.PLACE_WATER
                        && silentUseRecovery
                        && placedWaterPos != null) {
                    silentUsePhase = SilentUsePhase.RECOVERING;
                    tickRecovery(client);
                } else {
                    beginReturn(client);
                }
            }
            case RECOVERING -> tickRecovery(client);
            case WAITING_FOR_RETURN_PACKET -> {
                if (!SilentPacketRotation.isRotationPacketSent()) {
                    return;
                }
                SilentPacketRotation.reset();
                clearSilentUse();
            }
            case IDLE -> {}
        }
    }

    private void completeSilentUse(Minecraft client) {
        if (silentUseAction == SilentUseAction.PLACE_WATER) {
            // Vanilla predicts both the emptied bucket and the source locally.
            // Waterlogged supports store the water in the hit block itself.
            BlockPos support = silentUseHit.getBlockPos();
            BlockPos adjacent = support.offset(silentUseHit.sideHit);
            boolean emptied =
                    LegacyItems.is(
                            client.thePlayer.inventory.getStackInSlot(silentUseSlot), Items.bucket);
            placedWaterPos =
                    !emptied
                            ? null
                            : isWaterSource(client, support)
                                    ? support
                                    : isWaterSource(client, adjacent) ? adjacent : null;
            waterPlaced = placedWaterPos != null;
        } else if (silentUseAction == SilentUseAction.FILL_BUCKET) {
            postActionCooldown = 8;
            postPlaceCooldown = Math.max(postPlaceCooldown, 1);
        }
    }

    private void tickRecovery(Minecraft client) {
        // Count from the actual use invocation, not the rotation/return callbacks.
        if (client.thePlayer.ticksExisted - silentUseTick < 1) return;
        if (placedWaterPos == null
                || !isWaterSource(client, placedWaterPos)
                || !LegacyItems.is(
                        client.thePlayer.inventory.getStackInSlot(silentUseSlot), Items.bucket)) {
            beginReturn(client);
            return;
        }
        // Placement prediction can run before the fall reaches the source.
        // Leave the cushion in place until the player has actually reached it.
        if (!client.thePlayer.isInWater() && !client.thePlayer.onGround) return;
        Rotation current =
                new Rotation(SilentPacketRotation.getYaw(), SilentPacketRotation.getPitch());
        MovingObjectPosition hit =
                RAYS.traceOutline(
                        client,
                        current,
                        Minecraft.getMinecraft().playerController.getBlockReachDistance(),
                        LegacyRay.Fluid.SOURCE_ONLY,
                        placedWaterPos);
        boolean reuseRotation = BlockPlacementUtils.matchesBlock(hit, placedWaterPos);
        if (!reuseRotation) {
            hit =
                    RAYS.traceOutline(
                            client,
                            rotationToBlock(client, placedWaterPos),
                            Minecraft.getMinecraft().playerController.getBlockReachDistance(),
                            LegacyRay.Fluid.SOURCE_ONLY,
                            placedWaterPos);
            if (!BlockPlacementUtils.matchesBlock(hit, placedWaterPos)) {
                beginReturn(client);
                return;
            }
        }
        silentUseAction = SilentUseAction.RECOVER_WATER;
        silentUseHit = hit;
        silentUseFluid = LegacyRay.Fluid.SOURCE_ONLY;
        silentUseTicks = 0;
        if (reuseRotation) {
            silentUsePhase = SilentUsePhase.READY_TO_USE;
            tickSilentUse(client);
        } else {
            turnForUse(client, VecMath.atCenterOf(placedWaterPos));
        }
    }

    private void beginReturn(Minecraft client) {
        restoreSlot(client);
        placedWaterPos = null;
        silentUsePhase = SilentUsePhase.RETURNING;
        silentUseTicks = 0;
        SilentPacketRotation.beginReturnToCamera(
                client,
                actionRotation == SilentPacketRotation.Mode.SMOOTH ? actionReturnTicks : 1,
                actionRotation,
                () -> {
                    if (silentUsePhase == SilentUsePhase.RETURNING) {
                        silentUsePhase = SilentUsePhase.WAITING_FOR_RETURN_PACKET;
                    }
                });
    }

    private void abortSilentUse(Minecraft client) {
        restoreSlot(client);
        placedWaterPos = null;
        SilentPacketRotation.reset();
        clearSilentUse();
    }

    private void clearSilentUse() {
        silentUsePhase = SilentUsePhase.IDLE;
        silentUseAction = null;
        silentUseHit = null;
        silentUseFluid = null;
        silentUseSlot = -1;
        silentUseRecovery = false;
        silentUseTicks = 0;
        silentUseTick = 0;
    }

    private BlockPos findBucketPos(Minecraft client) {
        BlockPos playerPos = client.thePlayer.getPosition();
        BlockPos closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos candidate = playerPos.add(dx, dy, dz);
                    if (!isWaterSource(client, candidate)) continue;
                    double distance =
                            VecMath.position(client.thePlayer)
                                    .squareDistanceTo(VecMath.atCenterOf(candidate));
                    if (distance >= closestDistance) continue;
                    Rotation rotation = rotationToBlock(client, candidate);
                    MovingObjectPosition hit =
                            RAYS.traceOutline(
                                    client,
                                    rotation,
                                    Minecraft.getMinecraft()
                                            .playerController
                                            .getBlockReachDistance(),
                                    LegacyRay.Fluid.SOURCE_ONLY,
                                    candidate);
                    if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS
                            || !hit.getBlockPos().equals(candidate)) continue;
                    closest = candidate;
                    closestDistance = distance;
                }
            }
        }
        return closest;
    }

    private static Rotation rotationToBlock(Minecraft client, BlockPos pos) {
        return MathUtils.rotationTo(
                client.thePlayer.getPositionEyes(1.0F), VecMath.atCenterOf(pos));
    }

    private static boolean isWaterSource(Minecraft client, BlockPos pos) {
        FluidState fluid = LegacyWorld.fluid(client.theWorld, pos);
        return FluidQueries.isSource(fluid, Material.water);
    }

    private void selectSlot(Minecraft client, int slot) {
        if (slotToRestore == null) slotToRestore = client.thePlayer.inventory.currentItem;
        client.thePlayer.inventory.currentItem = slot;
    }

    private void restoreSlot(Minecraft client) {
        if (slotToRestore == null) return;
        client.thePlayer.inventory.currentItem = slotToRestore;
        slotToRestore = null;
    }

    private enum SilentUsePhase {
        IDLE,
        TURNING,
        READY_TO_USE,
        WAITING_FOR_USE,
        RECOVERING,
        RETURNING,
        WAITING_FOR_RETURN_PACKET
    }

    private enum SilentUseAction {
        PLACE_WATER,
        RECOVER_WATER,
        FILL_BUCKET
    }
}
