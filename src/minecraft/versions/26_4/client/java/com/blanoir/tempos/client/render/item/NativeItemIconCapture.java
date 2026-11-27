package com.blanoir.tempos.client.render.item;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.pipeline.RenderPipelines;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.Consumer;

/** Captures native item geometry offscreen. Null pixels report failure without blocking the queue. */
public final class NativeItemIconCapture {
    public static final int SIZE = 32;

    private NativeItemIconCapture() {}

    public static void setQuads(
            net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState layer,
            java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> quads) {
        layer.setQuads(net.minecraft.client.resources.model.geometry.ItemQuads.split(quads));
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
            var atlas =
                    result.own(
                            new GuiItemAtlas(
                                    client.gameRenderer.featureRenderDispatcher(), SIZE, SIZE));
            var projection = RenderSystem.getProjectionMatrixBuffer();
            var projectionType = RenderSystem.getProjectionType();

            GuiItemAtlas.SlotView slot;
            try {
                slot = atlas.getOrUpdate(state);
            } finally {
                RenderSystem.setProjectionMatrix(projection, projectionType);
            }
            if (slot == null) {
                result.complete(null);
                return;
            }
            // GuiItemAtlas allows sampling but does not expose COPY_SRC in 26.4.
            // The game's unblended RGBA blit preserves the transparent edges of this single slot.
            var device = RenderSystem.getDevice();
            var readable = result.own(device.createTexture("Tempos item icon",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1));
            var view = result.own(device.createTextureView(readable));
            var encoder = device.createCommandEncoder();
            try (var pass = encoder.createRenderPass(() -> "Tempos item icon", net.minecraft.client.renderer.RenderPassLayouts.COLOR_ONLY, view, Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT, pass.activeSubpass()));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("InSampler", slot.textureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            var buffer = result.own(device.createBuffer(() -> "Tempos item readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SIZE * SIZE * 4));
            encoder.copyTextureToBuffer(readable, buffer, 0L, 0);
            RenderSystem.queueFencedTask(
                    () -> {
                        byte[] pixels = null;
                        try (var mapped = buffer.map(true, false)) {
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
                    });
        } catch (RuntimeException failure) {
            result.complete(null);
        }
    }
}
