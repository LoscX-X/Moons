package com.blanoir.moons.client.render.model;

import com.blanoir.moons.client.render.LegacyRenderContext;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.VisualRenderTargets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

import org.lwjgl.opengl.GL11;

/** Replays real entity geometry into an owned capture before compositing its skin and silhouette. */
public final class ModelOverlayRenderer {
    private static Framebuffer capture;
    private static boolean normalEnabled;

    private ModelOverlayRenderer() {}

    public static void setNormalEnabled(boolean enabled) {
        normalEnabled = enabled;
    }

    public static boolean normalEnabled() {
        return normalEnabled;
    }

    public static void render(
            EntityLivingBase entity,
            Vec3 at,
            float partialTick,
            int color,
            float light,
            boolean filled) {
        Minecraft mc = Minecraft.getMinecraft();
        if (VisualModelCapture.active() || mc.theWorld == null) return;
        int framebuffer = GL11.glGetInteger(0x8CA6);
        var cachedState = com.blanoir.moons.client.render.LegacyGlStateCache.capture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (capture == null
                    || capture.framebufferWidth != mc.displayWidth
                    || capture.framebufferHeight != mc.displayHeight) {
                close();
                capture = new Framebuffer(mc.displayWidth, mc.displayHeight, true);
                capture.setFramebufferColor(0, 0, 0, 0);
            }
            capture.framebufferClear();
            capture.bindFramebuffer(false);
            var camera = LegacyRenderContext.capture(partialTick).cameraPosition();
            VisualModelCapture.run(
                    () ->
                            mc.getRenderManager()
                                    .doRenderEntity(
                                            entity,
                                            at.xCoord - camera.xCoord,
                                            at.yCoord - camera.yCoord,
                                            at.zCoord - camera.zCoord,
                                            entity.rotationYaw,
                                            partialTick,
                                            false));
            var overlay = VisualRenderTargets.worldTarget(mc);
            if (overlay != null) overlay.bindFramebuffer(false);
            else OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            VisualModelOutline.apply(capture, color, light, filled);
        } finally {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            GL11.glPopAttrib();
            cachedState.close();
        }
    }

    public static void close() {
        if (capture != null) capture.deleteFramebuffer();
        capture = null;
        VisualModelOutline.close();
    }
}
