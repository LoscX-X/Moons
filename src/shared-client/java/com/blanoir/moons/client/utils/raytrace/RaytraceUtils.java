package com.blanoir.moons.client.utils.raytrace;

import com.blanoir.moons.client.utils.entity.EntityDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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

    private RaytraceUtils() {
    }

    public static boolean canRayTraceTo(
            Minecraft client,
            Vec3 eyePos,
            Vec3 point
    ) {
        if (client == null || client.level == null || client.player == null
                || eyePos == null || point == null) {
            return false;
        }
        return clipBlocks(client, eyePos, point).getType() == HitResult.Type.MISS
                && firstCobwebHit(client, eyePos, point).isEmpty();
    }

    public static HitResult clipBlocks(Minecraft client, Vec3 start, Vec3 end) {
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (client == null || currentLevel == null || currentPlayer == null
                || start == null || end == null) {
            return BlockHitResult.miss(
                    start == null ? Vec3.ZERO : start,
                    Direction.UP,
                    BlockPos.ZERO);
        }
        return currentLevel.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                currentPlayer));
    }

    public static EntityHitResult findEntity(
            Minecraft client,
            Vec3 start,
            Vec3 end,
            double maxDistance,
            boolean throughBlocks,
            Predicate<Entity> predicate
    ) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || client.level == null || currentPlayer == null
                || start == null || end == null || predicate == null
                || !Double.isFinite(maxDistance) || maxDistance <= 0.0D) {
            return null;
        }

        Vec3 rayEnd = end;
        double rayDistance = maxDistance;
        if (!throughBlocks) {
            HitResult blockHit = clipBlocks(client, start, end);
            if (blockHit.getType() != HitResult.Type.MISS) {
                rayEnd = blockHit.getLocation();
                rayDistance = Math.min(rayDistance, start.distanceTo(rayEnd));
            }
            Optional<Vec3> cobwebHit = firstCobwebHit(client, start, rayEnd);
            if (cobwebHit.isPresent()) {
                rayEnd = cobwebHit.get();
                rayDistance = Math.min(rayDistance, start.distanceTo(rayEnd));
            }
        }

        AABB searchBox = new AABB(start, start)
                .expandTowards(rayEnd.subtract(start))
                .inflate(1.0D);
        return ProjectileUtil.getEntityHitResult(
                currentPlayer,
                start,
                rayEnd,
                searchBox,
                predicate,
                rayDistance * rayDistance);
    }

    public static EntityRayState traceEntity(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double reach,
            Entity target
    ) {
        if (client == null || client.player == null || client.level == null
                || start == null || look == null || target == null
                || !Double.isFinite(reach) || reach <= 0.0D
                || EntityDistance.squaredToBox(start, target.getBoundingBox()) > reach * reach) {
            return EntityRayState.RANGE;
        }

        Vec3 end = start.add(look.scale(reach));
        AABB box = target.getBoundingBox();
        Optional<Vec3> entityHit = box.clip(start, end);
        if (!box.contains(start) && entityHit.isEmpty()) {
            return EntityRayState.AIM;
        }

        Optional<Vec3> cobwebHit = firstCobwebHit(client, start, end);
        if (cobwebHit.isPresent() && entityHit.isPresent()
                && start.distanceToSqr(cobwebHit.get())
                + 1.0E-7D < start.distanceToSqr(entityHit.get())) {
            return EntityRayState.BLOCKED;
        }

        HitResult blockHit = clipBlocks(client, start, end);
        if (blockHit.getType() == HitResult.Type.MISS) {
            return EntityRayState.HIT;
        }
        boolean unobstructed = box.contains(start) || entityHit.isPresent()
                && start.distanceToSqr(blockHit.getLocation())
                >= start.distanceToSqr(entityHit.get());
        return unobstructed ? EntityRayState.HIT : EntityRayState.BLOCKED;
    }

    public static double distanceToBlock(
            Minecraft client,
            Vec3 start,
            Vec3 end,
            double missDistance
    ) {
        HitResult hit = clipBlocks(client, start, end);
        double distance = hit.getType() == HitResult.Type.MISS
                ? missDistance
                : start.distanceTo(hit.getLocation());
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
    private static Optional<Vec3> firstCobwebHit(
            Minecraft client, Vec3 start, Vec3 end) {
        var currentLevel = client == null ? null : client.level;
        if (client == null || currentLevel == null || start == null || end == null) {
            return Optional.empty();
        }
        int minX = Mth.floor(Math.min(start.x, end.x));
        int minY = Mth.floor(Math.min(start.y, end.y));
        int minZ = Mth.floor(Math.min(start.z, end.z));
        int maxX = Mth.floor(Math.max(start.x, end.x));
        int maxY = Mth.floor(Math.max(start.y, end.y));
        int maxZ = Mth.floor(Math.max(start.z, end.z));
        Vec3 closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!currentLevel.getBlockState(pos).is(Blocks.COBWEB)) continue;
                    AABB web = new AABB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
                    if (web.contains(start)) continue;
                    Optional<Vec3> hit = web.clip(start, end);
                    if (hit.isEmpty()) continue;
                    double distance = start.distanceToSqr(hit.get());
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
