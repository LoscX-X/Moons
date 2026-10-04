package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.render.model.ModelOverlayRenderer;
import com.blanoir.moons.client.utils.render.ColorCodec;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/** Smoothed real-position presentation; rendering never changes packet history. */
final class BacktrackOverlay {
    private final BacktrackConfig config;
    // Preserve saved visual preferences without exposing six more tuning controls.
    private final int boxFill = color("box.color", 0x1456cfe1);
    private final int boxOutline = color("box.outlineColor", 0xbe56cfe1);
    private final int modelOutline = color("model.outlineColor", 0xffffffff);
    private final int wireFill = color("wireframe.color", 0xffffffff);
    private final int wireOutline = color("wireframe.outlineColor", 0xff000000);
    private final float modelLight =
            Mth.clamp(Settings.getInt("backtrack.esp.model.lightPercent", 100), 0, 100) * 0.01F;
    private BacktrackWireframePlayer wireframe;
    private Vec3 renderPosition;

    BacktrackOverlay(BacktrackConfig config) {
        this.config = config;
    }

    void reset() {
        renderPosition = null;
    }

    void frame(EntityLivingBase target, Vec3 real, double deltaSeconds) {
        if (target == null || config.espMode() == BacktrackConfig.EspMode.NONE) {
            reset();
            return;
        }
        if (renderPosition == null || renderPosition.squareDistanceTo(real) > 4.0) {
            renderPosition = real;
            return;
        }
        double response = 1.0 - Math.exp(-28.0 * Mth.clamp(deltaSeconds, 0.0, 0.05));
        renderPosition =
                new Vec3(
                        renderPosition.xCoord + (real.xCoord - renderPosition.xCoord) * response,
                        renderPosition.yCoord + (real.yCoord - renderPosition.yCoord) * response,
                        renderPosition.zCoord + (real.zCoord - renderPosition.zCoord) * response);
    }

    void renderEsp(WorldRenderEvent event, EntityLivingBase target, Vec3 real) {
        if (!visible(target, real)
                || (config.espMode() != BacktrackConfig.EspMode.BOX
                        && config.espMode() != BacktrackConfig.EspMode.WIREFRAME)) return;
        Minecraft client = Minecraft.getMinecraft();
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        Vec3 at = renderPosition == null ? real : renderPosition;
        com.blanoir.moons.client.render.LegacyPoseStack poses = event.poseStack();
        poses.pushPose();
        try {
            poses.translate(-camera.xCoord, -camera.yCoord, -camera.zCoord);
            if (config.espMode() == BacktrackConfig.EspMode.BOX) {

                double halfWidth = target.width / 2.0;
                AxisAlignedBB box =
                        new AxisAlignedBB(
                                        -halfWidth,
                                        0,
                                        -halfWidth,
                                        halfWidth,
                                        target.height,
                                        halfWidth)
                                .expand(0.015, 0.015, 0.015)
                                .offset(at.xCoord, at.yCoord, at.zCoord);
                BacktrackRenderer.renderBox(
                        poses,
                        box,
                        BacktrackVisual.fill(boxFill),
                        BacktrackVisual.outline(boxOutline),
                        "backtrack box");
            } else {
                if (wireframe == null) wireframe = new BacktrackWireframePlayer();
                poses.translate(at.xCoord, at.yCoord, at.zCoord);
                wireframe.setRotation(target.rotationPitch, target.rotationYaw);
                wireframe.setPose(
                        target.isSneaking()
                                ? BacktrackWireframePlayer.Pose.CROUCHING
                                : BacktrackWireframePlayer.Pose.STANDING);

                wireframe.render(
                        poses,
                        BacktrackVisual.fill(wireFill),
                        BacktrackVisual.outline(wireOutline));
            }
        } finally {
            poses.popPose();
        }
    }

    void renderModel(float partialTicks, EntityLivingBase target, Vec3 real) {
        if (config.espMode() != BacktrackConfig.EspMode.MODEL || !visible(target, real)) return;
        ModelOverlayRenderer.render(
                target,
                renderPosition == null ? real : renderPosition,
                partialTicks,
                modelOutline,
                modelLight,
                true);
    }

    private static boolean visible(EntityLivingBase target, Vec3 real) {
        if (target == null
                || AntiBot.shouldHide(target)
                || real.squareDistanceTo(new Vec3(target.posX, target.posY, target.posZ)) < 0.0025)
            return false;
        Minecraft client = Minecraft.getMinecraft();
        return client != null
                && client.thePlayer != null
                && client.theWorld != null
                && client.theWorld.getEntityByID(target.getEntityId()) == target;
    }

    private static int color(String suffix, int fallback) {
        try {
            String value =
                    Settings.getString("backtrack.esp." + suffix, ColorCodec.formatArgb(fallback))
                            .trim();
            if (value.startsWith("#")) value = value.substring(1);
            if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
            return ColorCodec.parseRgbOrArgb(value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
