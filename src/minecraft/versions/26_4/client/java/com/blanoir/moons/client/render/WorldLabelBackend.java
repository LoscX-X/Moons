package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.GameAccess;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.resources.Identifier;

import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/** Version boundary for the shared Skia atlas and its through-wall billboard batch. */
final class WorldLabelBackend {
    private static final RenderPipeline PIPELINE = pipeline();
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static StagedVertexBuffer buffer;

    private WorldLabelBackend() {}

    private static RenderPipeline pipeline() {
        var builder =
                RenderPipeline.builder()
                        .withLocation(
                                Identifier.fromNamespaceAndPath("moons", "pipeline/world_labels"))
                        .withVertexShader("core/position_tex_color")
                        .withFragmentShader("core/position_tex_color")
                        .withBindGroupLayout(
                                BindGroupLayout.builder()
                                        .withUniform(
                                                "DynamicTransforms", UniformType.UNIFORM_BUFFER)
                                        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                                        .build())
                        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                        .withPrimitiveTopology(PrimitiveTopology.QUADS)
                        .withCull(false)
                        .withColorTargetState(
                                new ColorTargetState(
                                        BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA));
        builder.withDepthStencilState(Optional.empty());
        return GameAccess.registerPipeline(builder.build());
    }

    static void draw(
            Minecraft client,
            List<WorldLabelAtlas.Upload> uploads,
            WorldLabelFont font,
            Consumer<VertexConsumer> writer) {
        var target = VisualRenderTargets.worldTarget(client);
        if (target == null || target.getColorTextureView() == null) return;
        if (texture == null) {
            texture =
                    RenderSystem.getDevice()
                            .createTexture(
                                    "Moons world text atlas",
                                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                                    GpuFormat.RGBA8_UNORM,
                                    WorldLabelAtlas.SIZE,
                                    WorldLabelAtlas.SIZE,
                                    1,
                                    1);
            view = RenderSystem.getDevice().createTextureView(texture);
        }
        for (var upload : uploads) {
            try (NativeImage image =
                    new NativeImage(upload.getWidth(), upload.getHeight(), false)) {
                MemoryUtil.memByteBuffer(image.getPointer(), upload.getRgba().length)
                        .put(upload.getRgba());
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToTexture(texture, image, 0, 0, upload.getX(), upload.getY());
            }
        }
        if (buffer == null) buffer = new StagedVertexBuffer(() -> "Moons world labels", 131_072);
        try {
            var draw =
                    buffer.appendDraw(
                            DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, null);
            writer.accept(buffer.getVertexBuilder(draw));
            buffer.upload();
            var info = buffer.getExecuteInfo(draw);
            if (info == null) return;
            RenderSystem.resizeAllAutoStorageIndexBuffers();
            var transforms =
                    RenderSystem.getDynamicUniforms()
                            .writeTransform(RenderSystem.getModelViewMatrixCopy());
            try (var pass =
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(
                                    () -> "Moons world labels",
                                    target.getColorTextureView(),
                                    Optional.empty(),
                                    null,
                                    OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setUniform(
                        "Sampler0",
                        view,
                        RenderSystem.getSamplerCache()
                                .getClampToEdge(
                                        font == WorldLabelFont.MINECRAFT
                                                ? FilterMode.NEAREST
                                                : FilterMode.LINEAR));
                pass.setVertexBuffer(0, info.vertexBuffer().slice());
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            if (buffer != null) buffer.endFrame();
        }
    }

    static void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }
}
