package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.render.item.NativeItemIconCapture;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureMap;

import java.util.function.Consumer;

/** Renders the exact resource-pack blockstate, including blocks without inventory items. */
public final class PluginBlockPreviewModel {
    private PluginBlockPreviewModel() {}

    public static void capture(IBlockState state, Consumer<byte[]> ready) {
        if (state == null) {
            ready.accept(null);
            return;
        }
        NativeItemIconCapture.captureGeometry(
                () -> {
                    var client = Minecraft.getMinecraft();
                    var renderer = client.getBlockRendererDispatcher();
                    var model = renderer.getBlockModelShapes().getModelForState(state);
                    client.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                    GlStateManager.translate(8, 8, 0);
                    GlStateManager.scale(10, -10, 10);
                    GlStateManager.rotate(30, 1, 0, 0);
                    GlStateManager.rotate(45, 0, 1, 0);
                    GlStateManager.translate(-.5, -.5, -.5);
                    renderer.getBlockModelRenderer().renderModelBrightness(model, state, 1, true);
                },
                ready);
    }
}
