package com.blanoir.moons.client.module.impl.render.chams;

import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.access.GameAccess;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.resources.Identifier;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * LiquidBounce-style offscreen render target used by Chams.
 *
 * Eligible entity render types are remapped to {@link #outputTarget()} while the
 * level is submitted, then the accumulated color texture is blitted over the main
 * render target at the end of the main pass.  This is what makes the entity visible
 * through walls instead of rendering a translucent duplicate on top of it.
 */
public final class ChamsRenderTarget implements AutoCloseable {
    private static final RenderPipeline BLIT_PIPELINE = GameAccess.registerPipeline(
            RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(MoonsConfig.MOD_ID, "pipeline/chams_blit"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader("core/blit_screen")
                    .withSampler("InSampler")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build()
    );

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

    /**
     * Creates, resizes or clears the target and returns it.  Must be called on the
     * render thread before the remapped render types are drawn.
     */
    public void initAndGet() {
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();

        if (target == null) {
            target = new TextureTarget(name, width, height, true);
            clear();
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        } else {
            clear();
        }
    }

    private RenderTarget getOrCreateTarget() {
        if (target == null) {
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
     * Blits the accumulated chams color texture into the main render target.
     */
    public void composite(RenderTarget mainTarget) {
        if (target == null || mainTarget == null) {
            return;
        }
        var sourceColor = target.getColorTextureView();
        var destinationColor = mainTarget.getColorTextureView();
        if (sourceColor == null || destinationColor == null) return;

        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> ClientBranding.name() + " Chams composite",
                destinationColor,
                OptionalInt.empty(),
                null,
                OptionalDouble.empty()
        )) {
            pass.setPipeline(BLIT_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", sourceColor, sampler);
            // 26.1.2 RenderPass.draw(firstVertex, vertexCount): a fullscreen triangle
            // with an empty vertex format is generated from gl_VertexID, so first=0/count=3.
            pass.draw(0, 3);
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
