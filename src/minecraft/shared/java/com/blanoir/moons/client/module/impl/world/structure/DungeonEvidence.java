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
        List<BlockPos> spawners = new ArrayList<>();
        for (var marker : markers) {
            if (marker.kind() == StructureEvidence.Kind.SPAWNER) spawners.add(marker.pos());
            if (marker.kind() != StructureEvidence.Kind.DUNGEON
                    || !StructureEvidence.allowedHeight(marker.kind(), marker.pos().getY()))
                continue;
            masonry.add(marker.pos());
            if (marker.mossy()) moss.add(marker.pos());
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
                    || width < 3
                    || depth < 3
                    || width > 9
                    || depth > 9
                    || count < width * depth * .3) continue;
            int floorY = seed.getY();
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
            int wallBlocks = 0, levels = 0;
            // Require some raised masonry, not a complete shell: mine/cave intersections can remove
            // walls.
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                boolean onLevel = false;
                for (int x = minX - 1; x <= maxX + 1; x++)
                    for (int z = minZ - 1; z <= maxZ + 1; z++) {
                        if (x > minX && x < maxX && z > minZ && z < maxZ) continue;
                        if (masonry.contains(new BlockPos(x, y, z))) {
                            wallBlocks++;
                            onLevel = true;
                        }
                    }
                if (onLevel) levels++;
            }
            if (wallBlocks < 3 || levels < 2) continue;
            result.add(
                    new StructureEvidence.Found(
                            StructureEvidence.Kind.DUNGEON, bounds, count, false));
            if (result.size() == 512) break;
        }
        return result;
    }
}
