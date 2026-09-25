package com.blanoir.moons.client.render;

import com.blanoir.moons.client.ui.compose.ComposeRenderBridge;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.Minecraft;

/** Composites Moons visuals into a display-only copy, never into the screenshot source. */
public final class VisualPresentation {
    private static TextureTarget display;
    private static Object device;
    private static GpuTextureView source;
    private static boolean copied;
    private static boolean failureLogged;

    private VisualPresentation() {}

    /** The returned view replaces only the source of this frame's swapchain blit. */
    public static GpuTextureView present(GpuTextureView base) {
        RenderSystem.assertOnRenderThread();
        if (base == null || base.isClosed() || source != null) return base;
        source = base;
        copied = false;
        try {
            Minecraft client = Minecraft.getInstance();
            var overlay = VisualRenderTargets.worldOverlayView(client);
            if (overlay != null) {
                var target = outputTarget();
                if (target != null) blend(overlay, target, 0, 0, target.width, target.height);
            }
            ComposeRenderBridge.renderCurrentScreen();
            failureLogged = false;
            return copied ? display.getColorTextureView() : base;
        } catch (RuntimeException failure) {
            if (!failureLogged) {
                System.getLogger(VisualPresentation.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Skipping Moons visual presentation",
                                failure);
                failureLogged = true;
            }
            return base;
        } finally {
            source = null;
            copied = false;
        }
    }

    /** Available only while presenting; allocation and base copy are lazy when nothing is drawn. */
    public static RenderTarget outputTarget() {
        if (source == null) return null;
        if (copied) return display;
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        // The Skia/world pipelines currently target RGBA8; unsupported targets stay untouched.
        if (width <= 0
                || height <= 0
                || source.baseMipLevel() != 0
                || source.texture().getFormat() != GpuFormat.RGBA8_UNORM) return null;
        var currentDevice = RenderSystem.getDevice();
        if (display != null && (device != currentDevice || display.getColorTexture().isClosed())) {
            releaseDisplay();
        }
        if (display == null) {
            display =
                    new TextureTarget(
                            "Moons presentation", width, height, GpuFormat.RGBA8_UNORM, null);
            device = currentDevice;
        } else if (display.width != width || display.height != height) {
            display.resize(width, height);
        }
        RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToTexture(
                        source.texture(), display.getColorTexture(), 0, 0, 0, 0, 0, width, height);
        copied = true;
        return display;
    }

    /** Both world overlays and Skia uploads use premultiplied alpha. */
    public static void blend(
            GpuTextureView layer, RenderTarget target, int x, int y, int width, int height) {
        // Reject accidental callers outside the scoped display target; never fall back to main.
        if (source == null || target != display || !copied || width <= 0 || height <= 0) return;
        VisualLayerBlit.draw(layer, target, x, y, width, height);
    }

    public static void close() {
        source = null;
        copied = false;
        releaseDisplay();
    }

    private static void releaseDisplay() {
        var previous = display;
        display = null;
        device = null;
        if (previous != null) previous.destroyBuffers();
    }
}
