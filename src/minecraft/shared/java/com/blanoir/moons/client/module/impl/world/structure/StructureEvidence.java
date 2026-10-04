package com.blanoir.moons.client.module.impl.world.structure;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Marker rules ported from Open-Kawasaki's StructureFinderModule and its support classes. */
public final class StructureEvidence {
    public enum Kind {
        STRONGHOLD("Stronghold", 0x96FF64, 80),
        NETHER_PORTAL("Nether Portal", 0xAA46FF, 64),
        SPAWNER("Spawner", 0x59E59B, 0),
        DUNGEON("Possible Dungeon", 0xFFBC55, 0);

        public final String label;
        public final int color;
        final double mergeRange;

        Kind(String label, int color, double mergeRange) {
            this.label = label;
            this.color = color;
            this.mergeRange = mergeRange;
        }
    }

    public record Marker(Kind kind, BlockPos pos, boolean mossy, boolean calcite) {
        public Marker(Kind kind, BlockPos pos) {
            this(kind, pos, false);
        }

        public Marker(Kind kind, BlockPos pos, boolean mossy) {
            this(kind, pos, mossy, false);
        }
    }

    public record Found(
            Kind kind, AxisAlignedBB bounds, int markers, boolean visited, boolean tentative) {
        public Found(Kind kind, AxisAlignedBB bounds, int markers, boolean visited) {
            this(kind, bounds, markers, visited, false);
        }

        public String label() {
            return tentative ? "Possible " + kind.label : kind.label;
        }

        public Vec3 center() {
            return VecMath.center(bounds);
        }

        public Found visit() {
            return visited ? this : new Found(kind, bounds, markers, true, tentative);
        }
    }

    public static Kind marker(Block block) {
        if (block == Blocks.end_portal_frame) return Kind.STRONGHOLD;
        if (block == Blocks.portal) return Kind.NETHER_PORTAL;
        if (block == Blocks.mob_spawner) return Kind.SPAWNER;
        if (block == Blocks.cobblestone || block == Blocks.mossy_cobblestone) return Kind.DUNGEON;
        return null;
    }

    public static boolean allowedHeight(Kind kind, int y) {
        return switch (kind) {
            case STRONGHOLD -> y >= 0 && y < 80;
            case NETHER_PORTAL, SPAWNER -> true;
            case DUNGEON -> y >= 0 && y <= 80;
        };
    }

    public static List<Found> locate(List<Marker> markers, EnumSet<Kind> enabled) {
        Map<BlockPos, List<BlockPos>> frames = new HashMap<>();
        for (Marker marker : markers)
            if (marker.kind == Kind.STRONGHOLD)
                frames.computeIfAbsent(bucket(marker.pos), unused -> new ArrayList<>())
                        .add(marker.pos);
        List<Found> groups = new ArrayList<>();
        Set<Marker> seen = new HashSet<>();
        for (Marker marker : markers) {
            if (marker.kind == Kind.DUNGEON
                    || !enabled.contains(marker.kind)
                    || !allowedHeight(marker.kind, marker.pos.getY())
                    || !seen.add(marker)) continue;
            Found next = new Found(marker.kind, LegacyWorld.box(marker.pos), 1, false);
            for (int i = 0; i < groups.size(); i++) {
                Found old = groups.get(i);
                if (!joins(old, next)) continue;
                next =
                        new Found(
                                old.kind,
                                old.bounds.union(next.bounds),
                                old.markers + next.markers,
                                false);
                groups.remove(i);
                i = -1;
            }
            if (groups.size() < 512) groups.add(next);
        }
        // Apply the source's city size check after grouping, rather than making the
        // marker count depend on the traversal order of partial bounding boxes.
        if (enabled.contains(Kind.DUNGEON)) {
            for (Found room : DungeonEvidence.locate(markers)) {
                if (groups.size() == 512) break;
                groups.add(room);
            }
        }

        return List.copyOf(groups);
    }

    private static BlockPos bucket(BlockPos pos) {
        return new BlockPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    private static boolean nearFrame(BlockPos pos, Map<BlockPos, List<BlockPos>> frames) {
        for (int x = (pos.getX() - 6) >> 4; x <= (pos.getX() + 6) >> 4; x++)
            for (int y = (pos.getY() - 3) >> 4; y <= (pos.getY() + 3) >> 4; y++)
                for (int z = (pos.getZ() - 6) >> 4; z <= (pos.getZ() + 6) >> 4; z++)
                    for (BlockPos frame : frames.getOrDefault(new BlockPos(x, y, z), List.of()))
                        if (Math.abs(frame.getX() - pos.getX()) <= 6
                                && Math.abs(frame.getY() - pos.getY()) <= 3
                                && Math.abs(frame.getZ() - pos.getZ()) <= 6) return true;
        return false;
    }

    static boolean joins(Found a, Found b) {
        if (a.kind != b.kind) return false;
        return a.kind == Kind.NETHER_PORTAL
                ? LegacyWorld.inflate(a.bounds, 1).intersectsWith(b.bounds)
                : a.center().squareDistanceTo(b.center()) <= a.kind.mergeRange * a.kind.mergeRange;
    }

    private static boolean cityShape(Found found) {
        double x = (found.bounds.maxX - found.bounds.minX),
                y = (found.bounds.maxY - found.bounds.minY),
                z = (found.bounds.maxZ - found.bounds.minZ);
        return found.markers < 3
                || (!(x < 4 && z < 4) && y >= 2 && (found.markers >= 8 || x >= 6 && z >= 6));
    }

    public static List<Found> carryVisits(List<Found> fresh, List<Found> previous, Vec3 player) {
        return fresh.stream()
                .map(
                        f ->
                                f.bounds.isVecInside(player)
                                                || previous.stream()
                                                        .anyMatch(
                                                                p ->
                                                                        p.visited
                                                                                && p.kind == f.kind
                                                                                && LegacyWorld
                                                                                        .inflate(
                                                                                                p.bounds,
                                                                                                1)
                                                                                        .intersectsWith(
                                                                                                f.bounds))
                                        ? f.visit()
                                        : f)
                .toList();
    }
}
