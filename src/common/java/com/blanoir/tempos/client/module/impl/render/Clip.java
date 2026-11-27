package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

@ModuleInfo(category = ModuleCategory.RENDER)
public final class Clip extends ClientFeature {
    public static final Clip INSTANCE = new Clip();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("clip.enabled").defaultValue(false).build();
    private static final int VISIBLE_RADIUS_CHUNKS = 12;
    private volatile VisibilityRange visibilityRange;

    private Clip() {
        super("clip", "Clip");
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public static int visibleRadiusChunks() {
        return VISIBLE_RADIUS_CHUNKS;
    }

    /** Keeps the existing 12-chunk square; refreshes only after crossing a chunk boundary. */
    public boolean forceVisible(AABB box) {
        if (!this.enabled.get()) return false;
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null
                || client.level == null
                || client.options.getCameraType().isFirstPerson()) return false;
        var chunk = player.chunkPosition();
        VisibilityRange range = this.visibilityRange;
        if (range == null || range.chunkX != chunk.x() || range.chunkZ != chunk.z()) {
            range = new VisibilityRange(chunk.x(), chunk.z());
            this.visibilityRange = range;
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

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        ClientChat.send(client, "Clip " + statusText() + ".");
        return 1;
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }
}
