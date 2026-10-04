package com.blanoir.moons.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

/** Snapshot of the interpolated render camera at the world-render boundary. */
public record LegacyRenderContext(float partialTick, Vec3 cameraPosition) {
    public static LegacyRenderContext capture(float partialTick) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        return new LegacyRenderContext(
                partialTick,
                view == null
                        ? new Vec3(0, 0, 0)
                        : new Vec3(
                                view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTick,
                                view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTick,
                                view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTick));
    }
}
