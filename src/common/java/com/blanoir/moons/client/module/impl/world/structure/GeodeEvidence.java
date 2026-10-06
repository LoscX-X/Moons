package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded shell heuristic over a snapshot; never reads the client world on the worker. */
final class GeodeEvidence {
    private static final List<BlockPos> NEIGHBORS = neighbors();

    static List<StructureEvidence.Found> locate(List<StructureEvidence.Marker> markers) {
        Set<BlockPos> amethyst = new HashSet<>(), calcite = new HashSet<>();
        for (var marker : markers) {
            if (marker.kind() != StructureEvidence.Kind.AMETHYST_GEODE) continue;
            (marker.calcite() ? calcite : amethyst).add(marker.pos());
        }
        if (amethyst.size() < 24) return List.of();
        var seeds = new ArrayList<>(amethyst);
        seeds.sort(
                Comparator.<BlockPos>comparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ));
        var results = new ArrayList<StructureEvidence.Found>();
        for (BlockPos seed : seeds) {
            if (!amethyst.remove(seed)) continue;
            var component = new ArrayList<BlockPos>();
            component.add(seed);
            AABB bounds = new AABB(seed);
            for (int i = 0; i < component.size(); i++) {
                BlockPos pos = component.get(i);
                bounds = bounds.minmax(new AABB(pos));
                for (BlockPos offset : NEIGHBORS) {
                    BlockPos next = pos.offset(offset);
                    if (amethyst.remove(next)) component.add(next);
                }
            }
            var geode = shell(component, bounds, calcite);
            if (geode != null) {
                results.add(geode);
                if (results.size() == 512) break;
            }
        }
        return List.copyOf(results);
    }

    private static StructureEvidence.Found shell(
            List<BlockPos> blocks, AABB bounds, Set<BlockPos> calcite) {
        if (blocks.size() < 24) return null;
        double sx = bounds.getXsize(), sy = bounds.getYsize(), sz = bounds.getZsize();
        double shortest = Math.min(sx, Math.min(sy, sz));
        double longest = Math.max(sx, Math.max(sy, sz));
        // Reject paving, pillars and oversized builds; allow an irregular, opened shell.
        if (shortest < 4 || longest > 32 || longest > shortest * 2.7) return null;
        var center = bounds.getCenter();
        int octants = 0, outerDirections = 0, shellDirections = 0, core = 0, shell = 0;
        Set<BlockPos> supportingCalcite = new HashSet<>();
        for (BlockPos pos : blocks) {
            double dx = pos.getX() + .5 - center.x;
            double dy = pos.getY() + .5 - center.y;
            double dz = pos.getZ() + .5 - center.z;
            double nx = 2 * dx / sx, ny = 2 * dy / sy, nz = 2 * dz / sz;
            double radius = nx * nx + ny * ny + nz * nz;
            if (radius < .22) core++;
            if (radius >= .35 && radius <= 2.1) shell++;
            octants |= 1 << ((dx >= 0 ? 1 : 0) | (dy >= 0 ? 2 : 0) | (dz >= 0 ? 4 : 0));
            int direction =
                    Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz)
                            ? (dx >= 0 ? 0 : 1)
                            : Math.abs(ny) >= Math.abs(nz) ? (dy >= 0 ? 2 : 3) : (dz >= 0 ? 4 : 5);
            shellDirections |= 1 << direction;
            for (BlockPos offset : NEIGHBORS) {
                // Supporting white blocks must sit outward of the purple layer.
                if (dx * offset.getX() + dy * offset.getY() + dz * offset.getZ() <= 0) continue;
                BlockPos outer = pos.offset(offset);
                if (!calcite.contains(outer)) continue;
                supportingCalcite.add(outer);
                outerDirections |= 1 << direction;
            }
        }
        if (Integer.bitCount(octants) < 6
                || shell < blocks.size() * .65
                || core > Math.max(1, sx * sy * sz * .012)) return null;
        boolean layered =
                supportingCalcite.size() >= Math.max(8, blocks.size() / 16)
                        && Integer.bitCount(outerDirections) >= 3;
        // A missing white layer is allowed, but shape-only evidence must be more substantial.
        if (!layered
                && (blocks.size() < 80
                        || shortest < 5
                        || longest > shortest * 2
                        || Integer.bitCount(octants) < 7
                        || Integer.bitCount(shellDirections) < 5
                        || shell < blocks.size() * .8)) return null;
        return new StructureEvidence.Found(
                StructureEvidence.Kind.AMETHYST_GEODE, bounds, blocks.size(), false, !layered);
    }

    private static List<BlockPos> neighbors() {
        var result = new ArrayList<BlockPos>();
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    if (x != 0 || y != 0 || z != 0) result.add(new BlockPos(x, y, z));
        // Bridge a missing block without merging every geode in a broad search radius.
        result.addAll(
                List.of(
                        new BlockPos(2, 0, 0),
                        new BlockPos(-2, 0, 0),
                        new BlockPos(0, 2, 0),
                        new BlockPos(0, -2, 0),
                        new BlockPos(0, 0, 2),
                        new BlockPos(0, 0, -2)));
        return List.copyOf(result);
    }
}
