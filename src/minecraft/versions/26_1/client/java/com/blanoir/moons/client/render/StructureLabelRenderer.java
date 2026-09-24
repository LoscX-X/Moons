package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

import java.util.List;

public final class StructureLabelRenderer {
    public record Label(Vec3 position, String text, int color) {}

    private static final float VANILLA_TEXT_SCALE = .025f;
    private static final double DISTANCE_SCALE_START = 8, DISTANCE_SCALE_CAP = 512;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

    private StructureLabelRenderer() {}

    public static void render(
            Minecraft client,
            PoseStack matrices,
            List<Label> labels,
            double scale,
            int backgroundAlpha) {
        if (labels.isEmpty()) return;
        Camera camera = MinecraftClientAccess.camera(client);
        MultiBufferSource.BufferSource submits = client.renderBuffers().bufferSource();
        for (Label label : labels) {
            Vec3 relative = label.position().subtract(camera.position());
            drawNametag(
                    client,
                    matrices,
                    submits,
                    camera,
                    relative,
                    Component.literal(label.text()),
                    relative.length(),
                    scale,
                    0xFF000000 | label.color(),
                    backgroundAlpha << 24 | 0x13171B);
        }
        submits.endBatch();
    }

    private static void drawNametag(
            Minecraft client,
            PoseStack matrices,
            MultiBufferSource consumers,
            Camera camera,
            Vec3 renderPos,
            Component text,
            double distance,
            double scale,
            int color,
            int background) {
        Font font = client.font;
        int width = font.width(text);

        float distanceScale =
                (float)
                        Mth.clamp(
                                distance / DISTANCE_SCALE_START,
                                1.0D,
                                DISTANCE_SCALE_CAP / DISTANCE_SCALE_START);

        float finalScale = (float) (VANILLA_TEXT_SCALE * scale * distanceScale);

        matrices.pushPose();

        matrices.translate(renderPos.x, renderPos.y, renderPos.z);

        matrices.mulPose(new Quaternionf(camera.rotation()).rotateY((float) Math.PI));
        matrices.scale(-finalScale, -finalScale, finalScale);

        float x = -width / 2.0F;
        float y = 0.0F;

        font.drawInBatch(
                text,
                x,
                y,
                color,
                true,
                matrices.last().pose(),
                consumers,
                Font.DisplayMode.SEE_THROUGH,
                background,
                FULL_BRIGHT_LIGHT);
        font.drawInBatch(
                text,
                x,
                y,
                color,
                false,
                matrices.last().pose(),
                consumers,
                Font.DisplayMode.NORMAL,
                0,
                FULL_BRIGHT_LIGHT);

        matrices.popPose();
    }

    public static void close() {}
}
