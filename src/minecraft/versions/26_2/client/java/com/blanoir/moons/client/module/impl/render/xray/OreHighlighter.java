package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.render.world.WorldOverlayBuffer;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 26.2 ore overlay using Minecraft's staged GPU buffer model. */
public final class OreHighlighter {
    private static final float BOX_ALPHA = 0.35f;
    private static final double MAX_RENDER_DISTANCE_SQ = 196.0 * 196.0;
    private static final int MAX_RENDER_BLOCKS = 12_900;

    private static final RenderPipeline FILLED_THROUGH_WALLS =
            GameAccess.registerPipeline(
                    RenderPipeline.builder(GameAccess.debugFilledSnippet())
                            .withLocation(
                                    Identifier.fromNamespaceAndPath(
                                            MoonsConfig.MOD_ID,
                                            "pipeline/diamond_highlight_through_walls"))
                            // The writer emits independent faces, not one connected
                            // strip. This is especially visible on dense Nether gold.
                            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                            .withPrimitiveTopology(PrimitiveTopology.QUADS)
                            .withDepthStencilState(Optional.empty())
                            .build());

    private static final BooleanSetting DISPLAY_ENABLED =
            new BooleanSetting.Builder().name("xray.display.enabled").defaultValue(true).build();

    private OreHighlighter() {}

    public static void init() {
        EventBus.WORLD_RENDER.register("OreHighlighter.worldRender", OreHighlighter::render);
    }

    public static void setColor(int newRed, int newGreen, int newBlue) {
        XrayBlockTarget.DIAMOND.setColor(newRed, newGreen, newBlue);
    }

    public static String getRgbString() {
        return XrayBlockTarget.DIAMOND.rgbString();
    }

    public static void setDisplayEnabled(Minecraft client, boolean enabled) {
        DISPLAY_ENABLED.set(enabled);
        ClientChat.send(
                client, "Ore display " + displayStatusText() + ". Cached=" + OreCache.size() + ".");
    }

    public static boolean isDisplayEnabled() {
        return DISPLAY_ENABLED.get();
    }

    public static String displayStatusText() {
        return DISPLAY_ENABLED.get() ? "enabled" : "disabled";
    }

    public static void close() {
        // WorldOverlayBuffer owns the shared 26.2 GPU staging lifecycle.
    }

    private static void render(WorldRenderEvent context) {
        Minecraft client = Minecraft.getInstance();
        if (!DISPLAY_ENABLED.get()
                || !OreScanner.isClientWorldReady(client)
                || OreCache.size() == 0) {
            return;
        }

        Vec3 camera = MinecraftClientAccess.camera(client).position();
        List<OreCache.CachedXrayBlock> visible = new ArrayList<>();
        for (OreCache.CachedXrayBlock entry : OreCache.snapshotEntries()) {
            BlockPos pos = entry.pos();
            double dx = pos.getX() + 0.5 - camera.x;
            double dy = pos.getY() + 0.5 - camera.y;
            double dz = pos.getZ() + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz <= MAX_RENDER_DISTANCE_SQ) {
                visible.add(entry);
                if (visible.size() >= MAX_RENDER_BLOCKS) {
                    break;
                }
            }
        }
        if (visible.isEmpty()) {
            return;
        }

        PoseStack matrices = context.poseStack();
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc pose = new Matrix4f(matrices.last().pose());
        matrices.popPose();

        WorldOverlayBuffer.draw(
                client,
                FILLED_THROUGH_WALLS,
                "ore highlighter rendering",
                builder -> {
                    for (OreCache.CachedXrayBlock entry : visible) {
                        BlockPos pos = entry.pos();
                        XrayTarget target = entry.target();
                        renderFilledBox(
                                pose,
                                builder,
                                pos.getX(),
                                pos.getY(),
                                pos.getZ(),
                                pos.getX() + 1,
                                pos.getY() + 1,
                                pos.getZ() + 1,
                                target.red() / 255.0f,
                                target.green() / 255.0f,
                                target.blue() / 255.0f,
                                BOX_ALPHA);
                    }
                });
    }

    private static void renderFilledBox(
            Matrix4fc pose,
            VertexConsumer b,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float red,
            float green,
            float blue,
            float alpha) {
        vertex(b, pose, x1, y1, z2, red, green, blue, alpha);
        vertex(b, pose, x2, y1, z2, red, green, blue, alpha);
        vertex(b, pose, x2, y2, z2, red, green, blue, alpha);
        vertex(b, pose, x1, y2, z2, red, green, blue, alpha);
        vertex(b, pose, x2, y1, z1, red, green, blue, alpha);
        vertex(b, pose, x1, y1, z1, red, green, blue, alpha);
        vertex(b, pose, x1, y2, z1, red, green, blue, alpha);
        vertex(b, pose, x2, y2, z1, red, green, blue, alpha);
        vertex(b, pose, x1, y1, z1, red, green, blue, alpha);
        vertex(b, pose, x1, y1, z2, red, green, blue, alpha);
        vertex(b, pose, x1, y2, z2, red, green, blue, alpha);
        vertex(b, pose, x1, y2, z1, red, green, blue, alpha);
        vertex(b, pose, x2, y1, z2, red, green, blue, alpha);
        vertex(b, pose, x2, y1, z1, red, green, blue, alpha);
        vertex(b, pose, x2, y2, z1, red, green, blue, alpha);
        vertex(b, pose, x2, y2, z2, red, green, blue, alpha);
        vertex(b, pose, x1, y2, z2, red, green, blue, alpha);
        vertex(b, pose, x2, y2, z2, red, green, blue, alpha);
        vertex(b, pose, x2, y2, z1, red, green, blue, alpha);
        vertex(b, pose, x1, y2, z1, red, green, blue, alpha);
        vertex(b, pose, x1, y1, z1, red, green, blue, alpha);
        vertex(b, pose, x2, y1, z1, red, green, blue, alpha);
        vertex(b, pose, x2, y1, z2, red, green, blue, alpha);
        vertex(b, pose, x1, y1, z2, red, green, blue, alpha);
    }

    private static void vertex(
            VertexConsumer builder,
            Matrix4fc pose,
            float x,
            float y,
            float z,
            float red,
            float green,
            float blue,
            float alpha) {
        builder.addVertex(pose, x, y, z).setColor(red, green, blue, alpha);
    }
}
