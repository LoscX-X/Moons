package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.MoonsConfig;
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
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Through-walls box renderer used by the LiquidBounce-style Backtrack ESP.
 *
 * <p>Unlike {@link WorldOverlayRenderer}, boxes can be rotated by the current pose stack: the
 * eight AABB corners are transformed into world space before the faces and the
 * outline edges are uploaded.
 */
public final class BacktrackRenderer {
    private static final RenderPipeline FILLED_PIPELINE =
            registerPipeline("pipeline/moons_backtrack_filled", VertexFormat.Mode.QUADS);
    private static final RenderPipeline LINE_PIPELINE =
            registerPipeline("pipeline/moons_backtrack_lines", VertexFormat.Mode.LINES);

    private static final ByteBufferBuilder ALLOCATOR =
            new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    private static final int[][] FACES = {
        {0, 1, 3, 2},
        {4, 5, 7, 6},
        {0, 1, 5, 4},
        {2, 3, 7, 6},
        {0, 2, 6, 4},
        {1, 3, 7, 5}
    };

    private static final int[][] EDGES = {
        {0, 1}, {1, 3}, {3, 2}, {2, 0},
        {4, 5}, {5, 7}, {7, 6}, {6, 4},
        {0, 4}, {1, 5}, {3, 7}, {2, 6}
    };

    private static MappableRingBuffer vertexBuffer;

    private BacktrackRenderer() {}

    public static void renderBox(
            PoseStack matrices, AABB box, int fillArgb, int outlineArgb, String label) {
        Minecraft client = Minecraft.getInstance();
        if (alpha(fillArgb) == 0 && alpha(outlineArgb) == 0) {
            return;
        }

        Vector4f[] corners = transformedCorners(matrices.last().pose(), box);

        if (alpha(fillArgb) > 0) {
            drawBoxMesh(
                    client,
                    FILLED_PIPELINE,
                    List.<Vector4f[]>of(corners),
                    FACES,
                    fillArgb,
                    label + " fill");
        }

        if (alpha(outlineArgb) > 0) {
            drawBoxMesh(
                    client,
                    LINE_PIPELINE,
                    List.<Vector4f[]>of(corners),
                    EDGES,
                    outlineArgb,
                    label + " outline");
        }
    }

    /** Collects several transformed boxes into one fill and one outline GPU submission. */
    public static final class BoxBatch {
        private final List<Vector4f[]> boxes = new ArrayList<>();

        public void add(PoseStack matrices, AABB box) {
            boxes.add(transformedCorners(matrices.last().pose(), box));
        }

        public void render(int fillArgb, int outlineArgb, String label) {
            Minecraft client = Minecraft.getInstance();
            if (client == null || boxes.isEmpty()) return;
            if (alpha(fillArgb) > 0) {
                drawBoxMesh(client, FILLED_PIPELINE, boxes, FACES, fillArgb, label + " fill");
            }
            if (alpha(outlineArgb) > 0) {
                drawBoxMesh(client, LINE_PIPELINE, boxes, EDGES, outlineArgb, label + " outline");
            }
        }
    }

    private static Vector4f[] transformedCorners(Matrix4fc transform, AABB box) {
        Vector4f[] corners = new Vector4f[8];
        float[][] rawCorners = getRawCorners(box);

        for (int i = 0; i < rawCorners.length; i++) {
            Vector4f corner =
                    new Vector4f(rawCorners[i][0], rawCorners[i][1], rawCorners[i][2], 1.0f);
            transform.transform(corner);
            corners[i] = corner;
        }

        return corners;
    }

    private static float @NonNull [][] getRawCorners(AABB box) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        float[][] rawCorners = {
            {minX, minY, minZ},
            {maxX, minY, minZ},
            {minX, maxY, minZ},
            {maxX, maxY, minZ},
            {minX, minY, maxZ},
            {maxX, minY, maxZ},
            {minX, maxY, maxZ},
            {maxX, maxY, maxZ}
        };
        return rawCorners;
    }

    private static void drawBoxMesh(
            Minecraft client,
            RenderPipeline pipeline,
            List<Vector4f[]> boxes,
            int[][] indices,
            int color,
            String label) {
        BufferBuilder buffer =
                new BufferBuilder(
                        ALLOCATOR, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());

        float red = ((color >>> 16) & 0xFF) / 255.0f;
        float green = ((color >>> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        float alpha = alpha(color) / 255.0f;

        for (Vector4f[] corners : boxes) {
            for (int[] face : indices) {
                for (int cornerIndex : face) {
                    Vector4f corner = corners[cornerIndex];
                    buffer.addVertex(IDENTITY, corner.x, corner.y, corner.z)
                            .setColor(red, green, blue, alpha);
                }
            }
        }

        drawBuiltBuffer(client, buffer, pipeline, label);
    }

    private static void drawBuiltBuffer(
            Minecraft client, BufferBuilder buffer, RenderPipeline pipeline, String label) {
        try (MeshData builtBuffer = buffer.buildOrThrow()) {
            var mainTarget = MinecraftClientAccess.mainRenderTarget(client);
            var colorView = mainTarget.getColorTextureView();
            if (colorView == null) return;
            MeshData.DrawState drawParameters = builtBuffer.drawState();
            VertexFormat format = drawParameters.format();

            int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

            if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
                if (vertexBuffer != null) {
                    vertexBuffer.close();
                }

                vertexBuffer =
                        new MappableRingBuffer(
                                () -> MoonsConfig.MOD_ID + " " + label,
                                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                                vertexBufferSize);
            }

            CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

            try (GpuBuffer.MappedView mappedView =
                    commandEncoder.mapBuffer(
                            vertexBuffer
                                    .currentBuffer()
                                    .slice(0, builtBuffer.vertexBuffer().remaining()),
                            false,
                            true)) {
                MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
            }

            GpuBuffer vertices = vertexBuffer.currentBuffer();
            GpuBuffer indices;
            VertexFormat.IndexType indexType;

            if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
                builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
                indices =
                        pipeline.getVertexFormat()
                                .uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
                indexType = builtBuffer.drawState().indexType();
            } else {
                RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                        RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());

                indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
                indexType = shapeIndexBuffer.type();
            }

            GpuBufferSlice dynamicTransforms =
                    RenderSystem.getDynamicUniforms()
                            .writeTransform(
                                    RenderSystem.getModelViewMatrix(),
                                    COLOR_MODULATOR,
                                    MODEL_OFFSET,
                                    TEXTURE_MATRIX);

            try (RenderPass renderPass =
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(
                                    () -> MoonsConfig.MOD_ID + " " + label + " rendering",
                                    colorView,
                                    OptionalInt.empty(),
                                    mainTarget.getDepthTextureView(),
                                    OptionalDouble.empty())) {
                renderPass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                renderPass.setVertexBuffer(0, vertices);
                renderPass.setIndexBuffer(indices, indexType);
                renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
            }
        }

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

    private static RenderPipeline registerPipeline(String name, VertexFormat.Mode mode) {
        return GameAccess.registerPipeline(
                RenderPipeline.builder(GameAccess.debugFilledSnippet())
                        .withLocation(Identifier.fromNamespaceAndPath(MoonsConfig.MOD_ID, name))
                        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, mode)
                        .withDepthStencilState(Optional.empty())
                        .build());
    }

    private static int alpha(int argb) {
        return argb >>> 24 & 0xFF;
    }
}
