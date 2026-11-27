package com.blanoir.tempos.client.utils.raytrace;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Block intersections and distances, including cobweb cover. */
public final class BlockRaycast {
    private BlockRaycast() {}

    public static HitResult clipBlocks(Minecraft client, Vec3 start, Vec3 end) {
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (client == null
                || currentLevel == null
                || currentPlayer == null
                || start == null
                || end == null) {
            return BlockHitResult.miss(
                    start == null ? Vec3.ZERO : start, Direction.UP, BlockPos.ZERO);
        }
        return currentLevel.clip(
                new ClipContext(
                        start,
                        end,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        currentPlayer));
    }

    public static double distanceToBlock(
            Minecraft client, Vec3 start, Vec3 end, double missDistance) {
        HitResult hit = clipBlocks(client, start, end);
        double distance =
                hit.getType() == HitResult.Type.MISS
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
    static Optional<Vec3> firstCobwebHit(Minecraft client, Vec3 start, Vec3 end) {
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
