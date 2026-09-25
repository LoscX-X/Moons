package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared billboard layout. Minecraft supplies camera/depth, never fonts or text submissions. */
public final class WorldLabelRenderer {
    public record Span(String text, int color) {}

    public record Label(
            Vec3 position,
            List<Span> text,
            double distance,
            double scale,
            double distanceCap,
            int background) {}

    private record Prepared(Matrix4f pose, List<Span> text, float width, int background) {}

    private static Object device;

    private WorldLabelRenderer() {}

    public static List<Span> spans(Component component) {
        List<Span> spans = new ArrayList<>();
        component.visit(
                (style, text) -> {
                    if (!text.isEmpty())
                        spans.add(
                                new Span(
                                        text,
                                        0xFF000000
                                                | (style.getColor() == null
                                                        ? 0xFFFFFF
                                                        : style.getColor().getValue())));
                    return Optional.empty();
                },
                Style.EMPTY);
        return List.copyOf(spans);
    }

    public static void render(Minecraft client, PoseStack matrices, List<Label> labels) {
        if (labels.isEmpty() || VisualRenderTargets.worldTarget(client) == null) return;
        Object currentDevice = RenderSystem.getDevice();
        if (device != null && device != currentDevice) close();
        device = currentDevice;
        List<String> strings = new ArrayList<>();
        for (Label label : labels) for (Span span : label.text()) strings.add(span.text());
        WorldLabelAtlas.prepare(strings);
        List<Prepared> prepared = new ArrayList<>();
        var camera = MinecraftClientAccess.camera(client);
        for (Label label : labels) {
            Vec3 relative = label.position().subtract(camera.position());
            float scale =
                    (float)
                            (.025
                                    * label.scale()
                                    * Math.clamp(label.distance() / 8, 1, label.distanceCap() / 8));
            if (!Float.isFinite(scale) || scale <= 0) continue;
            Matrix4f pose =
                    new Matrix4f(matrices.last().pose())
                            .translate((float) relative.x, (float) relative.y, (float) relative.z)
                            .rotate(new Quaternionf(camera.rotation()).rotateY((float) Math.PI))
                            .scale(-scale, -scale, scale);
            float width = 0;
            for (Span span : label.text()) {
                var tile = WorldLabelAtlas.tile(span.text());
                if (tile != null) width += tile.getAdvance();
            }
            prepared.add(new Prepared(pose, label.text(), width, label.background()));
        }
        WorldLabelBackend.draw(
                client, WorldLabelAtlas.takeUploads(), false, out -> draw(out, prepared, false));
        WorldLabelBackend.draw(client, List.of(), true, out -> draw(out, prepared, true));
    }

    private static void draw(VertexConsumer out, List<Prepared> values, boolean normal) {
        for (Prepared label : values) {
            float x = -label.width() / 2;
            if (!normal && label.background() >>> 24 != 0) {
                quad(
                        out,
                        label.pose(),
                        x - 1,
                        -1,
                        x + label.width() + 1,
                        11,
                        .5f / WorldLabelAtlas.SIZE,
                        .5f / WorldLabelAtlas.SIZE,
                        .5f / WorldLabelAtlas.SIZE,
                        .5f / WorldLabelAtlas.SIZE,
                        premultiply(label.background()));
            }
            for (Span span : label.text()) {
                var tile = WorldLabelAtlas.tile(span.text());
                if (tile == null) continue;
                float left = x - 1, top = -2;
                float right = left + tile.getWidth() / 2f, bottom = top + 16;
                float u = tile.getX() / (float) WorldLabelAtlas.SIZE;
                float v = tile.getY() / (float) WorldLabelAtlas.SIZE;
                float u2 = (tile.getX() + tile.getWidth()) / (float) WorldLabelAtlas.SIZE;
                float v2 = (tile.getY() + 32) / (float) WorldLabelAtlas.SIZE;
                if (!normal)
                    quad(
                            out,
                            label.pose(),
                            left + 1,
                            top + 1,
                            right + 1,
                            bottom + 1,
                            u,
                            v,
                            u2,
                            v2,
                            premultiply(
                                    (span.color() & 0xFF000000)
                                            | ((span.color() & 0xFCFCFC) >> 2)));
                quad(
                        out,
                        label.pose(),
                        left,
                        top,
                        right,
                        bottom,
                        u,
                        v,
                        u2,
                        v2,
                        premultiply(span.color()));
                x += tile.getAdvance();
            }
        }
    }

    private static int premultiply(int color) {
        int a = color >>> 24;
        return a << 24
                | ((color >> 16 & 255) * a / 255) << 16
                | ((color >> 8 & 255) * a / 255) << 8
                | (color & 255) * a / 255;
    }

    private static void quad(
            VertexConsumer out,
            Matrix4f pose,
            float x,
            float y,
            float right,
            float bottom,
            float u,
            float v,
            float u2,
            float v2,
            int color) {
        out.addVertex(pose, x, y, 0).setUv(u, v).setColor(color);
        out.addVertex(pose, x, bottom, 0).setUv(u, v2).setColor(color);
        out.addVertex(pose, right, bottom, 0).setUv(u2, v2).setColor(color);
        out.addVertex(pose, right, y, 0).setUv(u2, v).setColor(color);
    }

    public static void close() {
        WorldLabelBackend.close();
        WorldLabelAtlas.close();
        device = null;
    }
}
