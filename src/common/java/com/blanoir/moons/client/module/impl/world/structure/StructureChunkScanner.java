package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Copies selected palettes on the client thread; all block traversal uses the copies. */
final class StructureChunkScanner {
    record Section(int y, PalettedContainer<BlockState> blocks, boolean markers, boolean shape) {}

    record Result(List<StructureEvidence.Marker> markers, CavitySnapshot cavity) {}

    static boolean wants(EnumSet<StructureEvidence.Kind> enabled, StructureEvidence.Kind kind) {
        return kind != null
                && (enabled.contains(kind)
                        || kind == StructureEvidence.Kind.SPAWNER
                                && enabled.contains(StructureEvidence.Kind.DUNGEON)
                        || kind == StructureEvidence.Kind.STRONGHOLD
                                && enabled.contains(StructureEvidence.Kind.TRIAL_CHAMBER));
    }

    static List<Section> capture(
            LevelChunkSection[] sections,
            int minY,
            EnumSet<StructureEvidence.Kind> enabled,
            boolean shapeEnabled) {
        var result = new ArrayList<Section>();
        boolean previousDungeon = false;
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            int y = minY + i * 16;
            boolean markers =
                    section.maybeHas(
                            state -> wants(enabled, StructureEvidence.marker(state.getBlock())));
            boolean shape = shapeEnabled && y >= CavitySnapshot.MIN_Y && y < CavitySnapshot.MAX_Y;
            boolean dungeon =
                    enabled.contains(StructureEvidence.Kind.DUNGEON)
                            && y >= -64
                            && y <= 80
                            && section.maybeHas(
                                    state ->
                                            state.is(Blocks.MOSSY_COBBLESTONE)
                                                    || state.is(Blocks.COBBLESTONE));
            // Floor headroom can cross the top of a section. Copy its neighbour even if
            // that palette contains no structure markers; absent data is never air.
            boolean headroom = previousDungeon;
            previousDungeon = dungeon;
            if (!markers && !shape && !headroom) continue;
            boolean uniformWall = shape && !section.maybeHas(state -> !state.isSolidRender());
            // A solid palette needs no per-block copy when no material markers are requested.
            result.add(
                    new Section(
                            y,
                            !markers && uniformWall && !headroom
                                    ? null
                                    : section.getStates().copy(),
                            markers,
                            shape));
        }
        return List.copyOf(result);
    }

    static Result scan(
            int chunkX,
            int chunkZ,
            List<Section> sections,
            EnumSet<StructureEvidence.Kind> enabled,
            boolean shapeEnabled,
            BooleanSupplier cancelled) {
        var markers = new ArrayList<StructureEvidence.Marker>();
        var cavity = shapeEnabled ? new CavitySnapshot.Builder(chunkX, chunkZ) : null;
        for (var section : sections) {
            if (cancelled.getAsBoolean()) return null;
            // Headroom-only copies are queried by the floor below, not scanned in full.
            if (!section.markers && !section.shape) continue;
            if (section.blocks == null) {
                cavity.solidSection(section.y);
                continue;
            }
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >> 4) & 15, dy = i >> 8, y = section.y + dy;
                var state = section.blocks.get(x, dy, z);
                if (section.shape) cavity.set(x, y, z, CavitySnapshot.classify(state));
                var kind = section.markers ? StructureEvidence.marker(state.getBlock()) : null;
                if (wants(enabled, kind)
                        && StructureEvidence.allowedHeight(kind, y)
                        && markers.size() < 16_384)
                    markers.add(
                            new StructureEvidence.Marker(
                                    kind,
                                    new BlockPos((chunkX << 4) + x, y, (chunkZ << 4) + z),
                                    state.is(Blocks.MOSSY_COBBLESTONE),
                                    state.is(Blocks.CALCITE),
                                    kind == StructureEvidence.Kind.DUNGEON
                                            && openAbove(sections, section, x, y, z)));
            }
        }
        return new Result(List.copyOf(markers), cavity == null ? null : cavity.build());
    }

    private static boolean openAbove(List<Section> sections, Section floor, int x, int y, int z) {
        Section upper = floor;
        for (int dy = 1; dy <= 2; dy++) {
            int aboveY = y + dy;
            if (aboveY >= upper.y + 16) {
                upper = null;
                for (var candidate : sections)
                    if (candidate.y == floor.y + 16) {
                        upper = candidate;
                        break;
                    }
            }
            if (upper == null || upper.blocks == null) return false;
            var state = upper.blocks.get(x, aboveY - upper.y, z);
            // A remaining chest/cage may occupy the first cell of a genuine room.
            if (!state.isAir()
                    && !(dy == 1 && (state.is(Blocks.CHEST) || state.is(Blocks.SPAWNER))))
                return false;
        }
        return true;
    }
}
