package com.blanoir.moons.client.render;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

/** Uploads the shared Skia font atlas and draws textured billboard vertices on LWJGL2. */
public final class WorldLabelPipeline {
    private static int texture;

    private WorldLabelPipeline() {}

    public static void draw(
            Minecraft client,
            List<WorldLabelAtlas.Upload> uploads,
            WorldLabelFont font,
            Consumer<LegacyVertexConsumer> draw) {
        int originalFramebuffer = GL11.glGetInteger(0x8CA6);
        var target = VisualRenderTargets.worldTarget(Minecraft.getMinecraft());
        if (target != null) target.bindFramebuffer(false);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(-1);
        try {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_FOG);
            if (texture == 0) {
                texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        GL11.GL_RGBA8,
                        WorldLabelAtlas.SIZE,
                        WorldLabelAtlas.SIZE,
                        0,
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        (ByteBuffer) null);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MIN_FILTER,
                    font == WorldLabelFont.MINECRAFT ? GL11.GL_NEAREST : GL11.GL_LINEAR);
            GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MAG_FILTER,
                    font == WorldLabelFont.MINECRAFT ? GL11.GL_NEAREST : GL11.GL_LINEAR);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            for (var upload : uploads) {
                ByteBuffer pixels = BufferUtils.createByteBuffer(upload.getRgba().length);
                pixels.put(upload.getRgba()).flip();
                GL11.glTexSubImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        upload.getX(),
                        upload.getY(),
                        upload.getWidth(),
                        upload.getHeight(),
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        pixels);
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glBegin(GL11.GL_QUADS);
            try {
                draw.accept(new LegacyVertexConsumer());
            } finally {
                GL11.glEnd();
            }
        } finally {
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            net.minecraft.client.renderer.OpenGlHelper.glBindFramebuffer(
                    net.minecraft.client.renderer.OpenGlHelper.GL_FRAMEBUFFER, originalFramebuffer);
        }
    }

    public static void close() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = 0;
    }
}
