package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

public final class Clip {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("clip.enabled").defaultValue(false).build();
    private static final int VISIBLE_RADIUS_CHUNKS = 12;
    private static volatile VisibilityRange visibilityRange;

    private Clip() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int visibleRadiusChunks() {
        return VISIBLE_RADIUS_CHUNKS;
    }

    /** Keeps the existing 12-chunk square; refreshes only after crossing a chunk boundary. */
    public static boolean forceVisible(AABB box) {
        if (!ENABLED.get()) return false;
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null
                || client.level == null
                || client.options.getCameraType().isFirstPerson()) return false;
        var chunk = player.chunkPosition();
        VisibilityRange range = visibilityRange;
        if (range == null || range.chunkX != chunk.x() || range.chunkZ != chunk.z()) {
            range = new VisibilityRange(chunk.x(), chunk.z());
            visibilityRange = range;
        }
        return range.contains(box.minX, box.minZ);
    }

    private static final class VisibilityRange {
        private final int chunkX;
        private final int chunkZ;
        private final double minX;
        private final double minZ;
        private final double maxX;
        private final double maxZ;

        private VisibilityRange(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            minX = ((long) chunkX - VISIBLE_RADIUS_CHUNKS) * 16.0;
            minZ = ((long) chunkZ - VISIBLE_RADIUS_CHUNKS) * 16.0;
            maxX = ((long) chunkX + VISIBLE_RADIUS_CHUNKS + 1L) * 16.0;
            maxZ = ((long) chunkZ + VISIBLE_RADIUS_CHUNKS + 1L) * 16.0;
        }

        private boolean contains(double x, double z) {
            return x >= minX && x < maxX && z >= minZ && z < maxZ;
        }
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(client, "Clip " + statusText() + ".");
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }
}
