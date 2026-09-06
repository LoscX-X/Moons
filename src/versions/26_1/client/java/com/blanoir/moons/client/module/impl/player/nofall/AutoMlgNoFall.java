package com.blanoir.moons.client.module.impl.player.nofall;

import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/** OpenZen AutoMLG timing, placement and recovery state machine. */
public final class AutoMlgNoFall {
    private float accumulatedFall;
    private double lastY;
    private Integer slotToRestore;
    private boolean waterPlaced;
    private boolean recoveryActive;
    private int recoveryDelay;
    private int recoveryCountdown;
    private Integer waterBucketSlot;
    private BlockPos placedWaterPos;
    private boolean readyToPlace;
    private int postPlaceCooldown;
    private int postActionCooldown;
    private int extraCooldown;
    private SilentUsePhase silentUsePhase = SilentUsePhase.IDLE;
    private SilentUseAction silentUseAction;
    private BlockHitResult silentUseHit;
    private ClipContext.Fluid silentUseFluid;
    private int silentUseSlot = -1;
    private boolean silentUseRecovery;
    private int silentUseTicks;

    public void tick(Minecraft client, double triggerDistance, int predictTicks,
                     boolean solidCheck, boolean recovery) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) return;
        if (currentPlayer.isFallFlying()) return;

        if (currentPlayer.onGround() || currentPlayer.getAbilities().flying
                || currentPlayer.isInWaterOrRain() || currentPlayer.isInLava()) {
            accumulatedFall = 0.0F;
        } else {
            double deltaY = currentPlayer.getY() - lastY;
            if (deltaY < 0.0D) accumulatedFall -= (float) deltaY;
        }
        lastY = currentPlayer.getY();

        if (postPlaceCooldown > 0) postPlaceCooldown--;
        if (postActionCooldown > 0) postActionCooldown--;
        if (extraCooldown > 0) extraCooldown--;

        if (slotToRestore != null) {
            currentPlayer.getInventory().setSelectedSlot(slotToRestore);
            slotToRestore = null;
        }
        if (currentPlayer.onGround() || accumulatedFall <= 0.0F) {
            waterPlaced = false;
            readyToPlace = false;
        }

        if (silentUsePhase != SilentUsePhase.IDLE) {
            tickSilentUse(client);
            return;
        }

        if (recoveryActive) {
            if (recoveryDelay > 0) {
                recoveryDelay--;
                return;
            }
            if (recoveryCountdown-- <= 0) {
                recoveryActive = false;
                return;
            }
            if (waterBucketSlot == null) {
                int slot = findHotbarSlot(client, Items.BUCKET);
                if (slot < 0) {
                    recoveryActive = false;
                    return;
                }
                waterBucketSlot = slot;
            }
            if (currentPlayer.getInventory().getItem(waterBucketSlot).is(Items.WATER_BUCKET)) {
                recoveryActive = false;
                waterBucketSlot = null;
                placedWaterPos = null;
                postPlaceCooldown = Math.max(postPlaceCooldown, 1);
                return;
            }
            if (placedWaterPos == null || !isWaterSource(client, placedWaterPos)) {
                recoveryActive = false;
                waterBucketSlot = null;
                placedWaterPos = null;
                return;
            }
            Rotation rotation = rotationToBlock(client, placedWaterPos);
            BlockHitResult hit = raycast(client, rotation, 4.5D, ClipContext.Fluid.SOURCE_ONLY);
            if (hit.getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(placedWaterPos)) {
                recoveryActive = false;
                waterBucketSlot = null;
                placedWaterPos = null;
                return;
            }
            startSilentUse(client, SilentUseAction.RECOVER_WATER, hit,
                    ClipContext.Fluid.SOURCE_ONLY, waterBucketSlot, false);
            return;
        }

        int emptyBucketSlot;
        BlockPos bucketPos;
        if (!waterPlaced && placedWaterPos == null && postPlaceCooldown == 0
                && postActionCooldown == 0 && accumulatedFall <= 0.5F
                && findHotbarSlot(client, Items.WATER_BUCKET) < 0
                && (emptyBucketSlot = findHotbarSlot(client, Items.BUCKET)) >= 0
                && (bucketPos = findBucketPos(client)) != null) {
            Rotation rotation = rotationToBlock(client, bucketPos);
            BlockHitResult hit = raycast(client, rotation, 4.5D, ClipContext.Fluid.SOURCE_ONLY);
            if (hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(bucketPos)) {
                startSilentUse(client, SilentUseAction.FILL_BUCKET, hit,
                        ClipContext.Fluid.SOURCE_ONLY, emptyBucketSlot, false);
                return;
            }
        }

        if (waterPlaced && !readyToPlace && currentPlayer.getDeltaMovement().y < 0.0D) {
            double distance = distanceToGround(client, 2.5D);
            if (distance > 0.0D && distance <= 1.05D) readyToPlace = true;
        }
        if (waterPlaced || accumulatedFall < triggerDistance) return;

        int waterSlot = findHotbarSlot(client, Items.WATER_BUCKET);
        if (waterSlot < 0 || ticksUntilGround(client) > predictTicks + 1) return;
        if (solidCheck && !hasSolidBelow(client, currentPlayer.blockPosition())) return;

        Rotation down = new Rotation(currentPlayer.getYRot(), 90.0F);
        BlockHitResult hit = raycast(client, down, 5.0D, ClipContext.Fluid.NONE);
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
        recoveryActive = false;
        recoveryDelay = 0;
        recoveryCountdown = 0;
        waterBucketSlot = null;
        placedWaterPos = null;
        readyToPlace = false;
        postPlaceCooldown = 0;
        postActionCooldown = 0;
        extraCooldown = 0;
        accumulatedFall = 0.0F;
        lastY = client != null && currentPlayer != null ? currentPlayer.getY() : 0.0D;
    }

    private int ticksUntilGround(Minecraft client) {
        if (client.player.getDeltaMovement().y >= 0.0D) return 999;
        double distance = distanceToGround(client, 30.0D);
        if (distance == Double.POSITIVE_INFINITY) return 999;
        double simulatedDrop = 0.0D;
        double simulatedVelocity = client.player.getDeltaMovement().y;
        for (int tick = 1; tick <= 20; tick++) {
            simulatedDrop += simulatedVelocity;
            simulatedVelocity = (simulatedVelocity - 0.08D) * 0.98D;
            if (Math.abs(simulatedDrop) >= distance) return tick;
        }
        return 999;
    }

    private void placeWaterBucket(
            Minecraft client,
            int slot,
            BlockHitResult hit,
            boolean recovery
    ) {
        startSilentUse(client, SilentUseAction.PLACE_WATER, hit,
                ClipContext.Fluid.NONE, slot, recovery);
    }

    private void startSilentUse(
            Minecraft client,
            SilentUseAction action,
            BlockHitResult hit,
            ClipContext.Fluid fluid,
            int slot,
            boolean recovery
    ) {
        if (silentUsePhase != SilentUsePhase.IDLE
                || SilentPacketRotation.shouldApplyRotation()) {
            return;
        }
        silentUseAction = action;
        silentUseHit = hit;
        silentUseFluid = fluid;
        silentUseSlot = slot;
        silentUseRecovery = recovery;
        silentUseTicks = 0;
        silentUsePhase = SilentUsePhase.TURNING;
        SilentPacketRotation.beginRotation(client, hit.getLocation(), 1,
                SilentPacketRotation.Mode.INSTANT, () -> {
            if (silentUsePhase == SilentUsePhase.TURNING) {
                silentUsePhase = SilentUsePhase.WAITING_FOR_ROTATION_PACKET;
            }
        });
        if (silentUsePhase == SilentUsePhase.WAITING_FOR_ROTATION_PACKET) {
            tickSilentUse(client);
        }
    }

    private void tickSilentUse(Minecraft client) {
        if (++silentUseTicks > 12) {
            abortSilentUse();
            return;
        }
        switch (silentUsePhase) {
            case TURNING, RETURNING -> {
                return;
            }
            case WAITING_FOR_ROTATION_PACKET -> {
                Rotation sent = new Rotation(
                        SilentPacketRotation.getInteractionYaw(client),
                        SilentPacketRotation.getInteractionPitch(client));
                double range = silentUseAction == SilentUseAction.PLACE_WATER ? 5.0D : 4.5D;
                BlockHitResult currentHit = raycast(client, sent, range, silentUseFluid);
                if (currentHit.getType() == HitResult.Type.MISS
                        || silentUseHit == null
                        || !currentHit.getBlockPos().equals(silentUseHit.getBlockPos())) {
                    abortSilentUse();
                    return;
                }
                silentUseHit = currentHit;
                selectSlot(client, silentUseSlot);
                if (!SilentPacketRotation.queueUse(client, currentHit)) {
                    abortSilentUse();
                    return;
                }
                silentUsePhase = SilentUsePhase.WAITING_FOR_USE;
            }
            case WAITING_FOR_USE -> {
                if (!SilentPacketRotation.isUseDone()) {
                    return;
                }
                completeSilentUse();
                silentUsePhase = SilentUsePhase.RETURNING;
                SilentPacketRotation.beginReturnToCamera(client, 1,
                        SilentPacketRotation.Mode.SMOOTH, () -> {
                    if (silentUsePhase == SilentUsePhase.RETURNING) {
                        silentUsePhase = SilentUsePhase.WAITING_FOR_RETURN_PACKET;
                    }
                });
            }
            case WAITING_FOR_RETURN_PACKET -> {
                if (!SilentPacketRotation.isRotationPacketSent()) {
                    return;
                }
                SilentPacketRotation.reset();
                clearSilentUse();
            }
            case IDLE -> {
            }
        }
    }

    private void completeSilentUse() {
        if (silentUseAction == SilentUseAction.PLACE_WATER) {
            waterPlaced = true;
            recoveryActive = silentUseRecovery;
            recoveryDelay = 3;
            recoveryCountdown = silentUseRecovery ? 2 : 0;
            waterBucketSlot = null;
            placedWaterPos = silentUseHit == null ? null
                    : silentUseHit.getBlockPos().relative(silentUseHit.getDirection());
        } else if (silentUseAction == SilentUseAction.FILL_BUCKET) {
            postActionCooldown = 8;
            postPlaceCooldown = Math.max(postPlaceCooldown, 1);
        }
    }

    private void abortSilentUse() {
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
                    double distance = client.player.position().distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance >= closestDistance) continue;
                    Rotation rotation = rotationToBlock(client, candidate);
                    BlockHitResult hit = raycast(client, rotation, 4.5D, ClipContext.Fluid.SOURCE_ONLY);
                    if (hit.getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(candidate)) continue;
                    closest = candidate;
                    closestDistance = distance;
                }
            }
        }
        return closest;
    }

    private static Rotation rotationToBlock(Minecraft client, BlockPos pos) {
        Vec3 eye = client.player.getEyePosition();
        double dx = addAimNoise(pos.getX() + 0.5D - eye.x);
        double dy = addAimNoise(pos.getY() + 0.5D - eye.y);
        double dz = addAimNoise(pos.getZ() + 0.5D - eye.z);
        return MathUtils.rotationTo(eye, eye.add(dx, dy, dz));
    }

    private static double addAimNoise(double value) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return value + random.nextDouble(0.05D, 0.08D)
                * (random.nextDouble() * 2.0D - 1.0D);
    }

    private static BlockHitResult raycast(Minecraft client, Rotation rotation, double range,
                                          ClipContext.Fluid fluid) {
        Vec3 eye = client.player.getEyePosition(1.0F);
        Vec3 direction = Vec3.directionFromRotation(rotation.pitch(), rotation.yaw());
        return client.level.clip(new ClipContext(
                eye, eye.add(direction.scale(range)), ClipContext.Block.OUTLINE, fluid, client.player));
    }

    private static boolean isWaterSource(Minecraft client, BlockPos pos) {
        FluidState fluid = client.level.getFluidState(pos);
        return fluid.getType() == Fluids.WATER && fluid.isSource();
    }

    private static boolean hasSolidBelow(Minecraft client, BlockPos pos) {
        return isSolidNonMenu(client, pos.below()) || isSolidNonMenu(client, pos.below(2));
    }

    private static boolean isSolidNonMenu(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return !state.getCollisionShape(client.level, pos).isEmpty()
                && state.getMenuProvider(client.level, pos) == null;
    }

    private static double distanceToGround(Minecraft client, double maxDistance) {
        Vec3 start = new Vec3(client.player.getX(), client.player.getBoundingBox().minY,
                client.player.getZ());
        BlockHitResult hit = client.level.clip(new ClipContext(
                start, start.add(0.0D, -maxDistance, 0.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS
                ? Double.POSITIVE_INFINITY : start.y - hit.getLocation().y;
    }

    private static int findHotbarSlot(Minecraft client, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private void selectSlot(Minecraft client, int slot) {
        slotToRestore = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(slot);
    }

    private enum SilentUsePhase {
        IDLE,
        TURNING,
        WAITING_FOR_ROTATION_PACKET,
        WAITING_FOR_USE,
        RETURNING,
        WAITING_FOR_RETURN_PACKET
    }

    private enum SilentUseAction {
        PLACE_WATER,
        RECOVER_WATER,
        FILL_BUCKET
    }
}
