package com.blanoir.moons.client.module.impl.render.chams;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import org.joml.Vector4f;

/** 26.2 offscreen chams target backed by the new render-target blit API. */
public final class ChamsRenderTarget implements AutoCloseable {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);

    private final String name;
    private final OutputTarget outputTarget;
    private TextureTarget target;

    public ChamsRenderTarget(String name) {
        this.name = name;
        this.outputTarget = new OutputTarget(name, this::getOrCreateTarget);
    }

    public OutputTarget outputTarget() {
        return outputTarget;
    }

    public RenderTarget initAndGet() {
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();
        if (target == null) {
            target = new TextureTarget(name, width, height, true, GpuFormat.RGBA8_UNORM);
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
        if (target != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    target.getColorTexture(), TRANSPARENT, target.getDepthTexture(), 1.0
            );
        }
    }

    public void composite(RenderTarget mainTarget) {
        if (target != null && mainTarget != null && target.getColorTextureView() != null) {
            target.blitAndBlendToTexture(mainTarget.getColorTextureView(), mainTarget.getDepthTextureView());
        }
    }

    @Override
    public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
