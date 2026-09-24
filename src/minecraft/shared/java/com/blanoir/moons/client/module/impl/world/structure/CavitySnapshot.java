package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/** Immutable block occupancy. Coarse probes share the same data as the final voxel check. */
final class CavitySnapshot {
    static final int MIN_Y = -64, MAX_Y = 64;
    static final byte UNKNOWN = 0, AIR = 1, WALL = 2, OTHER = 3, DECORATION = 4;

    static boolean open(byte cell) {
        return cell == AIR || cell == DECORATION;
    }

    static byte classify(BlockState state) {
        if (state.isAir()) return AIR;
        if (state.isSolidRender()) return WALL;
        // Crystal growth occupies part of a geode's air pocket. Plants, fences and other
        // non-opaque blocks are not evidence of an empty chamber.
        return state.getBlock() instanceof AmethystClusterBlock && state.getFluidState().isEmpty()
                ? DECORATION
                : OTHER;
    }

    final int chunkX, chunkZ;
    private final byte[] cells;

    private CavitySnapshot(int chunkX, int chunkZ, byte[] cells) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.cells = cells.clone();
    }

    byte at(int x, int y, int z) {
        return atBlock(x * 2, y * 2, z * 2);
    }

    byte atBlock(int x, int y, int z) {
        if (y < MIN_Y || y >= MAX_Y) return UNKNOWN;
        return cells[(y - MIN_Y) * 256 + (z & 15) * 16 + (x & 15)];
    }

    static final class Builder {
        private final int chunkX, chunkZ;
        private final byte[] cells = new byte[(MAX_Y - MIN_Y) * 256];

        Builder(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        void set(int x, int y, int z, byte value) {
            if (y < MIN_Y || y >= MAX_Y) return;
            cells[(y - MIN_Y) * 256 + z * 16 + x] = value;
        }

        void solidSection(int baseY) {
            int start = Math.max(MIN_Y, baseY), end = Math.min(MAX_Y, baseY + 16);
            if (start < end) Arrays.fill(cells, (start - MIN_Y) * 256, (end - MIN_Y) * 256, WALL);
        }

        CavitySnapshot build() {
            return new CavitySnapshot(chunkX, chunkZ, cells);
        }
    }
}
