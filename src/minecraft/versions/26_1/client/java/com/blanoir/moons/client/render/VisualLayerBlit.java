package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Premultiplied layer composition; callers retain ownership of their source and target. */
public final class VisualLayerBlit {
    private enum Mode {
        PREMULTIPLIED(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA, 15),
        STRAIGHT(BlendFunction.TRANSLUCENT, 15),
        RGB_ONLY(null, 7);

        final BlendFunction blend;
        final int writeMask;
        RenderPipeline pipeline;

        Mode(BlendFunction blend, int writeMask) {
            this.blend = blend;
            this.writeMask = writeMask;
        }
    }

    private VisualLayerBlit() {}

    public static void draw(GpuTextureView source, RenderTarget destination) {
        if (destination != null)
            draw(source, destination, 0, 0, destination.width, destination.height);
    }

    public static void draw(
            GpuTextureView source, RenderTarget destination, int x, int y, int width, int height) {
        draw(source, destination, x, y, width, height, Mode.PREMULTIPLIED);
    }

    /** Post-effect output stores straight color; blending it produces a premultiplied layer. */
    public static void drawStraight(GpuTextureView source, RenderTarget destination) {
        if (destination != null)
            draw(source, destination, 0, 0, destination.width, destination.height, Mode.STRAIGHT);
    }

    /** Paints RGB from the supplied color texture while retaining the destination silhouette. */
    public static void replaceRgb(GpuTextureView color, RenderTarget destination) {
        if (destination != null)
            draw(color, destination, 0, 0, destination.width, destination.height, Mode.RGB_ONLY);
    }

    private static void draw(
            GpuTextureView source,
            RenderTarget destination,
            int x,
            int y,
            int width,
            int height,
            Mode mode) {
        RenderSystem.assertOnRenderThread();
        if (source == null || source.isClosed() || destination == null || width <= 0 || height <= 0)
            return;
        var color = destination.getColorTextureView();
        if (color == null || color.isClosed() || source.texture() == color.texture()) return;
        var main = MinecraftClientAccess.mainRenderTarget(Minecraft.getInstance());
        // A separate wrapper around the main texture is still the screenshot source.
        if (destination == main || color.texture() == main.getColorTexture()) return;
        try (var pass =
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(
                                () -> "Moons visual layer composition",
                                color,
                                java.util.OptionalInt.empty())) {
            pass.setPipeline(pipeline(mode));
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture(
                    "InSampler",
                    source,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.enableScissor(x, y, width, height);
            pass.draw(0, 3);
        }
    }

    private static RenderPipeline pipeline(Mode mode) {
        if (mode.pipeline != null) return mode.pipeline;
        var vanilla = RenderPipelines.ENTITY_OUTLINE_BLIT;
        var builder =
                RenderPipeline.builder()
                        .withLocation(
                                Identifier.fromNamespaceAndPath(
                                        "moons",
                                        "pipeline/visual_composite_"
                                                + mode.name().toLowerCase(java.util.Locale.ROOT)))
                        .withVertexShader(vanilla.getVertexShader())
                        .withFragmentShader(vanilla.getFragmentShader())
                        .withVertexFormat(
                                com.mojang.blaze3d.vertex.DefaultVertexFormat.EMPTY,
                                com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)
                        .withColorTargetState(
                                new ColorTargetState(
                                        Optional.ofNullable(mode.blend), mode.writeMask));
        builder.withSampler("InSampler");
        return mode.pipeline = GameAccess.registerPipeline(builder.build());
    }
}
