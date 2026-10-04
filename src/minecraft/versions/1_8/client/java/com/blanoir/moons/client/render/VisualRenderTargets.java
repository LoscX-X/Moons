package com.blanoir.moons.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;

/** Owns the transparent world overlay, resized with the native display framebuffer. */
public final class VisualRenderTargets {
    private static Framebuffer overlay;
    private static boolean hasFrame;

    private VisualRenderTargets() {}

    public static void beginFrame() {
        Minecraft mc = Minecraft.getMinecraft();
        hasFrame = false;
        if (mc.theWorld == null || mc.displayWidth <= 0 || mc.displayHeight <= 0) return;
        int previous = GL11.glGetInteger(0x8CA6);
        var cachedState = LegacyGlStateCache.capture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (overlay == null
                    || overlay.framebufferWidth != mc.displayWidth
                    || overlay.framebufferHeight != mc.displayHeight) {
                close();
                overlay = new Framebuffer(mc.displayWidth, mc.displayHeight, true);
                overlay.setFramebufferColor(0, 0, 0, 0);
            }
            overlay.framebufferClear();
            hasFrame = true;
        } finally {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previous);
            GL11.glPopAttrib();
            cachedState.close();
        }
    }

    public static Framebuffer worldTarget(Minecraft mc) {
        return hasFrame ? overlay : null;
    }

    public static AutoCloseable bind() {
        int previous = GL11.glGetInteger(0x8CA6);
        if (hasFrame && overlay != null) overlay.bindFramebuffer(false);
        return () -> OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previous);
    }

    public static void close() {
        if (overlay != null) overlay.deleteFramebuffer();
        overlay = null;
        hasFrame = false;
    }
}
