package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.module.impl.render.Caver;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.world.IBlockAccess;

/** Native chunk-compiler policy: buried solids are culled; exposed geometry is translucent. */
public final class XrayTerrain {
    private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);

    private XrayTerrain() {}

    public static boolean isEnabled() {
        return Caver.isEnabled();
    }

    public static boolean isRenderingBackground() {
        return BACKGROUND.get();
    }

    public static void beginBackground() {
        BACKGROUND.set(true);
    }

    public static void endBackground() {
        BACKGROUND.remove();
    }

    public static boolean supportsBackgroundTransparency() {
        return true;
    }

    public static boolean beginBlock(IBlockState state, IBlockAccess level, BlockPos position) {
        endBackground();
        if (!isEnabled()) return false;
        if (shouldSkipBlock(state, level, position)) return true;
        beginBackground();
        return false;
    }

    public static EnumWorldBlockLayer forceTranslucentLayer(EnumWorldBlockLayer original) {
        return isEnabled() ? EnumWorldBlockLayer.TRANSLUCENT : original;
    }

    public static int alpha(int original) {
        return isRenderingBackground() ? Math.round(original * .25f) : original;
    }

    /** Copies cached baked-quad arrays before applying alpha, so toggling cannot corrupt models. */
    public static int[] applyAlpha(int[] vertices) {
        if (!isRenderingBackground() || vertices == null || vertices.length % 4 != 0)
            return vertices;
        int stride = vertices.length / 4;
        if (stride < 4) return vertices;
        int[] result = vertices.clone();
        boolean little = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * stride + 3, color = result[offset];
            int a = little ? color >>> 24 : color & 255;
            result[offset] =
                    little
                            ? (color & 0xffffff) | (alpha(a) << 24)
                            : (color & 0xffffff00) | alpha(a);
        }
        return result;
    }

    public static boolean isExposedToAir(IBlockAccess level, BlockPos pos) {
        for (EnumFacing face : EnumFacing.values()) {
            var neighbor = level.getBlockState(pos.offset(face)).getBlock();
            if (!neighbor.isOpaqueCube() || neighbor.getMaterial().isLiquid()) return true;
        }
        return false;
    }

    public static boolean shouldSkipBlock(IBlockState state, IBlockAccess level, BlockPos pos) {
        return state.getBlock().isOpaqueCube() && !isExposedToAir(level, pos);
    }
}
