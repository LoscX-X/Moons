package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded room inference from immutable block evidence; never reads the live world. */
final class DungeonEvidence {
    private DungeonEvidence() {}

    static List<StructureEvidence.Found> locate(List<StructureEvidence.Marker> markers) {
        Set<BlockPos> masonry = new HashSet<>(), moss = new HashSet<>();
        Set<BlockPos> cobble = new HashSet<>(), openFloors = new HashSet<>();
        List<BlockPos> spawners = new ArrayList<>();
        for (var marker : markers) {
            if (marker.kind() == StructureEvidence.Kind.SPAWNER) spawners.add(marker.pos());
            if (marker.kind() != StructureEvidence.Kind.DUNGEON
                    || !StructureEvidence.allowedHeight(marker.kind(), marker.pos().getY()))
                continue;
            masonry.add(marker.pos());
            if (marker.mossy()) moss.add(marker.pos());
            else cobble.add(marker.pos());
            if (marker.openAbove()) openFloors.add(marker.pos());
        }
        List<BlockPos> seeds = new ArrayList<>(moss);
        seeds.sort(
                Comparator.<BlockPos>comparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ));
        List<StructureEvidence.Found> result = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : seeds) {
            if (!moss.remove(seed)) continue;
            queue.add(seed);
            int minX = seed.getX(), maxX = minX, minZ = seed.getZ(), maxZ = minZ, count = 0;
            while (!queue.isEmpty()) {
                BlockPos pos = queue.removeFirst();
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
                count++;
                // Moss is mixed with plain cobble. Bridge a one-block gap, but never merge floors
                // at different Y.
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        BlockPos next = pos.offset(dx, 0, dz);
                        if (moss.remove(next)) queue.addLast(next);
                    }
            }
            int width = maxX - minX + 1, depth = maxZ - minZ + 1;
            // Vanilla outer floor dimensions are 7/9 per axis. Smaller remnants are allowed.
            if (count < 6
                    || width < 5
                    || depth < 5
                    || width > 9
                    || depth > 9
                    || count < width * depth * .3) continue;
            int floorY = seed.getY();
            // Dense moss piles also have horizontal layers and raised edges. Require
            // actual two-block headroom over most of the surviving interior floor.
            int interior = 0, open = 0;
            boolean openSquare = false;
            for (int x = minX + 1; x < maxX; x++)
                for (int z = minZ + 1; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, floorY, z);
                    if (masonry.contains(pos)) interior++;
                    if (!openFloors.contains(pos)) continue;
                    open++;
                    if (x + 1 < maxX
                            && z + 1 < maxZ
                            && openFloors.contains(pos.offset(1, 0, 0))
                            && openFloors.contains(pos.offset(0, 0, 1))
                            && openFloors.contains(pos.offset(1, 0, 1))) openSquare = true;
                }
            if (open < 6 || open < interior * .7 || !openSquare) continue;
            AABB bounds = new AABB(minX, floorY, minZ, maxX + 1, floorY + 5, maxZ + 1);
            boolean confirmed =
                    spawners.stream()
                            .anyMatch(
                                    p ->
                                            bounds.inflate(1)
                                                    .contains(
                                                            net.minecraft.world.phys.Vec3
                                                                    .atCenterOf(p)));
            if (confirmed) continue; // A visible cage already gets its exact one-block marker.
            // Vanilla walls are plain cobble. Scattered raised moss or a single pillar
            // cannot stand in for a surviving wall; retain a contiguous 3 x 2 strip.
            if (!hasWall(cobble, floorY, minX, maxX, minZ, maxZ)) continue;
            result.add(
                    new StructureEvidence.Found(
                            StructureEvidence.Kind.DUNGEON, bounds, count, false));
            if (result.size() == 512) break;
        }
        return result;
    }

    private static boolean hasWall(
            Set<BlockPos> cobble, int y, int minX, int maxX, int minZ, int maxZ) {
        for (int x : new int[] {minX - 1, minX, maxX, maxX + 1})
            if (wallStrip(cobble, x, y, minZ, maxZ, true)) return true;
        for (int z : new int[] {minZ - 1, minZ, maxZ, maxZ + 1})
            if (wallStrip(cobble, z, y, minX, maxX, false)) return true;
        return false;
    }

    private static boolean wallStrip(
            Set<BlockPos> cobble, int edge, int floorY, int min, int max, boolean alongZ) {
        for (int y = floorY + 1; y <= floorY + 2; y++) {
            int run = 0;
            for (int i = min; i <= max; i++) {
                BlockPos pos = alongZ ? new BlockPos(edge, y, i) : new BlockPos(i, y, edge);
                run = cobble.contains(pos) && cobble.contains(pos.above()) ? run + 1 : 0;
                if (run >= 3) return true;
            }
        }
        return false;
    }
}
