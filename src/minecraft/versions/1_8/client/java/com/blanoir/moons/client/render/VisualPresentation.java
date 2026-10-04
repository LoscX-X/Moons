package com.blanoir.moons.client.render;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Presents the owned overlay after vanilla's framebuffer blit, then the complete Compose UI. */
public final class VisualPresentation {
    private VisualPresentation() {}

    public static void render() {
        var mc = Minecraft.getMinecraft();
        var target = VisualRenderTargets.worldTarget(mc);
        if (target != null) {
            int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE),
                    program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            try {
                GL20.glUseProgram(0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glDisable(GL11.GL_FOG);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.framebufferTexture);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(
                        GL11.GL_ONE,
                        GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE,
                        GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(1, 1, 1, 1);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0, 0);
                GL11.glVertex2f(-1, -1);
                GL11.glTexCoord2f(1, 0);
                GL11.glVertex2f(1, -1);
                GL11.glTexCoord2f(1, 1);
                GL11.glVertex2f(1, 1);
                GL11.glTexCoord2f(0, 1);
                GL11.glVertex2f(-1, 1);
                GL11.glEnd();
            } finally {
                GL20.glUseProgram(program);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(mode);
                GL11.glPopAttrib();
            }
        }
        com.blanoir.moons.client.ui.compose.ComposeRenderBridge.renderCurrentScreen();
    }

    public static void close() {
        VisualRenderTargets.close();
    }
}
