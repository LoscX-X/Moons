package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Copies 1.8 packed state IDs on the client thread; workers never touch a mutable chunk. */
final class StructureChunkScanner {
    record Section(int y, char[] blocks) {}

    record Result(List<StructureEvidence.Marker> markers, CavitySnapshot cavity) {}

    static boolean wants(EnumSet<StructureEvidence.Kind> enabled, StructureEvidence.Kind kind) {
        return kind != null
                && (enabled.contains(kind)
                        || kind == StructureEvidence.Kind.SPAWNER
                                && enabled.contains(StructureEvidence.Kind.DUNGEON));
    }

    static List<Section> capture(
            ExtendedBlockStorage[] sections,
            int minY,
            EnumSet<StructureEvidence.Kind> enabled,
            boolean shapeEnabled) {
        var result = new ArrayList<Section>();
        for (var section : sections)
            if (section != null && !section.isEmpty())
                result.add(new Section(section.getYLocation(), section.getData().clone()));
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
        for (var section : sections) {
            if (cancelled.getAsBoolean()) return null;
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >> 4) & 15, y = section.y() + (i >> 8);
                int packed = section.blocks()[i];
                Block block = Block.getBlockById(packed >> 4);
                var kind = StructureEvidence.marker(block);
                if (wants(enabled, kind)
                        && StructureEvidence.allowedHeight(kind, y)
                        && markers.size() < 16384)
                    markers.add(
                            new StructureEvidence.Marker(
                                    kind,
                                    new BlockPos((chunkX << 4) + x, y, (chunkZ << 4) + z),
                                    block == Blocks.mossy_cobblestone));
            }
        }
        return new Result(List.copyOf(markers), null);
    }
}
