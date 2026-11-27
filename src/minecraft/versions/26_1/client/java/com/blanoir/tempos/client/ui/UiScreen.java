package com.blanoir.tempos.client.ui;

import com.blanoir.tempos.client.ui.host.WidgetScreen;
import com.blanoir.tempos.client.ui.paint.UiBackend;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.WidgetTree;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;

public class UiScreen extends WidgetScreen {

    public UiScreen(String title, WidgetTree tree, Theme theme) { super(title, tree, theme); }

    @Override protected RenderTarget createTarget(int width, int height) {
        return newLayer("Tempos widget layer",width,height);
    }
    public static RenderTarget newLayer(String name,int width,int height) {
        return new TextureTarget(name, width, height, false);
    }

    @Override protected void clearTarget(RenderTarget target) {
        clearLayer(target);
    }
    public static void clearLayer(RenderTarget target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), 0);
    }

    @Override protected void registerLayer(RenderTarget target) {
        registerLayer(LAYER,target);
    }
    public static void registerLayer(net.minecraft.resources.Identifier id,RenderTarget target) {
        var image = new net.minecraft.client.renderer.texture.AbstractTexture() {
            { texture = target.getColorTexture(); textureView = target.getColorTextureView(); }
            @Override public void close() { target.destroyBuffers(); }
        };
        net.minecraft.client.Minecraft.getInstance().getTextureManager().register(id, image);
    }

    @Override protected UiBackend createBackend() { return new MinecraftUiBackend(() -> layer); }

    @Override protected boolean shiftDown() {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), InputConstants.KEY_RSHIFT);
    }

    @Override protected void textFocus(boolean focused) {
        // GLFW delivers committed characters to the current screen.
    }
}
