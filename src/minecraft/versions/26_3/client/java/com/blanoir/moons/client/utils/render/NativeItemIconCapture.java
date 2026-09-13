package com.blanoir.moons.client.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
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
            var buffer =
                    result.own(
                            RenderSystem.getDevice()
                                    .createBuffer(
                                            () -> "Moons icon readback",
                                            9,
                                            (long) SIZE * SIZE * 4));
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(
                    slot.textureView().texture(),
                    buffer,
                    0L,
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
                    },
                    0);
        } catch (RuntimeException failure) {
            result.complete(null);
        }
    }
}
