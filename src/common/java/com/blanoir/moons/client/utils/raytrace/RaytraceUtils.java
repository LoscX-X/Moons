package com.blanoir.moons.client.utils.raytrace;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Optional;
import java.util.function.Predicate;

/** Shared block and entity ray queries used by combat modules. */
public final class RaytraceUtils {
    public enum EntityRayState {
        HIT,
        AIM,
        BLOCKED,
        RANGE
    }

    private RaytraceUtils() {}

    public static Optional<Vec3> intercept(AxisAlignedBB box, Vec3 start, Vec3 end) {
        MovingObjectPosition hit = box.calculateIntercept(start, end);
        return hit == null ? Optional.empty() : Optional.of(hit.hitVec);
    }

    public static Entity rootVehicle(Entity entity) {
        java.util.Set<Entity> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (entity.ridingEntity != null && seen.add(entity)) entity = entity.ridingEntity;
        return entity;
    }

    /** Direction-based entity query; filtering policy is supplied by the caller. */
    public static Entity findEntityOnRay(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double range,
            Predicate<Entity> predicate,
            boolean throughBlocks) {
        if (client == null
                || client.thePlayer == null
                || client.theWorld == null
                || start == null
                || look == null
                || VecMath.lengthSqr(look) <= 1.0E-9D
                || !Double.isFinite(range)
                || range <= 0.0D) {
            return null;
        }
        Vec3 end = start.add(VecMath.scale(look.normalize(), range));
        MovingObjectPosition hit = findEntity(client, start, end, range, throughBlocks, predicate);
        return hit == null ? null : hit.entityHit;
    }

    public static Entity findEntityOnViewRay(
            Minecraft client, Predicate<Entity> predicate, boolean throughBlocks) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || client.theWorld == null) {
            return null;
        }
        double range = CombatReach.vanillaEntityInteractionRange(currentPlayer);
        Vec3 start = currentPlayer.getPositionEyes(1F);
        return findEntityOnRay(
                client, start, currentPlayer.getLook(1.0F), range, predicate, throughBlocks);
    }

    public static boolean canRayTraceTo(Minecraft client, Vec3 eyePos, Vec3 point) {
        return canRayTraceTo(client, eyePos, point, false);
    }

    public static boolean canRayTraceTo(
            Minecraft client, Vec3 eyePos, Vec3 point, boolean throughBlocks) {
        if (client == null
                || client.theWorld == null
                || client.thePlayer == null
                || eyePos == null
                || point == null) {
            return false;
        }
        return throughBlocks
                || (clipBlocks(client, eyePos, point).typeOfHit
                                == MovingObjectPosition.MovingObjectType.MISS
                        && firstCobwebHit(client, eyePos, point).isEmpty());
    }

    public static MovingObjectPosition clipBlocks(Minecraft client, Vec3 start, Vec3 end) {
        var currentPlayer = client == null ? null : client.thePlayer;
        var currentLevel = client == null ? null : client.theWorld;
        if (client == null
                || currentLevel == null
                || currentPlayer == null
                || start == null
                || end == null) {
            Vec3 point = start == null ? VecMath.ZERO : start;
            return new MovingObjectPosition(
                    MovingObjectPosition.MovingObjectType.MISS,
                    point,
                    EnumFacing.UP,
                    new BlockPos(point));
        }
        MovingObjectPosition hit = currentLevel.rayTraceBlocks(start, end, false, true, false);
        return hit == null
                ? new MovingObjectPosition(
                        MovingObjectPosition.MovingObjectType.MISS,
                        end,
                        EnumFacing.UP,
                        new BlockPos(end))
                : hit;
    }

    public static MovingObjectPosition findEntity(
            Minecraft client,
            Vec3 start,
            Vec3 end,
            double maxDistance,
            boolean throughBlocks,
            Predicate<Entity> predicate) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null
                || client.theWorld == null
                || currentPlayer == null
                || start == null
                || end == null
                || predicate == null
                || !Double.isFinite(maxDistance)
                || maxDistance <= 0.0D) {
            return null;
        }

        Vec3 rayEnd = end;
        double rayDistance = maxDistance;
        if (!throughBlocks) {
            MovingObjectPosition blockHit = clipBlocks(client, start, end);
            if (blockHit.typeOfHit != MovingObjectPosition.MovingObjectType.MISS) {
                rayEnd = blockHit.hitVec;
                rayDistance = Math.min(rayDistance, start.distanceTo(rayEnd));
            }
            Optional<Vec3> cobwebHit = firstCobwebHit(client, start, rayEnd);
            if (cobwebHit.isPresent()) {
                rayEnd = cobwebHit.get();
                rayDistance = Math.min(rayDistance, start.distanceTo(rayEnd));
            }
        }

        AxisAlignedBB searchBox =
                new AxisAlignedBB(
                                Math.min(start.xCoord, rayEnd.xCoord),
                                Math.min(start.yCoord, rayEnd.yCoord),
                                Math.min(start.zCoord, rayEnd.zCoord),
                                Math.max(start.xCoord, rayEnd.xCoord),
                                Math.max(start.yCoord, rayEnd.yCoord),
                                Math.max(start.zCoord, rayEnd.zCoord))
                        .expand(1, 1, 1);
        Entity nearest = null;
        Vec3 contact = null;
        double distance = rayDistance * rayDistance;
        for (Entity entity :
                client.theWorld.getEntitiesInAABBexcluding(
                        currentPlayer, searchBox, e -> predicate.test(e))) {
            if (!entity.canBeCollidedWith() || rootVehicle(entity) == rootVehicle(currentPlayer))
                continue;
            double border = entity.getCollisionBorderSize();
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            Vec3 point =
                    box.isVecInside(start) ? start : intercept(box, start, rayEnd).orElse(null);
            if (point != null && start.squareDistanceTo(point) <= distance) {
                nearest = entity;
                contact = point;
                distance = start.squareDistanceTo(point);
            }
        }
        return nearest == null ? null : new MovingObjectPosition(nearest, contact);
    }

    public static EntityRayState traceEntity(
            Minecraft client, Vec3 start, Vec3 look, double reach, Entity target) {
        return traceEntity(client, start, look, reach, target, false);
    }

    public static EntityRayState traceEntity(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double reach,
            Entity target,
            boolean throughBlocks) {
        if (client == null
                || client.thePlayer == null
                || client.theWorld == null
                || start == null
                || look == null
                || target == null
                || !Double.isFinite(reach)
                || reach <= 0.0D
                || EntityDistance.squaredToBox(start, target.getEntityBoundingBox())
                        > reach * reach) {
            return EntityRayState.RANGE;
        }

        Vec3 end = start.add(VecMath.scale(look, reach));
        AxisAlignedBB box = target.getEntityBoundingBox();
        Optional<Vec3> entityHit = intercept(box, start, end);
        if (!box.isVecInside(start) && entityHit.isEmpty()) {
            return EntityRayState.AIM;
        }

        // Ignoring cover never bypasses the range and ray/hitbox intersection checks above.
        if (throughBlocks) return EntityRayState.HIT;

        Optional<Vec3> cobwebHit = firstCobwebHit(client, start, end);
        if (cobwebHit.isPresent()
                && entityHit.isPresent()
                && start.squareDistanceTo(cobwebHit.get()) + 1.0E-7D
                        < start.squareDistanceTo(entityHit.get())) {
            return EntityRayState.BLOCKED;
        }

        MovingObjectPosition blockHit = clipBlocks(client, start, end);
        if (blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
            return EntityRayState.HIT;
        }
        boolean unobstructed =
                box.isVecInside(start)
                        || entityHit.isPresent()
                                && start.squareDistanceTo(blockHit.hitVec)
                                        >= start.squareDistanceTo(entityHit.get());
        return unobstructed ? EntityRayState.HIT : EntityRayState.BLOCKED;
    }

    public static double distanceToBlock(
            Minecraft client, Vec3 start, Vec3 end, double missDistance) {
        MovingObjectPosition hit = clipBlocks(client, start, end);
        double distance =
                hit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS
                        ? missDistance
                        : start.distanceTo(hit.hitVec);
        Optional<Vec3> cobwebHit = firstCobwebHit(client, start, end);
        return cobwebHit.isEmpty()
                ? distance
                : Math.min(distance, start.distanceTo(cobwebHit.get()));
    }

    /**
     * Cobweb has no collision shape for the normal COLLIDER ray, but combat
     * line-of-sight must still treat its occupied cell as cover. Skip only a
     * web containing the attacker's eyes so being caught in one does not make
     * every outward attack impossible.
     */
    private static Optional<Vec3> firstCobwebHit(Minecraft client, Vec3 start, Vec3 end) {
        var currentLevel = client == null ? null : client.theWorld;
        if (client == null || currentLevel == null || start == null || end == null) {
            return Optional.empty();
        }
        int minX = Mth.floor(Math.min(start.xCoord, end.xCoord));
        int minY = Mth.floor(Math.min(start.yCoord, end.yCoord));
        int minZ = Mth.floor(Math.min(start.zCoord, end.zCoord));
        int maxX = Mth.floor(Math.max(start.xCoord, end.xCoord));
        int maxY = Mth.floor(Math.max(start.yCoord, end.yCoord));
        int maxZ = Mth.floor(Math.max(start.zCoord, end.zCoord));
        Vec3 closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (currentLevel.getBlockState(pos).getBlock() != Blocks.web) continue;
                    AxisAlignedBB web = new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
                    if (web.isVecInside(start)) continue;
                    Optional<Vec3> hit = intercept(web, start, end);
                    if (hit.isEmpty()) continue;
                    double distance = start.squareDistanceTo(hit.get());
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closest = hit.get();
                    }
                }
            }
        }
        return Optional.ofNullable(closest);
    }
}
