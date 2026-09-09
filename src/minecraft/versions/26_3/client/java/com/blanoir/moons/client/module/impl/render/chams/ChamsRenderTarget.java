package com.blanoir.moons.client.module.impl.render.chams;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;

import net.minecraft.client.Minecraft;

import org.joml.Vector4f;

/** 26.3 offscreen chams target backed by the new render-target blit API. */
public final class ChamsRenderTarget implements AutoCloseable {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    private final String name;
    private TextureTarget target;

    public ChamsRenderTarget(String name) {
        this.name = name;
    }

    public RenderTarget initAndGet() {
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();
        if (target == null) {
            target =
                    new TextureTarget(
                            name, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        clear();
        return target;
    }

    private RenderTarget getOrCreateTarget() {
        return target == null ? initAndGet() : target;
    }

    private void clear() {
        if (target == null) return;
        var color = target.getColorTexture();
        var depth = target.getDepthTexture();
        if (color == null || depth == null) return;
        RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorAndDepthTextures(color, TRANSPARENT, depth, 0.0);
    }

    public void composite(RenderTarget mainTarget) {
        if (target == null || mainTarget == null || target.getColorTextureView() == null) return;
        var destinationColor = mainTarget.getColorTextureView();
        var destinationDepth = mainTarget.getDepthTextureView();
        if (destinationColor == null || destinationDepth == null) return;
        target.blitAndBlendToTexture(destinationColor, destinationDepth);
    }

    @Override
    public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
