package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.module.impl.render.Caver;
import com.mojang.blaze3d.vertex.VertexConsumer;

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

    private static final ThreadLocal<Boolean> RENDERING_BACKGROUND =
            ThreadLocal.withInitial(() -> false);

    private XrayTerrain() {}

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

    public static ChunkSectionLayer forceTranslucentLayer(ChunkSectionLayer original) {
        return isRenderingBackground() ? ChunkSectionLayer.TRANSLUCENT : original;
    }

    public static VertexConsumer alphaConsumer(VertexConsumer delegate) {
        if (!isRenderingBackground()) return delegate;
        return new VertexConsumer() {
            public VertexConsumer addVertex(float x, float y, float z) {
                delegate.addVertex(x, y, z);
                return this;
            }

            public VertexConsumer setColor(int r, int g, int b, int a) {
                delegate.setColor(r, g, b, a * BACKGROUND_ALPHA / 255);
                return this;
            }

            public VertexConsumer setColor(int color) {
                delegate.setColor(ARGB.multiplyAlpha(color, BACKGROUND_ALPHA / 255f));
                return this;
            }

            public VertexConsumer setUv(float u, float v) {
                delegate.setUv(u, v);
                return this;
            }

            public VertexConsumer setUv1(int u, int v) {
                delegate.setUv1(u, v);
                return this;
            }

            public VertexConsumer setUv2(int u, int v) {
                delegate.setUv2(u, v);
                return this;
            }

            public VertexConsumer setNormal(float x, float y, float z) {
                delegate.setNormal(x, y, z);
                return this;
            }

            public VertexConsumer setLineWidth(float width) {
                delegate.setLineWidth(width);
                return this;
            }
        };
    }

    public static boolean isExposedToAir(BlockGetter level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));

            // Air, fluids and non-full blocks (banners, flowers, leaves, ...) all count
            // as open space: surfaces behind them are still visible in Caver mode.
            if (neighbor.isAir()
                    || !neighbor.getFluidState().isEmpty()
                    || !neighbor.isSolidRender()) {
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
