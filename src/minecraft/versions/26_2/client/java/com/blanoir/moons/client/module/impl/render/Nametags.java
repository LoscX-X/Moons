package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.module.impl.render.nametags.NametagTextCache;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.utils.text.NumberText;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

public final class Nametags {
    private static final StagedVertexBuffer TEXT_BUFFER =
            new StagedVertexBuffer(() -> "moons nametags", 131_072);
    private static final NameTagFeatureRenderer TEXT_RENDERER = new NameTagFeatureRenderer();

    private static final float VANILLA_TEXT_SCALE = 0.025F;
    private static final double DISTANCE_SCALE_START = 8.0D;
    private static final double DISTANCE_SCALE_CAP = 64.0D;
    private static final double NAMETAG_Y_OFFSET = 0.55D;

    private static final float PIN_WIDTH = 0.08F;
    private static final float PIN_HEIGHT_PADDING = 0.65F;

    private static final int BACKGROUND_COLOR = 0x5A0A0E18;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("nametags.enabled").defaultValue(true).build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder().name("nametags.range").defaultValue(128.0D).build();

    private static final DoubleSetting SCALE =
            new DoubleSetting.Builder().name("nametags.scale").defaultValue(1.0D).build();

    private static final BooleanSetting SAFE_MODE =
            new BooleanSetting.Builder().name("nametags.safeMode").defaultValue(false).build();

    private static final BooleanSetting SHOW_DISTANCE =
            new BooleanSetting.Builder().name("nametags.distance").defaultValue(true).build();

    private Nametags() {}

    public static void init() {
        Chams.bindPlayerFilter(Nametags::shouldRenderPlayer);
        EventBus.WORLD_RENDER.register("Nametags.worldRender", Nametags::render);
    }

    private static void render(WorldRenderEvent context) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (currentPlayer == null
                || currentLevel == null
                || MinecraftClientAccess.isHudHidden(client)) {
            return;
        }

        var players = currentLevel.players();
        if (players.isEmpty() || (players.size() == 1 && players.get(0) == currentPlayer)) {
            return;
        }

        PoseStack matrices = context.poseStack();
        if (matrices == null) {
            return;
        }

        Camera camera = MinecraftClientAccess.camera(client);
        Vec3 cameraPos = camera.position();
        float tickDelta = context.tickDelta();
        Vec3 selfPos = interpolatedPosition(currentPlayer, tickDelta);
        boolean showDistance = SHOW_DISTANCE.get();
        boolean safeMode = SAFE_MODE.get();
        boolean highlighter = Chams.isHighlighterEnabled();

        List<WorldOverlayRenderer.ColoredPin> pins = highlighter ? new ArrayList<>() : List.of();
        List<NameTagFeatureRenderer.Submit> textSubmits = new ArrayList<>();

        for (Player player : players) {
            if (!shouldRenderPlayer(client, player)) {
                continue;
            }

            Vec3 playerPos = interpolatedPosition(player, tickDelta);

            Vec3 renderPos = playerPos.subtract(cameraPos);
            double distance = selfPos.distanceTo(playerPos);

            Component text =
                    NametagTextCache.format(client, player, distance, showDistance, safeMode);
            drawNametag(client, matrices, textSubmits, camera, player, renderPos, text, distance);

            if (highlighter) {
                pins.add(createPlayerPin(player, playerPos));
            }
        }

        renderNametagBatch(client, textSubmits);

        if (pins.isEmpty()) {
            return;
        }

        matrices.pushPose();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        WorldOverlayRenderer.renderPins(client, matrices, pins, "nametag player pins");
        matrices.popPose();
    }

    public static boolean shouldRenderPlayer(Minecraft client, Player player) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || player == null) {
            return false;
        }

        double range = RANGE.get();
        boolean validTarget =
                player != currentPlayer
                        && !player.isRemoved()
                        && player.isAlive()
                        && player.isAttackable()
                        && !player.isSpectator()
                        && !AntiBot.isBot(player)
                        && currentPlayer.distanceToSqr(player) <= range * range;
        return validTarget
                && (!SAFE_MODE.get()
                        || (!player.isShiftKeyDown() && !player.getName().getString().isBlank()));
    }

    private static WorldOverlayRenderer.ColoredPin createPlayerPin(Player player, Vec3 pos) {
        return new WorldOverlayRenderer.ColoredPin(
                (float) pos.x,
                (float) pos.y,
                (float) pos.z,
                player.getBbHeight() + PIN_HEIGHT_PADDING,
                PIN_WIDTH,
                Chams.CHAMS_RED,
                Chams.CHAMS_GREEN,
                Chams.CHAMS_BLUE,
                Chams.HIGHLIGHTER_ALPHA);
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp((double) tickDelta, entity.xo, entity.getX()),
                Mth.lerp((double) tickDelta, entity.yo, entity.getY()),
                Mth.lerp((double) tickDelta, entity.zo, entity.getZ()));
    }

    private static void drawNametag(
            Minecraft client,
            PoseStack matrices,
            List<NameTagFeatureRenderer.Submit> submits,
            Camera camera,
            Player player,
            Vec3 renderPos,
            Component text,
            double distance) {
        Font font = client.font;
        int width = font.width(text);

        float distanceScale =
                (float)
                        Mth.clamp(
                                distance / DISTANCE_SCALE_START,
                                1.0D,
                                DISTANCE_SCALE_CAP / DISTANCE_SCALE_START);

        float finalScale = (float) (VANILLA_TEXT_SCALE * SCALE.get() * distanceScale);

        matrices.pushPose();

        matrices.translate(
                renderPos.x, renderPos.y + player.getBbHeight() + NAMETAG_Y_OFFSET, renderPos.z);

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
                        0xFFFFFFFF,
                        BACKGROUND_COLOR,
                        Font.DisplayMode.SEE_THROUGH));
        submits.add(
                new NameTagFeatureRenderer.Submit(
                        pose,
                        x,
                        y,
                        text,
                        FULL_BRIGHT_LIGHT,
                        0xFFFFFFFF,
                        0,
                        Font.DisplayMode.NORMAL));

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

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        if (!newEnabled) {
            NametagTextCache.clear();
        }
        ClientChat.send(
                client,
                "Nametags "
                        + statusText()
                        + ". Range: "
                        + format(RANGE.get())
                        + ", scale: "
                        + format(SCALE.get())
                        + ", safe mode: "
                        + safeModeStatusText()
                        + ", highlighter: "
                        + Chams.highlighterStatusText()
                        + ", chams: "
                        + Chams.chamsStatusText()
                        + ".");
        return 1;
    }

    public static int setHighlighterEnabled(Minecraft client, boolean newEnabled) {
        Chams.setHighlighterEnabled(newEnabled);
        ClientChat.send(client, "Nametag highlighter " + Chams.highlighterStatusText() + ".");
        return 1;
    }

    public static int setChamsEnabled(Minecraft client, boolean newEnabled) {
        Chams.setChamsEnabled(newEnabled);
        ClientChat.send(client, "Nametag chams " + Chams.chamsStatusText() + ".");
        return 1;
    }

    public static int setSafeMode(Minecraft client, boolean newSafeMode) {
        SAFE_MODE.set(newSafeMode);
        ClientChat.send(client, "Nametag safe mode " + safeModeStatusText() + ".");
        return 1;
    }

    public static int setShowDistance(Minecraft client, boolean visible) {
        SHOW_DISTANCE.set(visible);
        ClientChat.send(client, "Nametag distance " + (visible ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setRange(Minecraft client, double newRange) {
        RANGE.set(newRange);
        ClientChat.send(client, "Nametag range set to " + format(RANGE.get()) + ".");
        return 1;
    }

    public static int setScale(Minecraft client, double newScale) {
        SCALE.set(newScale);
        ClientChat.send(client, "Nametag scale set to " + format(SCALE.get()) + ".");
        return 1;
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static String safeModeStatusText() {
        return SAFE_MODE.get() ? "enabled" : "disabled";
    }

    private static String format(double value) {
        return NumberText.compactDouble(value);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }
}
