package com.blanoir.moons.client.render;

import com.blanoir.moons.client.render.world.OverlayGeometry;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.function.Consumer;

/** Direct 1.8 OpenGL geometry backend with balanced host state. */
public final class WorldOverlayRenderer {
    private WorldOverlayRenderer() {}

    public static void batch(int primitive, Consumer<LegacyVertexConsumer> draw) {
        int originalFramebuffer = GL11.glGetInteger(0x8CA6);
        var target = VisualRenderTargets.worldTarget(Minecraft.getMinecraft());
        if (target != null) target.bindFramebuffer(false);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE2);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(1f);
            GL11.glBegin(primitive);
            try {
                draw.accept(new LegacyVertexConsumer());
            } finally {
                GL11.glEnd();
            }
        } finally {
            GL11.glPopAttrib();
            net.minecraft.client.renderer.OpenGlHelper.glBindFramebuffer(
                    net.minecraft.client.renderer.OpenGlHelper.GL_FRAMEBUFFER, originalFramebuffer);
        }
    }

    public static void render(
            Minecraft client, LegacyPoseStack matrices, List<ColoredBox> boxes, String label) {
        if (boxes.isEmpty()) return;
        batch(
                GL11.GL_QUADS,
                b -> {
                    for (var box : boxes)
                        OverlayGeometry.renderFilledBox(matrices.last().pose(), b, box);
                });
    }

    public static void renderPins(
            Minecraft client, LegacyPoseStack matrices, List<ColoredPin> pins, String label) {
        if (pins.isEmpty()) return;
        batch(
                GL11.GL_QUADS,
                b -> {
                    for (var pin : pins) OverlayGeometry.renderPin(matrices.last().pose(), b, pin);
                });
    }

    public static void renderStyled(
            Minecraft client, LegacyPoseStack matrices, List<ColoredBox> boxes, String label) {
        if (boxes.isEmpty()) return;
        batch(
                GL11.GL_QUADS,
                b -> {
                    for (var box : boxes)
                        OverlayGeometry.renderSoftFill(matrices.last().pose(), b, box);
                });
        batch(
                GL11.GL_LINES,
                b -> {
                    for (var box : boxes)
                        OverlayGeometry.renderOutlineBox(matrices.last().pose(), b, box);
                });
    }

    public static void close() {
        WorldLabelRenderer.close();
    }

    public record ColoredBox(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float red,
            float green,
            float blue,
            float alpha) {}

    public record ColoredPin(
            float x,
            float y,
            float z,
            float height,
            float width,
            float red,
            float green,
            float blue,
            float alpha) {}
}
