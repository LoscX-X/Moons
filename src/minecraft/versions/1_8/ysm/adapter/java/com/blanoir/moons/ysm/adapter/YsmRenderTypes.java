package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

final class YsmRenderTypes {
    void draw(
            ResourceLocation texture,
            LocalYsmModel.Mesh mesh,
            Matrix4f matrix,
            int light,
            boolean hurt) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D,
                    Minecraft.getMinecraft()
                            .getTextureManager()
                            .getTexture(texture)
                            .getGlTextureId());
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 1f / 255);
            boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            for (LocalYsmModel.Pass pass : mesh.passes()) {
                if (pass.cull()) GL11.glEnable(GL11.GL_CULL_FACE);
                else GL11.glDisable(GL11.GL_CULL_FACE);
                if (pass.translucent()) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL14.glBlendFuncSeparate(
                            GL11.GL_SRC_ALPHA,
                            GL11.GL_ONE_MINUS_SRC_ALPHA,
                            GL11.GL_ONE,
                            GL11.GL_ONE_MINUS_SRC_ALPHA);
                } else GL11.glDisable(GL11.GL_BLEND);
                GL11.glDepthMask(!pass.translucent());
                if (pass.glow() || !lighting) GL11.glDisable(GL11.GL_LIGHTING);
                else GL11.glEnable(GL11.GL_LIGHTING);
                int packed = pass.glow() ? 0xF000F0 : light;
                GL13.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, packed & 65535, packed >>> 16);
                GL11.glColor4f(1, hurt ? .45f : 1, hurt ? .45f : 1, 1);
                YsmVertices.emit(matrix, pass.vertices());
            }
        } finally {
            GL11.glPopAttrib();
            GL13.glActiveTexture(active);
        }
    }
}
