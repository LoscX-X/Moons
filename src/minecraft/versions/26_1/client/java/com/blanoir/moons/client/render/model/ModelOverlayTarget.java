package com.blanoir.moons.client.render.model;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.render.VisualLayerBlit;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.OutputTarget;

/** Own color/depth storage for captured models, composited only into the visual layer. */
public final class ModelOverlayTarget implements AutoCloseable {
    private final String name;
    private final OutputTarget outputTarget;
    private TextureTarget target;
    private GpuTexture outlineTexture;
    private GpuTextureView outlineView;
    private int uploadedOutlineColor;
    private boolean outlineUploaded;
    private Object device;
    private Object world;
    private boolean preparedThisFrame;

    public ModelOverlayTarget(String name) {
        this.name = name;
        this.outputTarget = new OutputTarget(name, this::getOrCreateTarget);
    }

    public OutputTarget outputTarget() {
        return outputTarget;
    }

    public void beginFrame() {
        // Defer allocation and clearing until a remapped render type requests its target.
        preparedThisFrame = false;
    }

    /**
     * Creates, resizes or clears the target and returns it.  Must be called on the
     * render thread before the remapped render types are drawn.
     */
    public void initAndGet() {
        Minecraft client = Minecraft.getInstance();
        var scene = MinecraftClientAccess.mainRenderTarget(client);
        int width = scene.width;
        int height = scene.height;

        if (target != null && (device != RenderSystem.getDevice() || world != client.level))
            close();
        device = RenderSystem.getDevice();
        world = client.level;
        if (target == null) {
            target = new TextureTarget(name, width, height, true);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        clear();
        preparedThisFrame = true;
    }

    private RenderTarget getOrCreateTarget() {
        if (!preparedThisFrame || target == null) {
            initAndGet();
        }
        return target;
    }

    private void clear() {
        if (target == null) {
            return;
        }
        var color = target.getColorTexture();
        var depth = target.getDepthTexture();
        if (color == null || depth == null) return;
        RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorAndDepthTextures(color, 0, depth, 1.0);
    }

    /**
     * Composites the captured model color into an owned visual target.
     */
    public void composite(RenderTarget destination) {
        if (!preparedThisFrame
                || target == null
                || destination == null
                || target.getColorTextureView() == null) return;
        VisualLayerBlit.draw(target.getColorTextureView(), destination);
    }

    /** The textured body is retained; only this capture is recolored and filtered for its edge. */
    public void composite(RenderTarget destination, int outlineColor) {
        if (!preparedThisFrame
                || target == null
                || destination == null
                || target.getColorTextureView() == null) return;
        composite(destination);
        if ((outlineColor >>> 24) == 0) return;
        VisualLayerBlit.replaceRgb(outlineColor(outlineColor), target);
        if (VisualModelOutline.apply(target))
            VisualLayerBlit.drawStraight(target.getColorTextureView(), destination);
    }

    private GpuTextureView outlineColor(int argb) {
        if (outlineTexture == null) {
            outlineTexture =
                    RenderSystem.getDevice()
                            .createTexture(
                                    name + " outline color",
                                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                                    TextureFormat.RGBA8,
                                    1,
                                    1,
                                    1,
                                    1);
            outlineView = RenderSystem.getDevice().createTextureView(outlineTexture);
        }
        // Alpha is the captured model silhouette, preserved by the RGB-only drawing mask.
        int opaqueColor = argb | 0xFF000000;
        if (!outlineUploaded || uploadedOutlineColor != opaqueColor) {
            try (NativeImage pixel = new NativeImage(1, 1, false)) {
                pixel.setPixel(0, 0, opaqueColor);
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToTexture(outlineTexture, pixel);
            }
            uploadedOutlineColor = opaqueColor;
            outlineUploaded = true;
        }
        return outlineView;
    }

    @Override
    public void close() {
        if (outlineView != null) {
            outlineView.close();
            outlineView = null;
        }
        if (outlineTexture != null) {
            outlineTexture.close();
            outlineTexture = null;
        }
        outlineUploaded = false;
        device = null;
        world = null;
        preparedThisFrame = false;
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
