package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Marker rules ported from Open-Kawasaki's StructureFinderModule and its support classes. */
public final class StructureEvidence {
    private static final Block COPPER_BULB =
            BuiltInRegistries.BLOCK
                    .getOptional(Identifier.parse("minecraft:copper_bulb"))
                    .orElseThrow();
    private static final Block OXIDIZED_COPPER_BULB =
            BuiltInRegistries.BLOCK
                    .getOptional(Identifier.parse("minecraft:oxidized_copper_bulb"))
                    .orElseThrow();

    public enum Kind {
        ANCIENT_CITY("Ancient City", 0x0096FF, 96),
        STRONGHOLD("Stronghold", 0x96FF64, 80),
        TRIAL_CHAMBER("Trial Chamber", 0xFF7832, 64),
        NETHER_PORTAL("Nether Portal", 0xAA46FF, 64),
        SPAWNER("Spawner", 0x59E59B, 0),
        DUNGEON("Possible Dungeon", 0xFFBC55, 0),
        AMETHYST_GEODE("Amethyst Geode", 0xBE8CFF, 0);

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

    public record Found(Kind kind, AABB bounds, int markers, boolean visited, boolean tentative) {
        public Found(Kind kind, AABB bounds, int markers, boolean visited) {
            this(kind, bounds, markers, visited, false);
        }

        public String label() {
            return tentative ? "Possible " + kind.label : kind.label;
        }

        public Vec3 center() {
            return bounds.getCenter();
        }

        public Found visit() {
            return visited ? this : new Found(kind, bounds, markers, true, tentative);
        }
    }

    public static Kind marker(Block block) {
        if (block == Blocks.END_PORTAL_FRAME) return Kind.STRONGHOLD;
        if (block == Blocks.NETHER_PORTAL) return Kind.NETHER_PORTAL;
        if (block == Blocks.SPAWNER) return Kind.SPAWNER;
        if (block == Blocks.AMETHYST_BLOCK
                || block == Blocks.BUDDING_AMETHYST
                || block == Blocks.CALCITE) return Kind.AMETHYST_GEODE;
        if (block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE) return Kind.DUNGEON;
        if (block == COPPER_BULB
                || block == OXIDIZED_COPPER_BULB
                || block == Blocks.CHISELED_TUFF
                || block == Blocks.CHISELED_TUFF_BRICKS
                || block == Blocks.TUFF_BRICKS
                || block == Blocks.POLISHED_TUFF) return Kind.TRIAL_CHAMBER;
        if (block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.DEEPSLATE_BRICKS
                || block == Blocks.CRACKED_DEEPSLATE_BRICKS
                || block == Blocks.DEEPSLATE_TILES
                || block == Blocks.CRACKED_DEEPSLATE_TILES
                || block == Blocks.CHISELED_DEEPSLATE
                || block == Blocks.POLISHED_DEEPSLATE) return Kind.ANCIENT_CITY;
        return null;
    }

    public static boolean allowedHeight(Kind kind, int y) {
        return switch (kind) {
            case STRONGHOLD -> y >= -64 && y < 80;
            case TRIAL_CHAMBER -> y >= -64 && y <= 80;
            case ANCIENT_CITY -> y >= -64 && y <= 8;
            case NETHER_PORTAL, SPAWNER, AMETHYST_GEODE -> true;
            case DUNGEON -> y >= -64 && y <= 80;
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
                    || marker.kind == Kind.AMETHYST_GEODE
                    || !enabled.contains(marker.kind)
                    || !allowedHeight(marker.kind, marker.pos.getY())
                    || !seen.add(marker)) continue;
            if (marker.kind == Kind.TRIAL_CHAMBER && nearFrame(marker.pos, frames)) continue;
            Found next = new Found(marker.kind, new AABB(marker.pos), 1, false);
            for (int i = 0; i < groups.size(); i++) {
                Found old = groups.get(i);
                if (!joins(old, next)) continue;
                next =
                        new Found(
                                old.kind,
                                old.bounds.minmax(next.bounds),
                                old.markers + next.markers,
                                false);
                groups.remove(i);
                i = -1;
            }
            if (groups.size() < 512) groups.add(next);
        }
        // Apply the source's city size check after grouping, rather than making the
        // marker count depend on the traversal order of partial bounding boxes.
        groups.removeIf(f -> f.kind == Kind.ANCIENT_CITY && !cityShape(f));
        if (enabled.contains(Kind.DUNGEON)) {
            for (Found room : DungeonEvidence.locate(markers)) {
                if (groups.size() == 512) break;
                groups.add(room);
            }
        }
        if (enabled.contains(Kind.AMETHYST_GEODE)) {
            for (Found geode : GeodeEvidence.locate(markers)) {
                if (groups.size() == 512) break;
                groups.add(geode);
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
                ? a.bounds.inflate(1).intersects(b.bounds)
                : a.center().distanceToSqr(b.center()) <= a.kind.mergeRange * a.kind.mergeRange;
    }

    private static boolean cityShape(Found found) {
        double x = found.bounds.getXsize(),
                y = found.bounds.getYsize(),
                z = found.bounds.getZsize();
        return found.markers < 3
                || (!(x < 4 && z < 4) && y >= 2 && (found.markers >= 8 || x >= 6 && z >= 6));
    }

    public static List<Found> carryVisits(List<Found> fresh, List<Found> previous, Vec3 player) {
        return fresh.stream()
                .map(
                        f ->
                                f.bounds.contains(player)
                                                || previous.stream()
                                                        .anyMatch(
                                                                p ->
                                                                        p.visited
                                                                                && p.kind == f.kind
                                                                                && p.bounds
                                                                                        .inflate(1)
                                                                                        .intersects(
                                                                                                f.bounds))
                                        ? f.visit()
                                        : f)
                .toList();
    }
}
