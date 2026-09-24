package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

public final class StructureLabelRenderer {
    public record Label(Vec3 position, String text, int color) {}

    private static final float VANILLA_TEXT_SCALE = .025f;
    private static final double DISTANCE_SCALE_START = 8, DISTANCE_SCALE_CAP = 512;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
    private static final StagedVertexBuffer TEXT_BUFFER =
            new StagedVertexBuffer(() -> "moons structure labels", 131_072);
    private static final NameTagFeatureRenderer TEXT_RENDERER = new NameTagFeatureRenderer();

    private StructureLabelRenderer() {}

    public static void render(
            Minecraft client,
            PoseStack matrices,
            List<Label> labels,
            double scale,
            int backgroundAlpha) {
        if (labels.isEmpty()) return;
        Camera camera = MinecraftClientAccess.camera(client);
        List<NameTagFeatureRenderer.Submit> submits = new ArrayList<>();
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
        renderNametagBatch(client, submits);
    }

    private static void drawNametag(
            Minecraft client,
            PoseStack matrices,
            List<NameTagFeatureRenderer.Submit> submits,
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

        Matrix4f pose = new Matrix4f(matrices.last().pose());
        submits.add(
                new NameTagFeatureRenderer.Submit(
                        pose,
                        x,
                        y,
                        text,
                        FULL_BRIGHT_LIGHT,
                        color,
                        background,
                        Font.DisplayMode.SEE_THROUGH));
        submits.add(
                new NameTagFeatureRenderer.Submit(
                        pose, x, y, text, FULL_BRIGHT_LIGHT, color, 0, Font.DisplayMode.NORMAL));

        matrices.popPose();
    }

    private static void renderNametagBatch(
            Minecraft client, List<NameTagFeatureRenderer.Submit> submits) {
        if (submits.isEmpty()) {
            return;
        }
        FeatureFrameContext context =
                new FeatureFrameContext(
                        client.gameRenderer.gameRenderState().optionsRenderState,
                        client.font,
                        client.getModelManager().getBlockStateModelSet(),
                        client.getBlockColors(),
                        client.getTextureManager(),
                        client.getAtlasManager(),
                        client.gameRenderer.lightmap(),
                        TEXT_BUFFER);
        TEXT_RENDERER.beginPrepare(context);
        TEXT_RENDERER.prepareGroup(context, submits, false);
        TEXT_RENDERER.finishPrepare(context);
        TEXT_BUFFER.upload();
        TEXT_RENDERER.executeGroup(context, 0, submits, false);
        TEXT_RENDERER.finishExecute(context);
        TEXT_BUFFER.endFrame();
    }

    public static void close() {
        TEXT_BUFFER.close();
    }
}
