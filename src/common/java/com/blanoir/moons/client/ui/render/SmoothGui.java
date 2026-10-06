package com.blanoir.moons.client.ui.render;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Native rounded rectangles using reusable white alpha masks and per-draw color.
 * Each shape enters the GUI overlap sorter once, regardless of its corner radius.
 */
public final class SmoothGui {
    private static final Map<Shape, Identifier> MASKS = new HashMap<>();
    private static final int MAX_MASKS = 64;
    private static final long MAX_MASK_PIXELS = 1_048_576L;
    private static long maskPixels;

    private SmoothGui() {}

    public static void roundedRect(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            int right,
            int bottom,
            int radius,
            int color) {
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

        Identifier mask = mask(width, height, r);
        if (mask != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    mask,
                    left,
                    top,
                    0.0F,
                    0.0F,
                    width,
                    height,
                    width,
                    height,
                    color);
            return;
        }

        // Bound GPU memory during arbitrary editor resizing. Already submitted masks
        // stay alive until unload, so they cannot be freed while a frame references them.
        fillSpans(graphics, left, top, right, bottom, r, color);
    }

    private static void fillSpans(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            int right,
            int bottom,
            int r,
            int color) {
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

    private static Identifier mask(int width, int height, int radius) {
        Shape shape = new Shape(width, height, radius);
        Identifier cached = MASKS.get(shape);
        if (cached != null) return cached;
        long pixels = (long) width * height;
        if (MASKS.size() >= MAX_MASKS || pixels > MAX_MASK_PIXELS - maskPixels) return null;
        NativeImage image = new NativeImage(width, height, true);
        for (int y = 0; y < height; y++) {
            int cornerRow = Math.min(y, height - y - 1);
            double edge = 0.0;
            if (cornerRow < radius) {
                double dy = radius - cornerRow - 0.5;
                edge = radius - Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy));
            }
            int inset = (int) Math.ceil(edge);
            for (int x = inset; x < width - inset; x++) image.setPixel(x, y, 0xFFFFFFFF);
            if (inset > 0) {
                int edgeColor = ((int) Math.round(255.0 * (inset - edge)) << 24) | 0xFFFFFF;
                image.setPixel(inset - 1, y, edgeColor);
                image.setPixel(width - inset, y, edgeColor);
            }
        }
        Identifier id =
                Identifier.fromNamespaceAndPath(
                        "moons", "dynamic/gui/rounded_" + width + "_" + height + "_" + radius);
        DynamicTexture texture = null;
        try {
            texture = new DynamicTexture(() -> "Moons rounded GUI mask", image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        } catch (RuntimeException | Error failure) {
            if (texture == null) image.close();
            else texture.close();
            throw failure;
        }
        MASKS.put(shape, id);
        maskPixels += pixels;
        return id;
    }

    public static void close() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Identifier id : MASKS.values()) textures.release(id);
        MASKS.clear();
        maskPixels = 0;
    }

    private record Shape(int width, int height, int radius) {}

    public static void roundedOutline(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            int right,
            int bottom,
            int radius,
            int thickness,
            int outlineColor,
            int fillColor) {
        int safeThickness =
                Math.max(
                        1,
                        Math.min(thickness, Math.max(1, Math.min(right - left, bottom - top) / 2)));
        roundedRect(graphics, left, top, right, bottom, radius, outlineColor);
        roundedRect(
                graphics,
                left + safeThickness,
                top + safeThickness,
                right - safeThickness,
                bottom - safeThickness,
                Math.max(0, radius - safeThickness),
                fillColor);
    }
}
