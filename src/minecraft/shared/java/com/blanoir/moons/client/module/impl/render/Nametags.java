package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.module.impl.render.nametags.NametagTextCache;
import com.blanoir.moons.client.render.LegacyCamera;
import com.blanoir.moons.client.render.LegacyPoseStack;
import com.blanoir.moons.client.render.WorldLabelFont;
import com.blanoir.moons.client.render.WorldLabelRenderer;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class Nametags {

    private static final double DISTANCE_SCALE_CAP = 64.0D;
    private static final double NAMETAG_Y_OFFSET = 0.55D;

    private static final float PIN_WIDTH = 0.08F;
    private static final float PIN_HEIGHT_PADDING = 0.65F;

    private static final int BACKGROUND_COLOR = 0x5A0A0E18;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("nametags.enabled").defaultValue(true).build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder().name("nametags.range").defaultValue(128.0D).build();

    private static final DoubleSetting SCALE =
            new DoubleSetting.Builder().name("nametags.scale").defaultValue(1.0D).build();

    private static final ModeSetting<WorldLabelFont> FONT =
            new ModeSetting.Builder<WorldLabelFont>()
                    .name("nametags.font")
                    .defaultValue(WorldLabelFont.SMOOTH)
                    .option(WorldLabelFont.SMOOTH, "smooth")
                    .option(WorldLabelFont.MINECRAFT, "minecraft")
                    .build();

    private static final BooleanSetting SAFE_MODE =
            new BooleanSetting.Builder().name("nametags.safeMode").defaultValue(false).build();

    private static final BooleanSetting SHOW_DISTANCE =
            new BooleanSetting.Builder().name("nametags.distance").defaultValue(true).build();

    private Nametags() {}

    public static void init() {
        Chams.bindPlayerFilter(Nametags::shouldRenderPlayer);
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "Nametags.context", event -> NametagTextCache.clear());
        EventBus.WORLD_RENDER.register("Nametags.worldRender", Nametags::render);
    }

    private static void render(WorldRenderEvent context) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        var currentLevel = client == null ? null : client.theWorld;
        if (currentPlayer == null
                || currentLevel == null
                || MinecraftClientAccess.isHudHidden(client)) {
            return;
        }

        var players = currentLevel.playerEntities;
        if (players.isEmpty() || (players.size() == 1 && players.get(0) == currentPlayer)) {
            return;
        }

        LegacyPoseStack matrices = context.poseStack();
        if (matrices == null) {
            return;
        }

        LegacyCamera camera = MinecraftClientAccess.camera(client);
        Vec3 cameraPos = camera.position();
        float tickDelta = context.tickDelta();
        Vec3 selfPos = interpolatedPosition(currentPlayer, tickDelta);
        boolean showDistance = SHOW_DISTANCE.get();
        boolean safeMode = SAFE_MODE.get();
        boolean highlighter = Chams.isHighlighterEnabled();

        List<WorldOverlayRenderer.ColoredPin> pins = highlighter ? new ArrayList<>() : List.of();

        List<WorldLabelRenderer.Label> labels = new ArrayList<>();
        for (EntityPlayer player : players) {
            if (!shouldRenderPlayer(client, player)) {
                continue;
            }

            Vec3 playerPos = interpolatedPosition(player, tickDelta);

            double distance = selfPos.distanceTo(playerPos);

            var text = NametagTextCache.format(client, player, distance, showDistance, safeMode);
            labels.add(
                    new WorldLabelRenderer.Label(
                            playerPos.addVector(0, player.height + NAMETAG_Y_OFFSET, 0),
                            text,
                            distance,
                            SCALE.get(),
                            DISTANCE_SCALE_CAP,
                            BACKGROUND_COLOR));

            if (highlighter) {
                pins.add(createPlayerPin(player, playerPos));
            }
        }

        WorldLabelRenderer.render(client, matrices, labels, FONT.get());

        if (pins.isEmpty()) {
            return;
        }

        matrices.pushPose();
        try {
            matrices.translate(-cameraPos.xCoord, -cameraPos.yCoord, -cameraPos.zCoord);
            WorldOverlayRenderer.renderPins(client, matrices, pins, "nametag player pins");
        } finally {
            matrices.popPose();
        }
    }

    public static boolean shouldRenderPlayer(Minecraft client, EntityPlayer player) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || player == null) {
            return false;
        }

        double range = RANGE.get();
        boolean validTarget =
                player != currentPlayer
                        && !player.isDead
                        && player.isEntityAlive()
                        && player.canAttackWithItem()
                        && !player.isSpectator()
                        && !AntiBot.shouldHide(player)
                        && currentPlayer.getDistanceSqToEntity(player) <= range * range;
        return validTarget
                && (!SAFE_MODE.get() || (!player.isSneaking() && !player.getName().isBlank()));
    }

    private static WorldOverlayRenderer.ColoredPin createPlayerPin(EntityPlayer player, Vec3 pos) {
        return new WorldOverlayRenderer.ColoredPin(
                (float) pos.xCoord,
                (float) pos.yCoord,
                (float) pos.zCoord,
                player.height + PIN_HEIGHT_PADDING,
                PIN_WIDTH,
                Chams.CHAMS_RED,
                Chams.CHAMS_GREEN,
                Chams.CHAMS_BLUE,
                Chams.HIGHLIGHTER_ALPHA);
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp((double) tickDelta, entity.lastTickPosX, entity.posX),
                Mth.lerp((double) tickDelta, entity.lastTickPosY, entity.posY),
                Mth.lerp((double) tickDelta, entity.lastTickPosZ, entity.posZ));
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

    public static String fontMode() {
        return FONT.serialized();
    }

    public static List<String> fontOptions() {
        return FONT.optionIds();
    }

    public static int setFont(Minecraft ignoredClient, String mode) {
        return FONT.tryDeserialize(mode) ? 1 : 0;
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
