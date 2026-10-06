package com.blanoir.moons.client.render.item;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Renders a native 1.8 item into an isolated framebuffer and reads RGBA pixels for Skia. */
public final class NativeItemIconCapture {
    public static final int SIZE = 32;

    private NativeItemIconCapture() {}

    public static void capture(ItemStack stack, Consumer<byte[]> ready) {
        if (stack == null || stack.stackSize <= 0) {
            ready.accept(null);
            return;
        }
        captureGeometry(
                () ->
                        Minecraft.getMinecraft()
                                .getRenderItem()
                                .renderItemAndEffectIntoGUI(stack, 0, 0),
                ready);
    }

    public static void captureGeometry(Runnable geometry, Consumer<byte[]> ready) {
        Minecraft client = Minecraft.getMinecraft();
        int framebuffer = GL11.glGetInteger(0x8CA6);
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        Framebuffer target = null;
        byte[] result = null;
        var cachedState = com.blanoir.moons.client.render.LegacyGlStateCache.capture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(-1);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 16, 16, 0, -1000, 1000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            target = new Framebuffer(SIZE, SIZE, true);
            target.setFramebufferColor(0, 0, 0, 0);
            target.framebufferClear();
            target.bindFramebuffer(true);
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderHelper.enableGUIStandardItemLighting();
            geometry.run();
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            result = new byte[SIZE * SIZE * 4];
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE * 4; x++)
                    result[y * SIZE * 4 + x] = pixels.get((SIZE - 1 - y) * SIZE * 4 + x);
        } finally {
            if (target != null) target.deleteFramebuffer();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            cachedState.close();
        }
        ready.accept(result);
    }
}
