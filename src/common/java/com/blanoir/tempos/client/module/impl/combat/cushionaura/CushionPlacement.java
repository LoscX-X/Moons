package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import com.blanoir.tempos.client.access.CushionAccess;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded target-centered search and native placement/interaction geometry. */
final class CushionPlacement {
    record Plan(BlockHitResult hit, Vec3 position, int slot, int entityId) {
        Plan(BlockHitResult hit, Vec3 position, int slot) {
            this(hit, position, slot, -1);
        }

        static Plan existing(Entity seat) {
            return new Plan(null, seat.position(), -1, seat.getId());
        }

        boolean existing() {
            return entityId >= 0;
        }
    }

    private CushionPlacement() {}

    /** The fallback is lazy: existing seats need neither an inventory slot nor a placement. */
    static Plan preferExisting(Supplier<Plan> existing, Supplier<Plan> placement) {
        Plan seat = existing.get();
        return seat != null ? seat : placement.get();
    }

    static Plan findExisting(Minecraft client, LivingEntity target, double radius, Vec3 lastSeat) {
        Plan best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Entity seat :
                client.level.getEntities(
                        client.player,
                        target.getBoundingBox().inflate(radius, 3, radius),
                        CushionAccess::isCushion)) {
            if (!reusable(client, seat, target, radius, lastSeat)) continue;
            double score =
                    score(
                            client,
                            target,
                            seat.position(),
                            seatedEye(client, seat),
                            radius,
                            lastSeat);
            if (score < bestScore
                    || score == bestScore && best != null && seat.getId() < best.entityId()) {
                best = Plan.existing(seat);
                bestScore = score;
            }
        }
        return best;
    }

    static boolean reusable(
            Minecraft client, Entity seat, LivingEntity target, double radius, Vec3 previous) {
        return seatUsable(client, seat)
                && aroundTarget(seat.position(), target.position(), radius, previous)
                && safeSpace(client, seat.position(), seat.getBoundingBox())
                && attackSamples(client, target, seatedEye(client, seat)) > 0;
    }

    private static Vec3 seatedEye(Minecraft client, Entity seat) {
        return seat.getPassengerRidingPosition(client.player)
                .subtract(client.player.getVehicleAttachmentPoint(seat))
                .add(0, client.player.getEyeHeight(), 0);
    }

    static int findSlot(Minecraft client) {
        for (int slot = 0; slot < 9; slot++) {
            if (CushionAccess.isItem(client.player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    static Plan find(
            Minecraft client, LivingEntity target, int slot, double radius, Vec3 lastSeat) {
        ItemStack stack = client.player.getInventory().getItem(slot);
        BlockPos center = target.blockPosition();
        Plan best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int extent = Mth.ceil(radius);
        for (int x = -extent; x <= extent; x++)
            for (int z = -extent; z <= extent; z++) {
                for (int y = -2; y <= 1; y++) {
                    BlockPos support = center.offset(x, y, z);
                    if (!client.level.hasChunkAt(support)
                            || !BlockPlacementUtils.solidWithoutMenu(client, support)) continue;
                    Vec3 point =
                            BlockPlacementUtils.facePoint(client, support, Direction.UP, .5, .5);
                    if (!aroundTarget(point, target.position(), radius, lastSeat)) continue;
                    BlockHitResult hit =
                            BlockPlacementUtils.visibleFaceHit(
                                    client,
                                    client.player.getEyePosition(),
                                    support,
                                    Direction.UP,
                                    point,
                                    1.0E-4);
                    if (hit == null || !validPlan(client, hit, stack)) continue;
                    Vec3 position = CushionAccess.placementPosition(client, stack, hit);
                    double score = newSeatScore(client, target, position, radius, lastSeat);
                    if (score < bestScore) {
                        best = new Plan(hit, position, slot);
                        bestScore = score;
                    }
                }
            }
        return best;
    }

    static double newSeatScore(
            Minecraft client, LivingEntity target, Vec3 position, double radius, Vec3 previous) {
        Vec3 eye =
                position.add(CushionAccess.passengerOffset())
                        .subtract(
                                client.player
                                        .getAttachments()
                                        .get(EntityAttachment.VEHICLE, 0, client.player.getYRot()))
                        .add(0, client.player.getEyeHeight(), 0);
        return score(client, target, position, eye, radius, previous);
    }

    private static double score(
            Minecraft client,
            LivingEntity target,
            Vec3 position,
            Vec3 eye,
            double radius,
            Vec3 previous) {
        int samples = attackSamples(client, target, eye);
        if (samples == 0) return Double.POSITIVE_INFINITY;
        double reach =
                Math.min(client.player.entityInteractionRange(), SilentAuraConfig.aimRange());
        return positionScore(
                position,
                client.player.position(),
                target.position(),
                previous,
                CushionSettings.TARGET_DISTANCE.get(),
                radius,
                Math.sqrt(target.getBoundingBox().distanceToSqr(eye)),
                reach,
                samples);
    }

    /** Prefer reach margin and clear attack angles; radius is a bound, not an outer-ring target. */
    static double positionScore(
            Vec3 seat,
            Vec3 player,
            Vec3 target,
            Vec3 previous,
            double minimumRadius,
            double maximumRadius,
            double attackDistance,
            double reach,
            int clearSamples) {
        if (clearSamples <= 0 || attackDistance > reach) return Double.POSITIVE_INFINITY;
        double preferredRadius =
                Math.min((minimumRadius + maximumRadius) * .5, Math.max(minimumRadius, reach - .5));
        double radiusError = Math.sqrt(horizontalDistanceSquared(seat, target)) - preferredRadius;
        double margin = Math.max(0, .4 - (reach - attackDistance));
        double sameSide = 0;
        if (previous != null) {
            double ax = seat.x - target.x, az = seat.z - target.z;
            double bx = previous.x - target.x, bz = previous.z - target.z;
            double length = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
            if (length > 1.0E-6) sameSide = Math.max(0, (ax * bx + az * bz) / length);
        }
        return radiusError * radiusError * 3
                + horizontalDistanceSquared(seat, player) * .2
                + Math.pow(seat.y - target.y, 2) * 2
                + margin * margin * 12
                + (4 - clearSamples) * .5
                + sameSide;
    }

    private static int attackSamples(Minecraft client, LivingEntity target, Vec3 eye) {
        double reach =
                Math.min(client.player.entityInteractionRange(), SilentAuraConfig.aimRange());
        return attackSamples(
                eye,
                target.getBoundingBox(),
                reach,
                point -> SilentAuraConfig.throughBlocks() || visible(client, eye, point));
    }

    static int attackSamples(Vec3 eye, AABB box, double reach, Predicate<Vec3> visible) {
        if (reach <= 0 || box.distanceToSqr(eye) > reach * reach) return 0;
        Vec3 center = box.getCenter();
        Vec3 near =
                new Vec3(
                        Math.clamp(
                                eye.x,
                                box.minX + box.getXsize() * .1,
                                box.maxX - box.getXsize() * .1),
                        Math.clamp(
                                eye.y,
                                box.minY + box.getYsize() * .1,
                                box.maxY - box.getYsize() * .1),
                        Math.clamp(
                                eye.z,
                                box.minZ + box.getZsize() * .1,
                                box.maxZ - box.getZsize() * .1));
        int clear = 0;
        for (Vec3 point :
                new Vec3[] {
                    near,
                    center,
                    center.add(0, box.getYsize() * .3, 0),
                    center.add(0, -box.getYsize() * .3, 0)
                }) {
            if (eye.distanceToSqr(point) <= reach * reach && visible.test(point)) clear++;
        }
        return clear;
    }

    private static double horizontalDistanceSquared(Vec3 first, Vec3 second) {
        double dx = first.x - second.x, dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    static boolean aroundTarget(Vec3 seat, Vec3 target, double radius, Vec3 previous) {
        double distance = horizontalDistanceSquared(seat, target);
        return distance >= Math.pow(CushionSettings.TARGET_DISTANCE.get(), 2)
                && distance <= radius * radius
                && (previous == null
                        || horizontalDistanceSquared(previous, seat)
                                >= Math.pow(CushionSettings.SEAT_DISTANCE.get(), 2));
    }

    static boolean validPlan(Minecraft client, BlockHitResult hit, ItemStack stack) {
        if (!BlockPlacementUtils.withinReach(
                        client.player.getEyePosition(),
                        hit.getLocation(),
                        client.player.blockInteractionRange())
                || !client.level.getWorldBorder().isWithinBounds(hit.getBlockPos())
                || pickEntity(client, client.player.getEyePosition(), hit.getLocation()) != null
                || !CushionAccess.canPlace(client, stack, hit)) return false;
        Vec3 position = CushionAccess.placementPosition(client, stack, hit);
        AABB cushion = CushionAccess.boxAt(position);
        if (!BlockPlacementUtils.withinReach(
                        client.player.getEyePosition(),
                        cushion.getCenter(),
                        client.player.entityInteractionRange())
                || !visible(client, cushion.getCenter())) return false;
        return safeSpace(client, position, cushion);
    }

    private static boolean safeSpace(Minecraft client, Vec3 position, AABB cushion) {
        if (!client.level.getWorldBorder().isWithinBounds(BlockPos.containing(position)))
            return false;
        AABB space =
                new AABB(
                        position.x - .3,
                        cushion.maxY,
                        position.z - .3,
                        position.x + .3,
                        cushion.maxY + client.player.getBbHeight(),
                        position.z + .3);
        if (!client.level.noCollision(client.player, space)) return false;
        for (BlockPos block : BlockPos.betweenClosed(space)) {
            var state = client.level.getBlockState(block);
            if (!state.getFluidState().isEmpty()
                    || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS)
                    || state.is(Blocks.SWEET_BERRY_BUSH)) return false;
        }
        var support = client.level.getBlockState(BlockPos.containing(position.add(0, -.001, 0)));
        return !support.is(Blocks.MAGMA_BLOCK)
                && !support.is(Blocks.CAMPFIRE)
                && !support.is(Blocks.SOUL_CAMPFIRE);
    }

    static Entity findSeat(Minecraft client, Vec3 position) {
        return client
                .level
                .getEntities(
                        client.player,
                        new AABB(position, position).inflate(.6),
                        CushionAccess::isCushion)
                .stream()
                .filter(seat -> seat.position().distanceToSqr(position) < .01 && !seat.isVehicle())
                .min(Comparator.comparingInt(Entity::getId))
                .orElse(null);
    }

    static boolean seatUsable(Minecraft client, Entity seat) {
        if (!CushionAccess.isCushion(seat) || seat.isRemoved() || seat.isVehicle()) return false;
        Vec3 point = seat.getBoundingBox().getCenter();
        return BlockPlacementUtils.withinReach(
                        client.player.getEyePosition(),
                        point,
                        client.player.entityInteractionRange())
                && visible(client, point)
                && unobstructedSeat(client, seat, point);
    }

    private static boolean unobstructedSeat(Minecraft client, Entity seat, Vec3 point) {
        EntityHitResult hit = pickEntity(client, client.player.getEyePosition(), point);
        return hit != null && hit.getEntity() == seat;
    }

    static boolean visible(Minecraft client, Vec3 point) {
        return visible(client, client.player.getEyePosition(), point);
    }

    private static boolean visible(Minecraft client, Vec3 eye, Vec3 point) {
        return client.level
                        .clip(
                                new ClipContext(
                                        eye,
                                        point,
                                        ClipContext.Block.OUTLINE,
                                        ClipContext.Fluid.NONE,
                                        client.player))
                        .getType()
                == HitResult.Type.MISS;
    }

    static EntityHitResult pickEntity(Minecraft client, Vec3 eye, Vec3 end) {
        return ProjectileUtil.getEntityHitResult(
                client.player,
                eye,
                end,
                new AABB(eye, end).inflate(1),
                EntitySelector.CAN_BE_PICKED,
                eye.distanceToSqr(end));
    }
}
