package com.blanoir.moons.client.render.item;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/** Captures native item geometry offscreen. Null pixels report failure without blocking the queue. */
public final class NativeItemIconCapture {
    public static final int SIZE = 32;

    private NativeItemIconCapture() {}

    public static void setQuads(
            net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState layer,
            java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads) {
        layer.prepareQuadList().addAll(quads);
    }

    public static void capture(ItemStack stack, Consumer<byte[]> ready) {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        TrackingItemStackRenderState state;
        try {
            state = new TrackingItemStackRenderState();
            client.getItemModelResolver()
                    .updateForTopItem(
                            state, stack, ItemDisplayContext.GUI, client.level, client.player, 0);
        } catch (RuntimeException failure) {
            ready.accept(null);
            return;
        }
        capture(state, ready);
    }

    public static void capture(TrackingItemStackRenderState state, Consumer<byte[]> ready) {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        var result = new NativeIconCaptureResult(ready);
        try {
            var target =
                    new com.mojang.blaze3d.pipeline.TextureTarget(
                            "Moons item icon", SIZE, SIZE, true);
            result.own((AutoCloseable) target::destroyBuffers);
            var ortho =
                    result.own(
                            new net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer(
                                    "Moons item icon", -1000, 1000, true));
            var allocator = result.own(new com.mojang.blaze3d.vertex.ByteBufferBuilder(786432));
            var buffers = net.minecraft.client.renderer.MultiBufferSource.immediate(allocator);
            var storage = new net.minecraft.client.renderer.SubmitNodeStorage();
            var dispatcher =
                    result.own(
                            new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(
                                    storage,
                                    client.getBlockRenderer(),
                                    buffers,
                                    client.getAtlasManager(),
                                    client.renderBuffers().outlineBufferSource(),
                                    buffers,
                                    client.font));
            var projection = RenderSystem.getProjectionMatrixBuffer();
            var projectionType = RenderSystem.getProjectionType();
            var colorOutput = RenderSystem.outputColorTextureOverride;
            var depthOutput = RenderSystem.outputDepthTextureOverride;
            try {
                var encoder = RenderSystem.getDevice().createCommandEncoder();
                encoder.clearColorAndDepthTextures(
                        target.getColorTexture(), 0, target.getDepthTexture(), 1);
                RenderSystem.setProjectionMatrix(
                        ortho.getBuffer(SIZE, SIZE),
                        com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
                RenderSystem.outputColorTextureOverride = target.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
                client.gameRenderer
                        .getLighting()
                        .setupFor(
                                state.usesBlockLight()
                                        ? com.mojang.blaze3d.platform.Lighting.Entry.ITEMS_3D
                                        : com.mojang.blaze3d.platform.Lighting.Entry.ITEMS_FLAT);
                var pose = new com.mojang.blaze3d.vertex.PoseStack();
                pose.translate(SIZE / 2f, SIZE / 2f, 0);
                pose.scale(SIZE, -SIZE, SIZE);
                state.submit(
                        pose,
                        storage,
                        0xF000F0,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        0);
                dispatcher.renderAllFeatures();
                buffers.endBatch();
                dispatcher.endFrame();
            } finally {
                RenderSystem.setProjectionMatrix(projection, projectionType);
                RenderSystem.outputColorTextureOverride = colorOutput;
                RenderSystem.outputDepthTextureOverride = depthOutput;
                client.gameRenderer
                        .getLighting()
                        .setupFor(com.mojang.blaze3d.platform.Lighting.Entry.LEVEL);
            }
            var buffer =
                    result.own(
                            RenderSystem.getDevice()
                                    .createBuffer(
                                            () -> "Moons icon readback",
                                            9,
                                            (long) SIZE * SIZE * 4));
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(
                    target.getColorTexture(),
                    buffer,
                    0L,
                    () -> {
                        byte[] pixels = null;
                        try (var mapped = encoder.mapBuffer(buffer, true, false)) {
                            var source = mapped.data();
                            pixels = new byte[SIZE * SIZE * 4];
                            for (int y = 0; y < SIZE; y++)
                                for (int x = 0; x < SIZE * 4; x++)
                                    pixels[y * SIZE * 4 + x] =
                                            source.get((SIZE - 1 - y) * SIZE * 4 + x);
                        } catch (RuntimeException failure) {
                            pixels = null;
                        }
                        result.complete(pixels);
                    },
                    0);
        } catch (RuntimeException failure) {
            result.complete(null);
        }
    }
}
