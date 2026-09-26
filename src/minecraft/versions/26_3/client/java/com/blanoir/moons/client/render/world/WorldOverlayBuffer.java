package com.blanoir.moons.client.render.world;

import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.render.VisualRenderTargets;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

public final class WorldOverlayBuffer {
    private static StagedVertexBuffer buffer;

    private WorldOverlayBuffer() {}

    public static synchronized void draw(
            Minecraft client,
            RenderPipeline pipeline,
            String label,
            Consumer<VertexConsumer> writer) {
        draw(client, pipeline, label, writer, true);
    }

    /** Overlay fills are intentionally faint; avoid sorting thousands of faces every frame. */
    public static synchronized void drawUnsorted(
            Minecraft client,
            RenderPipeline pipeline,
            String label,
            Consumer<VertexConsumer> writer) {
        draw(client, pipeline, label, writer, false);
    }

    private static void draw(
            Minecraft client,
            RenderPipeline pipeline,
            String label,
            Consumer<VertexConsumer> writer,
            boolean sortQuads) {
        var mainTarget = VisualRenderTargets.worldTarget(client);
        if (mainTarget == null) return;
        var colorView = mainTarget.getColorTextureView();
        var format = pipeline.getVertexFormatBinding(0);
        if (colorView == null || format == null) return;
        if (buffer == null) {
            buffer = new StagedVertexBuffer(() -> MoonsConfig.MOD_ID + " world overlay", 262_144);
        }
        try {
            PrimitiveTopology topology = pipeline.getPrimitiveTopology();
            VertexSorting sorting =
                    sortQuads && topology == PrimitiveTopology.QUADS
                            ? RenderSystem.getProjectionType().vertexSorting()
                            : null;
            StagedVertexBuffer.Draw draw = buffer.appendDraw(format, topology, sorting);
            writer.accept(buffer.getVertexBuilder(draw));
            buffer.upload();

            StagedVertexBuffer.ExecuteInfo info = buffer.getExecuteInfo(draw);
            if (info != null) {
                GpuBufferSlice transforms =
                        RenderSystem.getDynamicUniforms()
                                .writeTransform(RenderSystem.getModelViewMatrixCopy());
                try (RenderPass pass =
                        RenderSystem.getDevice()
                                .createCommandEncoder()
                                .createRenderPass(
                                        () -> MoonsConfig.MOD_ID + " " + label,
                                        colorView,
                                        Optional.empty(),
                                        mainTarget.getDepthTextureView(),
                                        OptionalDouble.empty())) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.setVertexBuffer(0, info.vertexBuffer().slice());
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                }
            }
        } catch (RuntimeException | Error failure) {
            // A partial vertex writer must not poison the shared batch for later modules.
            close();
            throw failure;
        } finally {
            if (buffer != null) buffer.endFrame();
        }
    }

    public static synchronized void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
