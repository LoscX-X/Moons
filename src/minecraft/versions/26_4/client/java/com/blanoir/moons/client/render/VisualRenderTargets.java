package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.Minecraft;

import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Frame-local visual layer. The screenshot source is never used as a color attachment. */
public final class VisualRenderTargets {
    private static RenderTarget world;
    private static Object level;
    private static Object sourceColor;
    private static Object sourceDepth;
    private static Object device;
    private static boolean usedThisFrame;
    private static boolean copiedDepth;

    private VisualRenderTargets() {}

    public static void beginFrame() {
        usedThisFrame = false;
        copiedDepth = false;
        Minecraft client = Minecraft.getInstance();
        if (world != null && (client == null || client.level != level)) close();
    }

    /** Returns the transparent world layer, clearing its color once on first use this frame. */
    public static @Nullable RenderTarget worldTarget(Minecraft client) {
        if (client == null || client.level == null || client.player == null) return null;
        RenderTarget main = MinecraftClientAccess.mainRenderTarget(client);
        if (main.width <= 0 || main.height <= 0 || main.getColorTextureView() == null) return null;
        Object currentDevice = RenderSystem.getDevice();
        if (world != null
                && (level != client.level
                        || sourceColor != main.getColorTexture()
                        || sourceDepth != main.getDepthTexture()
                        || device != currentDevice
                        || world.width != main.width
                        || world.height != main.height)) {
            close();
        }
        if (world == null) {
            world =
                    new TextureTarget(
                            "Moons world visuals",
                            main.width,
                            main.height,
                            GpuFormat.RGBA8_UNORM,
                            main.getDepthTexture() == null
                                    ? null
                                    : main.getDepthTexture().getFormat());
            level = client.level;
            sourceColor = main.getColorTexture();
            sourceDepth = main.getDepthTexture();
            device = currentDevice;
        }
        if (!usedThisFrame) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.clearColorTexture(world.getColorTexture(), new Vector4f());
            usedThisFrame = true;
        }
        return world;
    }

    /** Copies scene depth into the independent layer once; no overlay can write the scene depth. */
    public static @Nullable RenderTarget worldTargetWithDepth(Minecraft client) {
        RenderTarget target = worldTarget(client);
        if (target == null) return null;
        if (!copiedDepth) {
            RenderTarget main = MinecraftClientAccess.mainRenderTarget(client);
            if (main.getDepthTexture() == null || target.getDepthTexture() == null) return null;
            target.copyDepthFrom(main);
            copiedDepth = true;
        }
        return target;
    }

    /** Premultiplied color for final presentation only; null prevents stale-frame composition. */
    public static @Nullable GpuTextureView worldOverlayView(Minecraft client) {
        if (!usedThisFrame || world == null || client == null || client.level != level) return null;
        RenderTarget main = MinecraftClientAccess.mainRenderTarget(client);
        if (main.getColorTexture() != sourceColor
                || main.getDepthTexture() != sourceDepth
                || main.width != world.width
                || main.height != world.height) return null;
        return world.getColorTextureView();
    }

    public static void close() {
        try {
            if (world != null) world.destroyBuffers();
        } finally {
            world = null;
            level = null;
            sourceColor = null;
            sourceDepth = null;
            device = null;
            usedThisFrame = false;
            copiedDepth = false;
        }
    }
}
