package com.blanoir.moons.client.module.impl.render;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Uploads bundled trim images directly; no pack installation or atlas reload. */
final class TrimTextures {
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private TrimTextures() {}

    static Identifier texture(Minecraft client, String pattern, boolean leggings, String palette) {
        String layer = leggings ? "humanoid_leggings" : "humanoid";
        String key = layer + "/" + pattern + "/" + palette;
        Identifier cached = TEXTURES.get(key);
        if (cached != null || FAILED.contains(key)) return cached;
        Identifier id = Identifier.fromNamespaceAndPath("moons", "dynamic/trims/" + key);
        try (InputStream input =
                TrimTextures.class.getResourceAsStream(
                        "/assets/civilization/textures/trims/entity/"
                                + layer
                                + "/"
                                + pattern
                                + "_trim.png")) {
            if (input == null) throw new IOException("Missing bundled trim " + pattern);
            NativeImage pixels = NativeImage.read(input);
            DynamicTexture texture = null;
            try {
                applyPalette(client, pixels, palette);
                texture = new DynamicTexture(() -> "Moons Trim " + key, pixels);
                client.getTextureManager().register(id, texture);
                TEXTURES.put(key, id);
                return id;
            } catch (IOException | RuntimeException failure) {
                if (texture != null) texture.close();
                else pixels.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            FAILED.add(key);
            System.err.println(
                    "[client] Failed to load Trim texture " + key + ": " + failure.getMessage());
            return null;
        }
    }

    private static void applyPalette(Minecraft client, NativeImage pixels, String palette)
            throws IOException {
        try (NativeImage source = readPalette(client, "trim_palette");
                NativeImage target = readPalette(client, palette)) {
            int[] from = source.getPixels();
            int[] to = target.getPixels();
            if (from.length != to.length)
                throw new IOException("Trim palette sizes differ: " + palette);
            Map<Integer, Integer> colors = new HashMap<>();
            for (int index = 0; index < from.length; index++) {
                if ((from[index] >>> 24) != 0) colors.put(from[index] & 0xFFFFFF, to[index]);
            }
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int color = pixels.getPixel(x, y);
                    int alpha = color >>> 24;
                    Integer replacement = colors.get(color & 0xFFFFFF);
                    if (alpha != 0 && replacement != null) {
                        int mappedAlpha = alpha * (replacement >>> 24) / 255;
                        pixels.setPixel(x, y, mappedAlpha << 24 | replacement & 0xFFFFFF);
                    }
                }
            }
        }
    }

    private static NativeImage readPalette(Minecraft client, String palette) throws IOException {
        Identifier id =
                Identifier.withDefaultNamespace(
                        "textures/trims/color_palettes/" + palette + ".png");
        try (InputStream input = client.getResourceManager().open(id)) {
            return NativeImage.read(input);
        }
    }

    static void close(Minecraft client) {
        for (Identifier id : TEXTURES.values()) client.getTextureManager().release(id);
        TEXTURES.clear();
        FAILED.clear();
    }
}
