package com.blanoir.moons.client.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/** Captures a vanilla GUI item model, retaining transparency for the Compose picker. */
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
        var state = new TrackingItemStackRenderState();
        client.getItemModelResolver()
                .updateForTopItem(
                        state, stack, ItemDisplayContext.GUI, client.level, client.player, 0);
        capture(state, ready);
    }

    /** Renders prepared geometry using the same native GUI atlas and GPU readback. */
    public static void capture(TrackingItemStackRenderState state, Consumer<byte[]> ready) {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        var atlas = new GuiItemAtlas(client.gameRenderer.featureRenderDispatcher(), SIZE, SIZE);
        var projection = RenderSystem.getProjectionMatrixBuffer();
        var projectionType = RenderSystem.getProjectionType();
        GuiItemAtlas.SlotView slot;
        try {
            slot = atlas.getOrUpdate(state);
        } catch (RuntimeException exception) {
            atlas.close();
            throw exception;
        } finally {
            RenderSystem.setProjectionMatrix(projection, projectionType);
        }
        if (slot == null) {
            atlas.close();
            ready.accept(new byte[SIZE * SIZE * 4]);
            return;
        }
        var device = RenderSystem.getDevice();
        var buffer =
                device.createBuffer(() -> "Moons item icon readback", 9, (long) SIZE * SIZE * 4);
        var encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(
                slot.textureView().texture(),
                buffer,
                0L,
                () -> {
                    try (var mapped = buffer.map(true, false)) {
                        var source = mapped.data();
                        byte[] pixels = new byte[SIZE * SIZE * 4];
                        for (int y = 0; y < SIZE; y++) {
                            for (int x = 0; x < SIZE * 4; x++) {
                                pixels[y * SIZE * 4 + x] =
                                        source.get((SIZE - 1 - y) * SIZE * 4 + x);
                            }
                        }
                        ready.accept(pixels);
                    } finally {
                        buffer.close();
                        atlas.close();
                    }
                },
                0);
    }
}
