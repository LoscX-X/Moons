package com.blanoir.moons.client.module.impl.render.xray;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Detached section data and target decisions; computation never reaches a live chunk/config. */
final class OreChunkSnapshot {
    private final int minY;
    private final List<PalettedContainer<BlockState>> sections;
    private final Set<BlockState> targets;

    private OreChunkSnapshot(
            int minY, List<PalettedContainer<BlockState>> sections, Set<BlockState> targets) {
        this.minY = minY;
        this.sections = List.copyOf(sections);
        this.targets = Collections.unmodifiableSet(targets);
    }

    static OreChunkSnapshot capture(
            LevelChunkSection[] source, int minY, Predicate<BlockState> selected) {
        var sections = new ArrayList<PalettedContainer<BlockState>>(source.length);
        Set<BlockState> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LevelChunkSection section : source) {
            var copy = section.getStates().copy();
            copy.getAll(
                    state -> {
                        if (selected.test(state)) targets.add(state);
                    });
            sections.add(copy);
        }
        return new OreChunkSnapshot(minY, sections, targets);
    }

    List<BlockPos> scan(int chunkX, int chunkZ, BooleanSupplier cancelled) {
        var positions = new ArrayList<BlockPos>();
        // Preserve original column order, including negative chunk coordinates and all heights.
        for (int x = 0; x < 16; x++) {
            if (cancelled.getAsBoolean()) return null;
            for (int z = 0; z < 16; z++) {
                for (int i = 0; i < sections.size(); i++) {
                    var section = sections.get(i);
                    for (int dy = 0; dy < 16; dy++) {
                        if (targets.contains(section.get(x, dy, z)))
                            positions.add(
                                    new BlockPos(
                                            (chunkX << 4) + x,
                                            minY + i * 16 + dy,
                                            (chunkZ << 4) + z));
                    }
                }
            }
        }
        return List.copyOf(positions);
    }
}
