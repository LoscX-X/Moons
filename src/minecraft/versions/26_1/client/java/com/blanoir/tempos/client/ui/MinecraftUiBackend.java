package com.blanoir.tempos.client.ui;

import com.blanoir.tempos.client.ui.paint.UiBackend;
import com.blanoir.tempos.client.ui.paint.UiBatch;
import com.blanoir.tempos.client.ui.paint.UiShaders;
import com.blanoir.tempos.client.ui.paint.UiTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/** Draws retained UI triangles on a caller-owned render target. */
public final class MinecraftUiBackend implements UiBackend {
    private static final OptionalInt TRANSPARENT = OptionalInt.of(0);
    private static final Identifier SDF = Identifier.fromNamespaceAndPath("tempos", "ui/shape");
    private static final Identifier GLYPH = Identifier.fromNamespaceAndPath("tempos", "ui/glyph");
    private final Supplier<RenderTarget> target;
    private final UiShaders shaders = new UiShaders(false);
    private final ShaderSource source = (id, type) ->
            SDF.equals(id) ? (type == ShaderType.VERTEX ? shaders.vertex() : shaders.fragment())
                    : GLYPH.equals(id) && type == ShaderType.FRAGMENT ? shaders.glyph() : null;
    private boolean prepared, glyphPrepared;
    private final RenderPipeline sdf;
    private final RenderPipeline textured;
    private GpuBuffer vertices, transforms;
    private GpuBufferSlice uniforms, accentUniforms;
    private GpuBufferSlice uploadView;
    private int uploadLength;
    private UiBatch uploaded;
    private long uploadedRevision;

    public MinecraftUiBackend(Supplier<RenderTarget> target) {
        this.target = target;
        VertexFormat format = format();
        sdf = pipeline(format, false);
        textured = pipeline(format, true);
    }

    private static VertexFormat format() {
        return VertexFormat.builder()
                .add("Position", VertexFormatElement.POSITION)
                .add("UV0", VertexFormatElement.UV0)
                .add("Color", VertexFormatElement.COLOR)
                .add("Shape", float4(0)).add("Details", float4(1)).build();
    }

    private static VertexFormatElement float4(int index) {
        synchronized (VertexFormatElement.class) {
            int free = -1;
            for (int i = 0; i < VertexFormatElement.MAX_COUNT; i++) {
                VertexFormatElement e = VertexFormatElement.byId(i);
                if (e == null) {
                    if (free < 0) free = i;
                } else if (e.index() == index && e.type() == VertexFormatElement.Type.FLOAT
                        && e.count() == 4 && !e.normalized()) {
                    return e;
                }
            }
            if (free < 0) throw new IllegalStateException("No free UI vertex format element");
            return VertexFormatElement.register(free, index, VertexFormatElement.Type.FLOAT, false, 4);
        }
    }

    private static RenderPipeline pipeline(VertexFormat format, boolean image) {
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("tempos", image ? "pipeline/ui_textured" : "pipeline/ui_sdf"))
                .withVertexFormat(format, VertexFormat.Mode.TRIANGLES)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false).withDepthStencilState(Optional.empty());
        builder.withVertexShader(SDF).withFragmentShader(image ? GLYPH : SDF);
        builder.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER);
        if (image) builder.withSampler("Sampler0");
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
        if (!prepared) {
            if (!device.precompilePipeline(sdf, source).isValid()) throw new IllegalStateException("Could not compile Tempos UI shapes");
            prepared = true;
        }
        if (hasImage && !glyphPrepared) {
            if (!device.precompilePipeline(textured, source).isValid()) throw new IllegalStateException("Could not compile Tempos UI glyphs");
            glyphPrepared = true;
        }
        if (transforms == null) {
            try (var stack = MemoryStack.stackPush()) {
                var block = stack.calloc(UiShaders.IDENTITY_BYTES);
                UiShaders.writeIdentityTransforms(block, false);
                transforms = device.createBuffer(() -> "Tempos UI transforms",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, block);
                uniforms = transforms.slice();
                accentUniforms = transforms.slice(UiShaders.accentOffset(false), 16);
            }
        }
        if (shaders.updateAccent(batch.accent())) encoder.writeToBuffer(accentUniforms, shaders.accentUniform());
        try (var pass = encoder.createRenderPass(() -> "Tempos UI", output.getColorTextureView(), clear ? TRANSPARENT : OptionalInt.empty())) {
            pass.setVertexBuffer(0, vertices);
            for (int i = 0; i < batch.rangeCount(); i++) {
                UiBatch.Range range = batch.range(i);
                if (range.texture() instanceof Texture texture) {
                    pass.setPipeline(textured);
                    pass.bindTexture("Sampler0", texture.view, RenderSystem.getSamplerCache().getClampToEdge(texture.texture == null ? FilterMode.NEAREST : FilterMode.LINEAR));
                } else {
                    pass.setPipeline(sdf);
                }
                pass.setUniform("DynamicTransforms", uniforms);
                pass.enableScissor(range.left(), batch.height() - range.bottom(), range.right() - range.left(), range.bottom() - range.top());
                pass.draw(range.first(), range.count());
            }
        }
    }

    @Override
    public UiTexture createTexture(int width, int height) { return new Texture(width, height); }

    private final java.util.Map<Identifier, Texture> borrowed = new java.util.HashMap<>();

    @Override
    public UiTexture borrowGameTexture(Identifier id) {
        var source = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(id);
        var view = source.getTextureView();
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
                    TextureFormat.RGBA8, width, height, 1, 1);
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
                    NativeImage.Format.RGBA, 0, 0, x, y, width, height);
            contentRevision++;
        }

        @Override public void close() { if (texture != null) { view.close(); texture.close(); } }
    }

    @Override
    public void close() {
        borrowed.clear();
        if (vertices != null) vertices.close();
        if (transforms != null) transforms.close();
        vertices = transforms = null;
        prepared = glyphPrepared = false;
        uniforms = null;
        uploadView = null;
        uploaded = null;
    }
}
