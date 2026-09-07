package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class UhcFinder {
    private static final float BOX_ALPHA = 0.35f;
    private static final float OFFLINE_PLAYER_ALPHA = 0.85f;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("uhcfinder.enabled").defaultValue(true).build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("uhcfinder.range")
                    .defaultValue(256.0D)
                    .range(8.0D, 1024.0D)
                    .build();

    private UhcFinder() {}

    public static void init() {
        EventBus.WORLD_RENDER.register("UhcFinder.worldRender", UhcFinder::render);
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "UhcFinder: "
                        + statusText()
                        + ", range: "
                        + format(RANGE.get())
                        + ". Usage: .moons uhcfinder <enable|disable|range 8-1024>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(
                client, "UhcFinder " + statusText() + ". Range: " + format(RANGE.get()) + ".");
        return 1;
    }

    public static int setRange(Minecraft client, double newRange) {
        RANGE.set(newRange);
        ClientChat.send(client, "UhcFinder range set to " + format(RANGE.get()) + ".");
        return 1;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static double range() {
        return RANGE.get();
    }

    private static void render(WorldRenderEvent context) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (currentLevel == null
                || currentPlayer == null
                || MinecraftClientAccess.isHudHidden(client)) {
            return;
        }

        PoseStack matrices = context.poseStack();
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        float tickDelta = context.tickDelta();

        double maxDistanceSquared = RANGE.get() * RANGE.get();
        List<LivingEntity> targets = new ArrayList<>();

        for (Entity entity : currentLevel.entitiesForRendering()) {
            if (entity instanceof LivingEntity livingEntity
                    && shouldRenderTarget(client, livingEntity, maxDistanceSquared)) {
                OfflinePlayerDetect.notifyIfNeeded(client, livingEntity);
                targets.add(livingEntity);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        targets.sort(Comparator.comparingDouble(target -> currentPlayer.distanceToSqr(target)));

        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>(targets.size());
        for (LivingEntity target : targets) {
            boxes.add(createEntityBox(target, tickDelta));
        }

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        WorldOverlayRenderer.render(client, matrices, boxes, "uhcfinder entity boxes");
        matrices.popPose();
    }

    private static boolean shouldRenderTarget(
            Minecraft client, LivingEntity entity, double maxDistanceSquared) {
        return entity != client.player
                && !entity.isRemoved()
                && entity.isAlive()
                && !entity.isSpectator()
                && client.player.distanceToSqr(entity) <= maxDistanceSquared
                && isUhcFinderTarget(entity);
    }

    private static boolean isUhcFinderTarget(LivingEntity entity) {
        return entity.getType().getCategory() == MobCategory.MONSTER || entity instanceof GlowSquid;
    }

    private static WorldOverlayRenderer.ColoredBox createEntityBox(
            LivingEntity entity, float tickDelta) {
        Vec3 pos = interpolatedPosition(entity, tickDelta);
        float[] color = colorFor(entity);
        boolean offlinePlayer = OfflinePlayerDetect.isOfflinePlayerZombie(entity);

        net.minecraft.world.phys.AABB box =
                entity.getBoundingBox()
                        .move(pos.x - entity.getX(), pos.y - entity.getY(), pos.z - entity.getZ());

        return new WorldOverlayRenderer.ColoredBox(
                (float) box.minX,
                (float) box.minY,
                (float) box.minZ,
                (float) box.maxX,
                (float) box.maxY,
                (float) box.maxZ,
                color[0],
                color[1],
                color[2],
                offlinePlayer ? OFFLINE_PLAYER_ALPHA : BOX_ALPHA);
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp((double) tickDelta, entity.xo, entity.getX()),
                Mth.lerp((double) tickDelta, entity.yo, entity.getY()),
                Mth.lerp((double) tickDelta, entity.zo, entity.getZ()));
    }

    private static float[] colorFor(LivingEntity entity) {
        if (entity instanceof EnderMan) {
            return rgb(143, 0, 226);
        }

        if (OfflinePlayerDetect.isOfflinePlayerZombie(entity)) {
            return rgb(255, 0, 255);
        }

        if (entity instanceof Blaze) {
            return rgb(239, 128, 2);
        }

        if (MinecraftClientAccess.isMagmaCube(entity)) {
            return rgb(177, 22, 53);
        }

        if (MinecraftClientAccess.isSlime(entity)) {
            return rgb(41, 255, 0);
        }

        if (entity instanceof Creeper) {
            return rgb(29, 156, 7);
        }

        if (entity instanceof Zombie) {
            return rgb(255, 0, 0);
        }

        if (entity instanceof Ghast) {
            return rgb(255, 190, 190);
        }

        if (entity instanceof GlowSquid) {
            return rgb(0, 255, 220);
        }

        return rgb(255, 215, 0);
    }

    private static float[] rgb(int red, int green, int blue) {
        return new float[] {red / 255.0f, green / 255.0f, blue / 255.0f};
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static String format(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }
}
