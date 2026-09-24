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
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            int y = minY + i * 16;
            boolean markers =
                    section.maybeHas(
                            state -> wants(enabled, StructureEvidence.marker(state.getBlock())));
            boolean shape = shapeEnabled && y >= CavitySnapshot.MIN_Y && y < CavitySnapshot.MAX_Y;
            if (!markers && !shape) continue;
            boolean uniformWall = shape && !section.maybeHas(state -> !state.isSolidRender());
            // A solid palette needs no per-block copy when no material markers are requested.
            result.add(
                    new Section(
                            y,
                            !markers && uniformWall ? null : section.getStates().copy(),
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
                                    state.is(Blocks.CALCITE)));
            }
        }
        return new Result(List.copyOf(markers), cavity == null ? null : cavity.build());
    }
}
