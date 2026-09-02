package com.blanoir.moons.client.utils.world;

public final class ChunkKey {
    private ChunkKey() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xffffffffL) | (((long) chunkZ & 0xffffffffL) << 32);
    }

    public static int unpackX(long packed) {
        return (int) packed;
    }

    public static int unpackZ(long packed) {
        return (int) (packed >> 32);
    }
}
