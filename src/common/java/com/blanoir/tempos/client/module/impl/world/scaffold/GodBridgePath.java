package com.blanoir.tempos.client.module.impl.world.scaffold;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Chooses support cells without owning input, player motion or action timing. */
public final class GodBridgePath {
    private BlockPos placedBlock;
    private Vec3 placedFrom;

    public void placed(BlockPos block, Vec3 position) {
        placedBlock = block.immutable();
        placedFrom = position;
    }

    public void reset() {
        placedBlock = null;
        placedFrom = null;
    }

    public List<BlockPos> cells(
            AABB feet, Vec3 velocity, AABB intended, int row, Predicate<BlockPos> covered) {
        Vec3 center = feet.getCenter();
        if (placedBlock != null) {
            // A second render/update at the same standing position must not fill another
            // footprint cell. Any actual travel releases this guard; no recentering is required.
            if (placedBlock.getY() == row
                    && covered.test(placedBlock)
                    && Math.abs(center.x - placedFrom.x) < 1.0E-6
                    && Math.abs(center.z - placedFrom.z) < 1.0E-6) return List.of();
            reset();
        }
        BlockPos inertia =
                BlockPos.containing(
                        center.x + Math.clamp(velocity.x, -.5, .5),
                        row,
                        center.z + Math.clamp(velocity.z, -.5, .5));
        Vec3 requested = intended.getCenter();
        BlockPos input = BlockPos.containing(requested.x, row, requested.z);
        Set<BlockPos> allowed = new HashSet<>();
        List<BlockPos> footprint = GodBridgeTargetSelector.cells(feet, velocity, intended, row);
        for (BlockPos destination : List.of(inertia, input)) {
            if (covered.test(destination)) continue;
            allowed.add(destination);
            boolean connected = false;
            for (Direction face : Direction.Plane.HORIZONTAL)
                connected |= covered.test(destination.relative(face));
            if (connected) continue;
            // A diagonal cell needs a face-connected step, not every empty footprint cell.
            for (BlockPos connector : footprint) {
                if (connector.distManhattan(destination) != 1) continue;
                for (Direction face : Direction.Plane.HORIZONTAL) {
                    if (covered.test(connector.relative(face))) {
                        allowed.add(connector);
                        break;
                    }
                }
            }
        }
        return footprint.stream().filter(allowed::contains).toList();
    }
}
