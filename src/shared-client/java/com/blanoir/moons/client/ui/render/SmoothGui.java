package com.blanoir.moons.client.ui.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Lightweight native GUI primitives with no per-frame texture allocation.
 *
 * <p>Rounded edges are rasterized as short horizontal spans. The one-pixel edge
 * receives fractional alpha coverage, keeping animated alpha values smooth
 * without creating a new {@code DynamicTexture} for every animation frame.</p>
 */
public final class SmoothGui {
    private SmoothGui() {
    }

    public static void roundedRect(GuiGraphicsExtractor graphics, int left, int top,
                                   int right, int bottom, int radius, int color) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) {
            return;
        }

        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (r == 0) {
            graphics.fill(left, top, right, bottom, color);
            return;
        }

        graphics.fill(left, top + r, right, bottom - r, color);
        int sourceAlpha = color >>> 24;
        int rgb = color & 0xFFFFFF;
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5D;
            double edge = r - Math.sqrt(Math.max(0.0D, r * r - dy * dy));
            int inset = (int) Math.ceil(edge);
            int yTop = top + row;
            int yBottom = bottom - row - 1;
            graphics.fill(left + inset, yTop, right - inset, yTop + 1, color);
            graphics.fill(left + inset, yBottom, right - inset, yBottom + 1, color);

            double coverage = inset - edge;
            int edgeAlpha = (int) Math.round(sourceAlpha * coverage);
            if (inset <= 0 || edgeAlpha <= 0) continue;
            int edgeColor = (edgeAlpha << 24) | rgb;
            graphics.fill(left + inset - 1, yTop, left + inset, yTop + 1, edgeColor);
            graphics.fill(right - inset, yTop, right - inset + 1, yTop + 1, edgeColor);
            graphics.fill(left + inset - 1, yBottom, left + inset, yBottom + 1, edgeColor);
            graphics.fill(right - inset, yBottom, right - inset + 1, yBottom + 1, edgeColor);
        }
    }

    public static void roundedOutline(GuiGraphicsExtractor graphics, int left, int top,
                                      int right, int bottom, int radius, int thickness,
                                      int outlineColor, int fillColor) {
        int safeThickness = Math.max(1, Math.min(thickness,
                Math.max(1, Math.min(right - left, bottom - top) / 2)));
        roundedRect(graphics, left, top, right, bottom, radius, outlineColor);
        roundedRect(graphics, left + safeThickness, top + safeThickness,
                right - safeThickness, bottom - safeThickness,
                Math.max(0, radius - safeThickness), fillColor);
    }

    public static void shadow(GuiGraphicsExtractor graphics, int left, int top,
                              int right, int bottom, int radius, int color, int spread) {
        int baseAlpha = color >>> 24;
        int rgb = color & 0xFFFFFF;
        int safeSpread = Math.max(1, spread);
        for (int layer = safeSpread; layer >= 1; layer--) {
            double progress = 1.0D - (layer - 1) / (double) safeSpread;
            int alpha = (int) Math.round(baseAlpha * progress * progress * 0.38D);
            roundedRect(graphics, left - layer, top - layer,
                    right + layer, bottom + layer, radius + layer,
                    (alpha << 24) | rgb);
        }
    }

    /** Kept as a lifecycle hook for callers compiled against the old renderer. */
    public static void close() {
    }
}
