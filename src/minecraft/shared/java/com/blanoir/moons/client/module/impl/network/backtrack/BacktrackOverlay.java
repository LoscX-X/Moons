package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.utils.render.ColorCodec;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    void frame(LivingEntity target, Vec3 real, double deltaSeconds) {
        if (target == null || config.espMode() == BacktrackConfig.EspMode.NONE) {
            reset();
            return;
        }
        if (renderPosition == null || renderPosition.distanceToSqr(real) > 4.0) {
            renderPosition = real;
            return;
        }
        double response = 1.0 - Math.exp(-28.0 * Mth.clamp(deltaSeconds, 0.0, 0.05));
        renderPosition = renderPosition.add(real.subtract(renderPosition).scale(response));
    }

    void renderEsp(WorldRenderEvent event, LivingEntity target, Vec3 real) {
        if (!visible(target, real)
                || (config.espMode() != BacktrackConfig.EspMode.BOX
                        && config.espMode() != BacktrackConfig.EspMode.WIREFRAME)) return;
        Minecraft client = Minecraft.getInstance();
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        Vec3 at = renderPosition == null ? real : renderPosition;
        PoseStack poses = event.poseStack();
        poses.pushPose();
        try {
            poses.translate(-camera.x, -camera.y, -camera.z);
            if (config.espMode() == BacktrackConfig.EspMode.BOX) {
                EntityDimensions dimensions = target.getDimensions(target.getPose());
                double halfWidth = dimensions.width() / 2.0;
                AABB box =
                        new AABB(
                                        -halfWidth,
                                        0,
                                        -halfWidth,
                                        halfWidth,
                                        dimensions.height(),
                                        halfWidth)
                                .inflate(0.015)
                                .move(at);
                BacktrackRenderer.renderBox(
                        poses,
                        box,
                        BacktrackVisual.fill(boxFill),
                        BacktrackVisual.outline(boxOutline),
                        "backtrack box");
            } else {
                if (wireframe == null) wireframe = new BacktrackWireframePlayer();
                poses.translate(at.x, at.y, at.z);
                wireframe.setRotation(target.getXRot(), target.getYRot());
                wireframe.setPose(target.getPose());
                wireframe.setSwimAmount(target.getSwimAmount(0.0F));
                wireframe.render(
                        poses,
                        BacktrackVisual.fill(wireFill),
                        BacktrackVisual.outline(wireOutline));
            }
        } finally {
            poses.popPose();
        }
    }

    void renderModel(
            PoseStack poses,
            LevelRenderState levelState,
            SubmitNodeCollector collector,
            LivingEntity target,
            Vec3 real) {
        if (config.espMode() != BacktrackConfig.EspMode.MODEL || !visible(target, real)) return;
        Minecraft client = Minecraft.getInstance();
        Entity entity = target;
        EntityRenderer<? super Entity, ?> renderer =
                client.getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 0.0F);
        if (modelOutline >>> 24 > 0) state.outlineColor = modelOutline;
        Vec3 at = renderPosition == null ? real : renderPosition;
        state.x = at.x;
        state.y = at.y;
        state.z = at.z;
        CameraRenderState camera = levelState.cameraRenderState;
        state.distanceToCameraSq = camera.pos.distanceToSqr(state.x, state.y, state.z);
        state.lightCoords =
                LightCoordsUtil.pack(
                        Mth.clamp(
                                Math.round(LightCoordsUtil.block(state.lightCoords) * modelLight),
                                0,
                                15),
                        Mth.clamp(
                                Math.round(LightCoordsUtil.sky(state.lightCoords) * modelLight),
                                0,
                                15));
        if (state instanceof LivingEntityRenderState living) {
            living.bodyRot = target.getYRot();
            living.yRot = 0.0F;
            living.xRot = target.getXRot();
        }
        client.getEntityRenderDispatcher()
                .submit(
                        state,
                        camera,
                        state.x - camera.pos.x,
                        state.y - camera.pos.y,
                        state.z - camera.pos.z,
                        poses,
                        collector);
    }

    private static boolean visible(LivingEntity target, Vec3 real) {
        if (target == null || real.distanceToSqr(target.position()) < 0.0025) return false;
        Minecraft client = Minecraft.getInstance();
        return client != null
                && client.player != null
                && client.level != null
                && client.level.getEntity(target.getId()) == target;
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
