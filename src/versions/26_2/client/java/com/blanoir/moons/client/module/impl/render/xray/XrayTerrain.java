package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.module.impl.render.Caver;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Controls the real through-wall terrain X-ray.
 *
 * <p>Minecraft 26.2 processes Caver at the final BlockQuadOutput boundary. Blocks
 * still follow the normal model and face-culling path; only quads Minecraft actually
 * emits are redirected to the translucent layer and receive the Caver alpha.
 */
public final class XrayTerrain {
    /**
     * 96/255 is visible enough to preserve terrain context in the 26.2 pipeline.
     */
    private static final int BACKGROUND_ALPHA = 96;
    private static final ThreadLocal<Boolean> RENDERING_BACKGROUND = ThreadLocal.withInitial(() -> false);

    private XrayTerrain() {
    }

    public static boolean isEnabled() {
        return Caver.isEnabled();
    }

    public static boolean isRenderingBackground() {
        return RENDERING_BACKGROUND.get();
    }

    public static void beginBackground() {
        RENDERING_BACKGROUND.set(true);
    }

    public static void endBackground() {
        RENDERING_BACKGROUND.set(false);
    }

    public static boolean supportsBackgroundTransparency() {
        return true;
    }

    public static ChunkSectionLayer forceTranslucentLayer(ChunkSectionLayer original) {
        return isEnabled() && isRenderingBackground()
                ? ChunkSectionLayer.TRANSLUCENT : original;
    }

    public static void applyAlpha(QuadInstance quadInstance) {
        if (!isEnabled() || !isRenderingBackground()) {
            return;
        }

        float alpha = BACKGROUND_ALPHA / 255.0f;
        for (int i = 0; i < 4; i++) {
            quadInstance.setColor(i, ARGB.multiplyAlpha(quadInstance.getColor(i), alpha));
        }
    }

    /**
     * 26.2 must not cancel tesselateBlock. SectionCompiler owns the output routing
     * and cancelling here can leave an otherwise valid section with no terrain mesh.
     */
    public static boolean shouldSkipBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }
}
