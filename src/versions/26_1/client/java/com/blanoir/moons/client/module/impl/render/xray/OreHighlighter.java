package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class OreHighlighter {
    private static final int DEFAULT_RED = 0;
    private static final int DEFAULT_GREEN = 220;
    private static final int DEFAULT_BLUE = 255;
    private static final float BOX_ALPHA = 0.35f;
    private static final int SMALL_BUFFER_SIZE = 2097152;
    private static final double MAX_RENDER_DISTANCE_SQ = 196.0 * 196.0;
    private static final int MAX_RENDER_BLOCKS = 12900;

    private static final RenderPipeline FILLED_THROUGH_WALLS =
            GameAccess.registerPipeline(RenderPipeline.builder(GameAccess.debugFilledSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath(
                            MoonsConfig.MOD_ID,
                            "pipeline/diamond_highlight_through_walls"
                    ))
                    // One ore emits six independent quad faces. Nether gold is
                    // dense enough for the inherited strip topology to join
                    // unrelated blocks into screen-sized pink/red polygons.
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(Optional.empty())
                    .build()
            );

    private static final ByteBufferBuilder ALLOCATOR =
            new ByteBufferBuilder(SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static final BooleanSetting DISPLAY_ENABLED =
            new BooleanSetting.Builder()
                    .name("xray.display.enabled")
                    .defaultValue(true)
                    .build();

    private static int red = DEFAULT_RED;
    private static int green = DEFAULT_GREEN;
    private static int blue = DEFAULT_BLUE;

    private static BufferBuilder buffer;
    private static MappableRingBuffer vertexBuffer;

    private OreHighlighter() {
    }

    public static void init() {
        EventBus.WORLD_RENDER.register("OreHighlighter.worldRender", OreHighlighter::render);
    }

    public static void setColor(int newRed, int newGreen, int newBlue) {
        red = newRed;
        green = newGreen;
        blue = newBlue;
        XrayBlockTarget.DIAMOND.setColor(newRed, newGreen, newBlue);
    }

    public static String getRgbString() {
        return XrayBlockTarget.DIAMOND.rgbString();
    }

    public static void toggleDisplay(Minecraft client) {
        setDisplayEnabled(client, !DISPLAY_ENABLED.get());
    }

    public static void setDisplayEnabled(Minecraft client, boolean newEnabled) {
        DISPLAY_ENABLED.set(newEnabled);
        ClientChat.send(client, "Ore display " + displayStatusText() + ". Cached=" + OreCache.size() + ".");
    }

    public static boolean isDisplayEnabled() {
        return DISPLAY_ENABLED.get();
    }

    public static String displayStatusText() {
        return DISPLAY_ENABLED.get() ? "enabled" : "disabled";
    }

    public static void close() {
        ALLOCATOR.close();

        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    private static void render(WorldRenderEvent context) {
        Minecraft client = Minecraft.getInstance();

        if (!DISPLAY_ENABLED.get() || !OreScanner.isClientWorldReady(client) || OreCache.size() == 0) {
            return;
        }

        List<OreCache.CachedXrayBlock> positions = OreCache.snapshotEntries();

        if (positions.isEmpty()) {
            return;
        }

        PoseStack matrices = context.poseStack();
        Vec3 camera = MinecraftClientAccess.camera(client).position();

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        int rendered = 0;

        for (OreCache.CachedXrayBlock entry : positions) {
            BlockPos pos = entry.pos();

            double dx = pos.getX() + 0.5 - camera.x;
            double dy = pos.getY() + 0.5 - camera.y;
            double dz = pos.getZ() + 0.5 - camera.z;

            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ) {
                continue;
            }

            if (rendered >= MAX_RENDER_BLOCKS) {
                break;
            }

            if (buffer == null) {
                buffer = new BufferBuilder(
                        ALLOCATOR,
                        FILLED_THROUGH_WALLS.getVertexFormatMode(),
                        FILLED_THROUGH_WALLS.getVertexFormat()
                );
            }

            XrayTarget target = entry.target();
            float renderRed = target.red() / 255.0f;
            float renderGreen = target.green() / 255.0f;
            float renderBlue = target.blue() / 255.0f;

            renderFilledBox(
                    matrices.last().pose(),
                    buffer,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    pos.getX() + 1,
                    pos.getY() + 1,
                    pos.getZ() + 1,
                    renderRed,
                    renderGreen,
                    renderBlue,
                    BOX_ALPHA
            );

            rendered++;
        }

        matrices.popPose();

        if (rendered > 0) {
            drawFilledThroughWalls(client, FILLED_THROUGH_WALLS);
        }
    }
    private static void renderFilledBox(Matrix4fc positionMatrix, BufferBuilder buffer, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float red, float green, float blue, float alpha) {

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

    private static void drawFilledThroughWalls(Minecraft client, RenderPipeline pipeline) {
        if (buffer == null) {
            return;
        }

        MeshData builtBuffer = buffer.buildOrThrow();

        try {
            MeshData.DrawState drawParameters = builtBuffer.drawState();
            VertexFormat format = drawParameters.format();

            GpuBuffer vertices = upload(drawParameters, format, builtBuffer);

            draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

            if (vertexBuffer != null) {
                vertexBuffer.rotate();
            }
        } finally {
            builtBuffer.close();
            buffer = null;
        }
    }

    private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }

            vertexBuffer = new MappableRingBuffer(
                    () -> MoonsConfig.MOD_ID + " ore highlighter",
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
            RenderPipeline pipeline,
            MeshData builtBuffer,
            MeshData.DrawState drawParameters,
            GpuBuffer vertices,
            VertexFormat format
    ) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                    RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());

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
                        () -> MoonsConfig.MOD_ID + " ore highlighter rendering",
                        MinecraftClientAccess.mainRenderTarget(client).getColorTextureView(),
                        OptionalInt.empty(),
                        MinecraftClientAccess.mainRenderTarget(client).getDepthTextureView(),
                        OptionalDouble.empty()
                )) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }
    }
}
