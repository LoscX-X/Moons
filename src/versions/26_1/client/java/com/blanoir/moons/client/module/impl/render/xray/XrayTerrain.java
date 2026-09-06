package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.module.impl.render.Caver;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Controls the real through-wall terrain X-ray.
 *
 * <p>While the X-ray display is enabled, buried blocks are skipped while chunks are
 * compiled, and blocks exposed to air are written into the translucent terrain layer
 * at 25% opacity (75% transparency).
 */
public final class XrayTerrain {
    /**
     * 64/255 = 25% opacity = 75% transparency.
     */
    private static final int BACKGROUND_ALPHA = 64;
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
        return isRenderingBackground() ? ChunkSectionLayer.TRANSLUCENT : original;
    }

    public static void applyAlpha(QuadInstance quadInstance) {
        if (!isRenderingBackground()) {
            return;
        }

        float alpha = BACKGROUND_ALPHA / 255.0f;

        for (int i = 0; i < 4; i++) {
            quadInstance.setColor(i, ARGB.multiplyAlpha(quadInstance.getColor(i), alpha));
        }
    }

    public static boolean isExposedToAir(BlockGetter level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));

            // Air, fluids and non-full blocks (banners, flowers, leaves, ...) all count
            // as open space: surfaces behind them are still visible in Caver mode.
            if (neighbor.isAir() || !neighbor.getFluidState().isEmpty() || !neighbor.isSolidRender()) {
                return true;
            }
        }

        return false;
    }

    /**
     * While the X-ray is active, only fully buried solid blocks are hidden.
     * Non-full blocks (banners, flowers, grass, leaves, glass, ...) always render
     * at 75% transparency, and exposed solid terrain renders at 75% as well.
     */
    public static boolean shouldSkipBlock(BlockState state, BlockGetter level, BlockPos pos) {
        if (!state.isSolidRender()) {
            return false;
        }

        return !isExposedToAir(level, pos);
    }
}
