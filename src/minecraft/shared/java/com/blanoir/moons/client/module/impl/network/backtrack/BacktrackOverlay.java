package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.model.ModelOverlayRenderer;
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
    private final int boxFill = color("box.color", 0x64058669);
    private final int boxOutline = color("box.outlineColor", darkerTwice(boxFill));
    private final int modelOutline = color("model.outlineColor", 0xffffffff);
    private final int wireFill = color("wireframe.color", 0xffffffff);
    private final int wireOutline = color("wireframe.outlineColor", 0xff000000);
    private final float modelLight =
            Mth.clamp(Settings.getInt("backtrack.esp.model.lightPercent", 100), 0, 100) * 0.01F;
    private BacktrackWireframePlayer wireframe;
    private Vec3 previousRenderPosition;
    private Vec3 currentRenderPosition;
    private Vec3 targetRenderPosition;
    private int interpolationSteps;

    BacktrackOverlay(BacktrackConfig config) {
        this.config = config;
    }

    void reset() {
        previousRenderPosition = currentRenderPosition = targetRenderPosition = null;
        interpolationSteps = 0;
    }

    void start(Vec3 real) {
        previousRenderPosition = currentRenderPosition = targetRenderPosition = real;
        interpolationSteps = 0;
    }

    /** OpenVA's three-tick presentation follows snapshots, never the replayed entity. */
    void observe(Vec3 real) {
        targetRenderPosition = real;
        interpolationSteps = 3;
    }

    void tick() {
        if (currentRenderPosition == null) return;
        previousRenderPosition = currentRenderPosition;
        if (interpolationSteps > 0) {
            currentRenderPosition =
                    currentRenderPosition.add(
                            targetRenderPosition
                                    .subtract(currentRenderPosition)
                                    .scale(1.0 / interpolationSteps));
            interpolationSteps--;
        }
    }

    private Vec3 renderPosition(Vec3 real, float partialTick) {
        if (currentRenderPosition == null) return real;
        return previousRenderPosition.add(
                currentRenderPosition
                        .subtract(previousRenderPosition)
                        .scale(Mth.clamp(partialTick, 0.0F, 1.0F)));
    }

    void renderEsp(WorldRenderEvent event, LivingEntity target, Vec3 real) {
        if (!visible(target)
                || (config.espMode() != BacktrackConfig.EspMode.BOX
                        && config.espMode() != BacktrackConfig.EspMode.WIREFRAME)) return;
        Minecraft client = Minecraft.getInstance();
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        Vec3 at = renderPosition(real, event.tickDelta());
        PoseStack poses = event.poseStack();
        poses.pushPose();
        try {
            poses.translate(-camera.x, -camera.y, -camera.z);
            if (config.espMode() == BacktrackConfig.EspMode.BOX) {
                EntityDimensions dimensions = target.getDimensions(target.getPose());
                double halfWidth =
                        (target.getBoundingBox().getXsize() + target.getPickRadius()) / 2.0;
                AABB box =
                        new AABB(
                                        -halfWidth,
                                        0.01,
                                        -halfWidth,
                                        halfWidth,
                                        dimensions.height() + 0.01,
                                        halfWidth)
                                .move(at);
                BacktrackRenderer.renderBox(poses, box, boxFill, boxOutline, "backtrack box");
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
        if (config.espMode() != BacktrackConfig.EspMode.MODEL || !visible(target)) return;
        Minecraft client = Minecraft.getInstance();
        Entity entity = target;
        EntityRenderer<? super Entity, ?> renderer =
                client.getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 0.0F);
        // Extra models cannot submit into the game's nameplate/outline/shadow passes.
        state.outlineColor = 0;
        state.nameTag = null;
        state.shadowPieces.clear();
        state.displayFireAnimation = false;
        state.leashStates = java.util.List.of();
        Vec3 at = renderPosition(real, client.getDeltaTracker().getGameTimeDeltaPartialTick(true));
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
        VisualModelCapture.submit(
                collector,
                modelOutline,
                ModelOverlayRenderer::remapOverlayModel,
                isolated ->
                        client.getEntityRenderDispatcher()
                                .submit(
                                        state,
                                        camera,
                                        state.x - camera.pos.x,
                                        state.y - camera.pos.y,
                                        state.z - camera.pos.z,
                                        poses,
                                        isolated));
    }

    private static boolean visible(LivingEntity target) {
        if (target == null || AntiBot.shouldHide(target)) return false;
        Minecraft client = Minecraft.getInstance();
        return client != null
                && client.player != null
                && client.level != null
                && client.level.getEntity(target.getId()) == target;
    }

    private static int darkerTwice(int argb) {
        int red = (int) ((int) (((argb >>> 16) & 0xff) * 0.7) * 0.7);
        int green = (int) ((int) (((argb >>> 8) & 0xff) * 0.7) * 0.7);
        int blue = (int) ((int) ((argb & 0xff) * 0.7) * 0.7);
        return (argb & 0xff000000) | red << 16 | green << 8 | blue;
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
