package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.access.GameAccess;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class WorldOverlayRenderer {
    private static final RenderPipeline THROUGH_WALLS =
            GameAccess.registerPipeline(RenderPipeline.builder(GameAccess.debugFilledSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath(
                            MoonsConfig.MOD_ID,
                            "pipeline/world_box_highlight_through_walls"
                    ))
                    // DEBUG_FILLED_SNIPPET uses a connected strip topology.
                    // These vertices are emitted as six independent quad faces;
                    // leaving the inherited mode connects adjacent entity boxes
                    // into huge triangles when UhcFinder sees many Nether mobs.
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(Optional.empty())
                    .build()
            );

    private static final ByteBufferBuilder ALLOCATOR =
            new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static MappableRingBuffer vertexBuffer;

    private WorldOverlayRenderer() {
    }

    public static void render(Minecraft client, PoseStack matrices, List<ColoredBox> boxes, String label) {
        if (client == null || boxes.isEmpty()) {
            return;
        }

        BufferBuilder buffer = new BufferBuilder(
                ALLOCATOR,
                THROUGH_WALLS.getVertexFormatMode(),
                THROUGH_WALLS.getVertexFormat()
        );

        Matrix4fc positionMatrix = matrices.last().pose();

        for (ColoredBox box : boxes) {
            renderFilledBox(positionMatrix, buffer, box);
        }

        drawBuiltBuffer(client, buffer, label);
    }

    public static void renderPins(Minecraft client, PoseStack matrices, List<ColoredPin> pins, String label) {
        if (client == null || pins.isEmpty()) {
            return;
        }

        BufferBuilder buffer = new BufferBuilder(
                ALLOCATOR,
                THROUGH_WALLS.getVertexFormatMode(),
                THROUGH_WALLS.getVertexFormat()
        );

        Matrix4fc positionMatrix = matrices.last().pose();

        for (ColoredPin pin : pins) {
            renderPin(positionMatrix, buffer, pin);
        }

        drawBuiltBuffer(client, buffer, label);
    }

    private static void drawBuiltBuffer(Minecraft client, BufferBuilder buffer, String label) {
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer, label);

        draw(client, builtBuffer, drawParameters, vertices, format, label);

        if (vertexBuffer != null) {
            vertexBuffer.rotate();
        }
    }

    public static void close() {
        ALLOCATOR.close();

        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }


    private static void renderPin(Matrix4fc positionMatrix, BufferBuilder buffer, ColoredPin pin) {
        float halfWidth = pin.width() / 2.0f;
        float stemTop = pin.y() + pin.height();
        float capHalfWidth = halfWidth * 1.9f;
        float capHeight = Math.max(pin.width() * 1.6f, 0.05f);

        renderFilledBox(positionMatrix, buffer, new ColoredBox(
                pin.x() - halfWidth,
                pin.y(),
                pin.z() - halfWidth,
                pin.x() + halfWidth,
                stemTop,
                pin.z() + halfWidth,
                pin.red(),
                pin.green(),
                pin.blue(),
                pin.alpha()
        ));
        renderFilledBox(positionMatrix, buffer, new ColoredBox(
                pin.x() - capHalfWidth,
                stemTop - capHeight,
                pin.z() - capHalfWidth,
                pin.x() + capHalfWidth,
                stemTop,
                pin.z() + capHalfWidth,
                pin.red(),
                pin.green(),
                pin.blue(),
                Math.min(1.0f, pin.alpha() + 0.12f)
        ));
    }

    private static void renderFilledBox(Matrix4fc positionMatrix, BufferBuilder buffer, ColoredBox box) {
        float minX = box.minX();
        float minY = box.minY();
        float minZ = box.minZ();
        float maxX = box.maxX();
        float maxY = box.maxY();
        float maxZ = box.maxZ();
        float red = box.red();
        float green = box.green();
        float blue = box.blue();
        float alpha = box.alpha();

        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);

        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);

        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);

        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
    }

    private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer, String label) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }

            vertexBuffer = new MappableRingBuffer(
                    () -> MoonsConfig.MOD_ID + " " + label,
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                    vertexBufferSize
            );
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()),
                false,
                true
        )) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        return vertexBuffer.currentBuffer();
    }

    private static void draw(
            Minecraft client,
            MeshData builtBuffer,
            MeshData.DrawState drawParameters,
            GpuBuffer vertices,
            VertexFormat format,
            String label
    ) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (THROUGH_WALLS.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = THROUGH_WALLS.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                    RenderSystem.getSequentialBuffer(THROUGH_WALLS.getVertexFormatMode());

            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(
                        RenderSystem.getModelViewMatrix(),
                        COLOR_MODULATOR,
                        MODEL_OFFSET,
                        TEXTURE_MATRIX
                );

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> MoonsConfig.MOD_ID + " " + label + " rendering",
                        MinecraftClientAccess.mainRenderTarget(client).getColorTextureView(),
                        OptionalInt.empty(),
                        MinecraftClientAccess.mainRenderTarget(client).getDepthTextureView(),
                        OptionalDouble.empty()
                )) {
            renderPass.setPipeline(THROUGH_WALLS);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    public record ColoredBox(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float red,
            float green,
            float blue,
            float alpha
    ) {
    }

    public record ColoredPin(
            float x,
            float y,
            float z,
            float height,
            float width,
            float red,
            float green,
            float blue,
            float alpha
    ) {
    }
}
