package com.blanoir.moons.client.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Structure labels share the same independently drawn billboards as player nametags. */
public final class StructureLabelRenderer {
    public record Label(Vec3 position, String text, int color) {}

    private StructureLabelRenderer() {}

    public static void render(
            Minecraft client,
            PoseStack matrices,
            List<Label> labels,
            double scale,
            int backgroundAlpha) {
        if (labels.isEmpty()) return;
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        List<WorldLabelRenderer.Label> batch = new ArrayList<>(labels.size());
        for (Label label : labels) {
            batch.add(
                    new WorldLabelRenderer.Label(
                            label.position(),
                            List.of(
                                    new WorldLabelRenderer.Span(
                                            label.text(), 0xFF000000 | label.color())),
                            label.position().distanceTo(camera),
                            scale,
                            512,
                            Math.clamp(backgroundAlpha, 0, 255) << 24 | 0x13171B));
        }
        WorldLabelRenderer.render(client, matrices, batch);
    }

    public static void close() {
        WorldLabelRenderer.close();
    }
}
