package com.blanoir.moons.client.module.impl.player.automlg;

import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.prediction.LandingPrediction;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.BlockDistance;
import com.blanoir.moons.client.utils.world.FluidQueries;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** AutoMLG timing, placement and recovery state machine. */
public final class AutoMlgRuntime {
    private float accumulatedFall;
    private double lastY;
    private Integer slotToRestore;
    private boolean waterPlaced;
    private BlockPos placedWaterPos;
    private int postPlaceCooldown;
    private int postActionCooldown;
    private SilentUsePhase silentUsePhase = SilentUsePhase.IDLE;
    private SilentUseAction silentUseAction;
    private BlockHitResult silentUseHit;
    private ClipContext.Fluid silentUseFluid;
    private int silentUseSlot = -1;
    private boolean silentUseRecovery;
    private int silentUseTicks;
    private int silentUseTick;

    public void tick(
            Minecraft client,
            double triggerDistance,
            int predictTicks,
            boolean solidCheck,
            boolean recovery) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) return;
        if (currentPlayer.isFallFlying()) return;

        if (currentPlayer.onGround()
                || currentPlayer.getAbilities().flying
                || currentPlayer.isInWaterOrRain()
                || currentPlayer.isInLava()) {
            accumulatedFall = 0.0F;
        } else {
            double deltaY = currentPlayer.getY() - lastY;
            if (deltaY < 0.0D) accumulatedFall -= (float) deltaY;
        }
        lastY = currentPlayer.getY();

        if (postPlaceCooldown > 0) postPlaceCooldown--;
        if (postActionCooldown > 0) postActionCooldown--;
        if (currentPlayer.onGround() || accumulatedFall <= 0.0F) {
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
                && HotbarQueries.firstItem(client, Items.WATER_BUCKET) < 0
                && (emptyBucketSlot = HotbarQueries.firstItem(client, Items.BUCKET)) >= 0
                && (bucketPos = findBucketPos(client)) != null) {
            Rotation rotation = rotationToBlock(client, bucketPos);
            BlockHitResult hit =
                    BlockPlacementUtils.traceOutline(
                            client,
                            rotation,
                            currentPlayer.blockInteractionRange(),
                            ClipContext.Fluid.SOURCE_ONLY);
            if (hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(bucketPos)) {
                startSilentUse(
                        client,
                        SilentUseAction.FILL_BUCKET,
                        hit,
                        ClipContext.Fluid.SOURCE_ONLY,
                        emptyBucketSlot,
                        false);
                return;
            }
        }

        if (waterPlaced || accumulatedFall < triggerDistance) return;

        int waterSlot = HotbarQueries.firstItem(client, Items.WATER_BUCKET);
        if (waterSlot < 0 || ticksUntilGround(client) > predictTicks + 1) return;
        if (solidCheck && !hasSolidBelow(client, currentPlayer.blockPosition())) return;

        Rotation down = new Rotation(currentPlayer.getYRot(), 90.0F);
        BlockHitResult hit =
                BlockPlacementUtils.traceOutline(
                        client,
                        down,
                        currentPlayer.blockInteractionRange(),
                        ClipContext.Fluid.NONE);
        if (hit.getType() == HitResult.Type.MISS) return;
        placeWaterBucket(client, waterSlot, hit, recovery);
    }

    public void reset(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (slotToRestore != null && client != null && currentPlayer != null) {
            currentPlayer.getInventory().setSelectedSlot(slotToRestore);
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
        lastY = client != null && currentPlayer != null ? currentPlayer.getY() : 0.0D;
    }

    private int ticksUntilGround(Minecraft client) {
        if (client.player.getDeltaMovement().y >= 0.0D) return 999;
        int ticks =
                LandingPrediction.ticksUntilGround(
                        client.player.getDeltaMovement().y, BlockDistance.toGround(client, 30.0D));
        return ticks == Integer.MAX_VALUE ? 999 : ticks;
    }

    private void placeWaterBucket(
            Minecraft client, int slot, BlockHitResult hit, boolean recovery) {
        startSilentUse(
                client, SilentUseAction.PLACE_WATER, hit, ClipContext.Fluid.NONE, slot, recovery);
    }

    private void startSilentUse(
            Minecraft client,
            SilentUseAction action,
            BlockHitResult hit,
            ClipContext.Fluid fluid,
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
        silentUseTicks = 0;
        turnForUse(client, hit.getLocation());
    }

    private void turnForUse(Minecraft client, Vec3 target) {
        silentUsePhase = SilentUsePhase.TURNING;
        SilentPacketRotation.beginRotation(
                client,
                target,
                1,
                SilentPacketRotation.Mode.INSTANT,
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
                Rotation sent =
                        new Rotation(
                                SilentPacketRotation.getInteractionYaw(client),
                                SilentPacketRotation.getInteractionPitch(client));
                double range = client.player.blockInteractionRange();
                BlockHitResult currentHit =
                        BlockPlacementUtils.traceOutline(client, sent, range, silentUseFluid);
                if (currentHit.getType() == HitResult.Type.MISS
                        || silentUseHit == null
                        || !currentHit.getBlockPos().equals(silentUseHit.getBlockPos())
                        || (silentUseAction == SilentUseAction.PLACE_WATER
                                && currentHit.getDirection() != silentUseHit.getDirection())) {
                    abortSilentUse(client);
                    return;
                }
                var expectedItem =
                        silentUseAction == SilentUseAction.PLACE_WATER
                                ? Items.WATER_BUCKET
                                : Items.BUCKET;
                if (!client.player.getInventory().getItem(silentUseSlot).is(expectedItem)) {
                    abortSilentUse(client);
                    return;
                }
                silentUseHit = currentHit;
                selectSlot(client, silentUseSlot);
                // A bucket has its own fluid ray. A fluid BLOCK hit would also send
                // USE_ITEM_ON against the water, which is not a block interaction.
                BlockHitResult useHit =
                        silentUseAction == SilentUseAction.PLACE_WATER
                                ? currentHit
                                : BlockHitResult.miss(
                                        currentHit.getLocation(),
                                        currentHit.getDirection(),
                                        currentHit.getBlockPos());
                // Keep slot sync/use/swing before this tick's movement. The instant
                // angle stays pinned until that following movement is sent.
                if (!SilentPacketRotation.invokeUseInPlayerUpdate(client, useHit, false)) {
                    abortSilentUse(client);
                    return;
                }
                silentUseTick = client.player.tickCount;
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
            BlockPos adjacent = support.relative(silentUseHit.getDirection());
            boolean emptied = client.player.getInventory().getItem(silentUseSlot).is(Items.BUCKET);
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
        if (client.player.tickCount - silentUseTick < 1) return;
        if (placedWaterPos == null
                || !isWaterSource(client, placedWaterPos)
                || !client.player.getInventory().getItem(silentUseSlot).is(Items.BUCKET)) {
            beginReturn(client);
            return;
        }
        // Placement prediction can run before the fall reaches the source.
        // Leave the cushion in place until the player has actually reached it.
        if (!client.player.isInWater() && !client.player.onGround()) return;
        Rotation current =
                new Rotation(SilentPacketRotation.getYaw(), SilentPacketRotation.getPitch());
        BlockHitResult hit =
                BlockPlacementUtils.traceOutline(
                        client,
                        current,
                        client.player.blockInteractionRange(),
                        ClipContext.Fluid.SOURCE_ONLY);
        boolean reuseRotation = BlockPlacementUtils.matchesBlock(hit, placedWaterPos);
        if (!reuseRotation) {
            hit =
                    BlockPlacementUtils.traceOutline(
                            client,
                            rotationToBlock(client, placedWaterPos),
                            client.player.blockInteractionRange(),
                            ClipContext.Fluid.SOURCE_ONLY);
            if (!BlockPlacementUtils.matchesBlock(hit, placedWaterPos)) {
                beginReturn(client);
                return;
            }
        }
        silentUseAction = SilentUseAction.RECOVER_WATER;
        silentUseHit = hit;
        silentUseFluid = ClipContext.Fluid.SOURCE_ONLY;
        silentUseTicks = 0;
        if (reuseRotation) {
            silentUsePhase = SilentUsePhase.READY_TO_USE;
            tickSilentUse(client);
        } else {
            turnForUse(client, Vec3.atCenterOf(placedWaterPos));
        }
    }

    private void beginReturn(Minecraft client) {
        restoreSlot(client);
        placedWaterPos = null;
        silentUsePhase = SilentUsePhase.RETURNING;
        silentUseTicks = 0;
        SilentPacketRotation.beginReturnToCamera(
                client,
                1,
                SilentPacketRotation.Mode.SMOOTH,
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
        BlockPos playerPos = client.player.blockPosition();
        BlockPos closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos candidate = playerPos.offset(dx, dy, dz);
                    if (!isWaterSource(client, candidate)) continue;
                    double distance =
                            client.player.position().distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance >= closestDistance) continue;
                    Rotation rotation = rotationToBlock(client, candidate);
                    BlockHitResult hit =
                            BlockPlacementUtils.traceOutline(
                                    client,
                                    rotation,
                                    client.player.blockInteractionRange(),
                                    ClipContext.Fluid.SOURCE_ONLY);
                    if (hit.getType() == HitResult.Type.MISS
                            || !hit.getBlockPos().equals(candidate)) continue;
                    closest = candidate;
                    closestDistance = distance;
                }
            }
        }
        return closest;
    }

    private static Rotation rotationToBlock(Minecraft client, BlockPos pos) {
        return MathUtils.rotationTo(client.player.getEyePosition(), Vec3.atCenterOf(pos));
    }

    private static boolean isWaterSource(Minecraft client, BlockPos pos) {
        FluidState fluid = client.level.getFluidState(pos);
        return FluidQueries.isSource(fluid, Fluids.WATER);
    }

    private static boolean hasSolidBelow(Minecraft client, BlockPos pos) {
        return BlockPlacementUtils.solidWithoutMenu(client, pos.below())
                || BlockPlacementUtils.solidWithoutMenu(client, pos.below(2));
    }

    private void selectSlot(Minecraft client, int slot) {
        if (slotToRestore == null) slotToRestore = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(slot);
    }

    private void restoreSlot(Minecraft client) {
        if (slotToRestore == null) return;
        client.player.getInventory().setSelectedSlot(slotToRestore);
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
