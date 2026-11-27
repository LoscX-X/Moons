package com.blanoir.tempos.client.ui;

import com.blanoir.tempos.client.ui.paint.UiBackend;
import com.blanoir.tempos.client.ui.paint.UiBatch;
import com.blanoir.tempos.client.ui.paint.UiShaders;
import com.blanoir.tempos.client.ui.paint.UiTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.blaze3d.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Supplier;

/** Draws retained UI triangles on a caller-owned render target. */
public final class MinecraftUiBackend implements UiBackend {
    private static final Optional<org.joml.Vector4fc> TRANSPARENT = Optional.of(new org.joml.Vector4f(0, 0, 0, 0));
    private static final Identifier SDF = Identifier.fromNamespaceAndPath("tempos", "ui/shape");
    private static final Identifier GLYPH = Identifier.fromNamespaceAndPath("tempos", "ui/glyph");
    private final Supplier<RenderTarget> target;
    private final UiShaders shaders = new UiShaders(true);
    private CompiledRenderPipeline compiled, compiledGlyph;
    private final ShaderSource source = new ShaderSource() {
        public String getShader(Identifier id, ShaderType type) {
            if (GLYPH.equals(id) && type == ShaderType.FRAGMENT) return shaders.glyph();
            if (!SDF.equals(id)) return null;
            return type == ShaderType.VERTEX ? shaders.vertex() : shaders.fragment();
        }
        public CachedIncludeSource getInclude(Identifier id) { return null; }
        public void close() {}
    };
    private final RenderPipeline sdf;
    private final RenderPipeline textured;
    private GpuBuffer vertices, transforms;
    private GpuBufferSlice uniforms, accentUniforms;
    private GpuBufferSlice uploadView;
    private int uploadLength;
    private GpuBufferSlice vertexView;
    private UiBatch uploaded;
    private long uploadedRevision;

    public MinecraftUiBackend(Supplier<RenderTarget> target) {
        this.target = target;
        VertexFormat format = format();
        sdf = pipeline(format, false);
        textured = pipeline(format, true);
    }

    private static VertexFormat format() {
        return VertexFormat.builder(0)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                .addAttribute("Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("Shape", GpuFormat.RGBA32_FLOAT)
                .addAttribute("Details", GpuFormat.RGBA32_FLOAT)
                .build();
    }

    private static RenderPipeline pipeline(VertexFormat format, boolean image) {
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("tempos", image ? "pipeline/ui_textured" : "pipeline/ui_sdf"))
                .withVertexBinding(0, format).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false).withDepthStencilState(Optional.empty());
        builder.withVertexShader(SDF).withFragmentShader(image ? GLYPH : SDF);
        var layout = BindGroupLayout.builder().withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER);
        if (image) layout.withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER);
        builder.withBindGroupLayout(layout.build());
        return builder.build();
    }

    @Override
    public void draw(UiBatch batch) {
        draw(batch, false);
    }

    @Override
    public boolean tryDrawCleared(UiBatch batch) {
        draw(batch, true);
        return true;
    }

    private void draw(UiBatch batch, boolean clear) {
        RenderSystem.assertOnRenderThread();
        RenderTarget output = target.get();
        if (batch.rangeCount() == 0 || batch.vertexCount() == 0 || output == null || output.getColorTextureView() == null) return;
        if (batch.width() != output.width || batch.height() != output.height) return;
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        if (uploaded != batch || uploadedRevision != batch.revision()) {
            ByteBuffer data = batch.vertices();
            int bytes = data.remaining();
            if (vertices == null || vertices.size() < bytes) {
                var next = device.createBuffer(() -> "Tempos UI vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Math.max(32768L, (long) bytes * 2));
                if (vertices != null) vertices.close();
                vertices = next;
                uploadView = null;
                vertexView = next.slice();
            }
            if (uploadView == null || uploadLength != bytes) {
                uploadView = vertices.slice(0, bytes);
                uploadLength = bytes;
            }
            encoder.writeToBuffer(uploadView, data);
            uploaded = batch;
            uploadedRevision = batch.revision();
        }
        boolean hasImage = false;
        for (int i = 0; i < batch.rangeCount(); i++) hasImage |= batch.range(i).texture() != null;
        if (compiled == null) {
            compiled = RenderSystem.getPipelineBuilder().compilePipeline(sdf, net.minecraft.client.renderer.RenderPassLayouts.COLOR_ONLY.subpass(0), source, Runnable::run).join().finishCompile();
            if (compiled == null) throw new IllegalStateException("Could not compile Tempos UI shapes");
        }
        if (hasImage && compiledGlyph == null) {
            compiledGlyph = RenderSystem.getPipelineBuilder().compilePipeline(textured, net.minecraft.client.renderer.RenderPassLayouts.COLOR_ONLY.subpass(0), source, Runnable::run).join().finishCompile();
            if (compiledGlyph == null) throw new IllegalStateException("Could not compile Tempos UI glyphs");
        }
        if (transforms == null) {
            try (var stack = MemoryStack.stackPush()) {
                var block = stack.calloc(UiShaders.IDENTITY_BYTES);
                UiShaders.writeIdentityTransforms(block, true);
                transforms = device.createBuffer(() -> "Tempos UI transforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, block);
                uniforms = transforms.slice();
                accentUniforms = transforms.slice(UiShaders.accentOffset(true), 16);
            }
        }
        if (shaders.updateAccent(batch.accent())) encoder.writeToBuffer(accentUniforms, shaders.accentUniform());
        try (var pass = encoder.createRenderPass(() -> "Tempos UI", net.minecraft.client.renderer.RenderPassLayouts.COLOR_ONLY, output.getColorTextureView(), clear ? TRANSPARENT : Optional.empty())) {
            pass.setVertexBuffer(0, vertexView);
            for (int i = 0; i < batch.rangeCount(); i++) {
                UiBatch.Range range = batch.range(i);
                if (range.texture() instanceof Texture texture) {
                    pass.setPipeline(compiledGlyph);
                    pass.setUniform("Sampler0", texture.view, RenderSystem.getSamplerCache().getClampToEdge(texture.texture == null ? FilterMode.NEAREST : FilterMode.LINEAR));
                } else {
                    pass.setPipeline(compiled);
                }
                pass.setUniform("DynamicTransforms", uniforms);
                pass.enableScissor(range.left(), batch.height() - range.bottom(),
                        range.right() - range.left(), range.bottom() - range.top());
                pass.draw(range.count(), 1, range.first(), 0);
            }
        }
    }

    @Override
    public UiTexture createTexture(int width, int height) { return new Texture(width, height); }

    private final java.util.Map<Identifier, Texture> borrowed = new java.util.HashMap<>();

    @Override
    public UiTexture borrowGameTexture(Identifier id) {
        var source = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(id);
        var view = source.textureView();
        if (view == null) return null;
        Texture previous = borrowed.get(id);
        if (previous != null && previous.view == view) return previous;
        Texture next = new Texture(view);
        borrowed.put(id, next);
        return next;
    }

    private static final class Texture implements UiTexture {
        final GpuTexture texture;
        final GpuTextureView view;
        private long contentRevision;
        @Override public long revision() { return texture == null ? -1 : contentRevision; }
        Texture(GpuTextureView borrowedView) {
            texture = null;
            view = borrowedView;
        }
        Texture(int width, int height) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture(() -> "Tempos UI atlas", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            try {
                view = device.createTextureView(texture);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        }

        @Override
        public void update(int x, int y, int width, int height, ByteBuffer rgba) {
            if (texture == null) throw new UnsupportedOperationException("Game textures are read-only");
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, rgba,
                    0, 0, x, y, width, height);
            contentRevision++;
        }

        @Override public void close() { if (texture != null) { view.close(); texture.close(); } }
    }

    @Override
    public void close() {
        borrowed.clear();
        if (vertices != null) vertices.close();
        if (transforms != null) transforms.close();
        if (compiled != null) compiled.close();
        if (compiledGlyph != null) compiledGlyph.close();
        compiled = compiledGlyph = null;
        vertices = transforms = null;
        uniforms = null;
        uploadView = null;
        vertexView = null;
        uploaded = null;
    }
}
