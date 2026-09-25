package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.GameAccess;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** Version boundary for the shared Skia atlas and its two billboard batches. */
final class WorldLabelBackend {
    private static final RenderPipeline THROUGH = pipeline(false);
    private static final RenderPipeline NORMAL = pipeline(true);
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static ByteBufferBuilder allocator;
    private static MappableRingBuffer buffer;

    private WorldLabelBackend() {}

    private static RenderPipeline pipeline(boolean depth) {
        var builder =
                RenderPipeline.builder()
                        .withLocation(
                                "moons:pipeline/world_labels_" + (depth ? "visible" : "through"))
                        .withVertexShader("core/position_tex_color")
                        .withFragmentShader("core/position_tex_color")
                        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                        .withSampler("Sampler0")
                        .withVertexFormat(
                                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                        .withCull(false)
                        .withColorTargetState(
                                new ColorTargetState(
                                        BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA));
        if (depth)
            builder.withDepthStencilState(
                    new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false));
        else builder.withDepthStencilState(Optional.empty());
        return GameAccess.registerPipeline(builder.build());
    }

    static void draw(
            Minecraft client,
            List<WorldLabelAtlas.Upload> uploads,
            boolean depth,
            Consumer<VertexConsumer> writer) {
        var target =
                depth
                        ? VisualRenderTargets.worldTargetWithDepth(client)
                        : VisualRenderTargets.worldTarget(client);
        if (target == null || target.getColorTextureView() == null) return;
        if (texture == null) {
            texture =
                    RenderSystem.getDevice()
                            .createTexture(
                                    "Moons world text atlas",
                                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                                    TextureFormat.RGBA8,
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
                        .writeToTexture(
                                texture,
                                image,
                                0,
                                0,
                                upload.getX(),
                                upload.getY(),
                                upload.getWidth(),
                                upload.getHeight(),
                                0,
                                0);
            }
        }
        if (allocator == null) allocator = new ByteBufferBuilder(131_072);
        var builder =
                new BufferBuilder(
                        allocator, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        writer.accept(builder);
        try (MeshData mesh = builder.build()) {
            if (mesh == null) return;
            int bytes = mesh.vertexBuffer().remaining();
            if (buffer == null || buffer.size() < bytes) {
                if (buffer != null) buffer.close();
                buffer =
                        new MappableRingBuffer(
                                () -> "Moons world label vertices",
                                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                                Math.max(131_072, bytes));
            }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            try (var mapping =
                    encoder.mapBuffer(buffer.currentBuffer().slice(0, bytes), false, true)) {
                MemoryUtil.memCopy(mesh.vertexBuffer(), mapping.data());
            }
            var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            var indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
            var transforms =
                    RenderSystem.getDynamicUniforms()
                            .writeTransform(
                                    RenderSystem.getModelViewMatrix(),
                                    new Vector4f(1),
                                    new Vector3f(),
                                    new Matrix4f());
            try (var pass =
                    encoder.createRenderPass(
                            () -> "Moons world labels",
                            target.getColorTextureView(),
                            OptionalInt.empty(),
                            depth ? target.getDepthTextureView() : null,
                            OptionalDouble.empty())) {
                pass.setPipeline(depth ? NORMAL : THROUGH);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.bindTexture(
                        "Sampler0",
                        view,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setVertexBuffer(0, buffer.currentBuffer());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        } finally {
            if (buffer != null) buffer.rotate();
        }
    }

    static void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        if (allocator != null) {
            allocator.close();
            allocator = null;
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
