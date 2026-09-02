package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.ui.render.MoonsFonts;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.utils.player.PlayerHealthResolver;
import com.blanoir.moons.client.utils.combat.damage.PlayerHitEstimator;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Nametags {
    private static final StagedVertexBuffer TEXT_BUFFER = new StagedVertexBuffer(
            () -> "moons nametags", 131_072
    );
    private static final NameTagFeatureRenderer TEXT_RENDERER = new NameTagFeatureRenderer();
    private static final double MIN_RANGE = 8.0D;
    private static final double MAX_RANGE = 256.0D;

    private static final double MIN_SCALE = 0.5D;
    private static final double MAX_SCALE = 4.0D;

    private static final float VANILLA_TEXT_SCALE = 0.025F;
    private static final double DISTANCE_SCALE_START = 8.0D;
    private static final double DISTANCE_SCALE_CAP = 64.0D;
    private static final double NAMETAG_Y_OFFSET = 0.55D;

    private static final float PIN_WIDTH = 0.08F;
    private static final float PIN_HEIGHT_PADDING = 0.65F;

    private static final int BACKGROUND_COLOR = 0x5A0A0E18;
    private static final int FULL_BRIGHT_LIGHT = 0x00F000F0;
    private static final int NAME_COLOR = 0xFFF5F7FF;
    private static final int DISTANCE_COLOR = 0xFFD0BE90;
    private static final int HEALTH_GOOD = 0xFF8FE3AB;
    private static final int HEALTH_WARNING = 0xFFFFD47B;
    private static final int HEALTH_LOW = 0xFFFF8F9E;
    private static final int HIT_COLOR = 0xFFFFD75A;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("nametags.enabled")
                    .defaultValue(true)
                    .build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("nametags.range")
                    .defaultValue(128.0D)
                    .build();

    private static final DoubleSetting SCALE =
            new DoubleSetting.Builder()
                    .name("nametags.scale")
                    .defaultValue(1.0D)
                    .build();

    private static final BooleanSetting SAFE_MODE =
            new BooleanSetting.Builder()
                    .name("nametags.safeMode")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting SHOW_DISTANCE =
            new BooleanSetting.Builder()
                    .name("nametags.distance")
                    .defaultValue(true)
                    .build();

    private Nametags() {
    }

    public static void init() {
        Chams.bindPlayerFilter(Nametags::shouldRenderPlayer);
        EventBus.WORLD_RENDER.register("Nametags.worldRender", Nametags::render);
    }

    private static void render(WorldRenderEvent context) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || MinecraftClientAccess.isHudHidden(client)) {
            return;
        }

        PoseStack matrices = context.poseStack();
        if (matrices == null) {
            return;
        }

        Camera camera = MinecraftClientAccess.camera(client);
        Vec3 cameraPos = MinecraftClientAccess.camera(client).position();
        float tickDelta = context.tickDelta();

        List<WorldOverlayRenderer.ColoredPin> pins = new ArrayList<>();
        List<NameTagFeatureRenderer.Submit> textSubmits = new ArrayList<>();

        for (Player player : client.level.players()) {
            if (!shouldRenderPlayer(client, player)) {
                continue;
            }

            Vec3 playerPos = interpolatedPosition(player, tickDelta);
            Vec3 selfPos = interpolatedPosition(client.player, tickDelta);

            Vec3 renderPos = playerPos.subtract(cameraPos);
            double distance = selfPos.distanceTo(playerPos);

            Component text = formatNametag(player, distance);
            drawNametag(client, matrices, textSubmits, camera, player, renderPos, text, distance);

            if (Chams.isHighlighterEnabled()) {
                pins.add(createPlayerPin(player, tickDelta));
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
        if (client == null || client.player == null || player == null) {
            return false;
        }

        double range = RANGE.get();
        boolean validTarget = player != client.player
                && !player.isRemoved()
                && player.isAlive()
                && player.isAttackable()
                && !player.isSpectator()
                && !AntiBot.isBot(player)
                && client.player.distanceToSqr(player) <= range * range;
        return validTarget && (!SAFE_MODE.get() || (!player.isShiftKeyDown() && !player.getName().getString().isBlank()));
    }

    private static WorldOverlayRenderer.ColoredPin createPlayerPin(Player player, float tickDelta) {
        Vec3 pos = interpolatedPosition(player, tickDelta);

        return new WorldOverlayRenderer.ColoredPin(
                (float) pos.x,
                (float) pos.y,
                (float) pos.z,
                player.getBbHeight() + PIN_HEIGHT_PADDING,
                PIN_WIDTH,
                Chams.CHAMS_RED,
                Chams.CHAMS_GREEN,
                Chams.CHAMS_BLUE,
                Chams.HIGHLIGHTER_ALPHA
        );
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp((double) tickDelta, entity.xo, entity.getX()),
                Mth.lerp((double) tickDelta, entity.yo, entity.getY()),
                Mth.lerp((double) tickDelta, entity.zo, entity.getZ())
        );
    }

    private static Component formatNametag(Player player, double distance) {
        MutableComponent nametag = MoonsFonts.clickGuiText(player.getName().getString(), NAME_COLOR);
        if (SHOW_DISTANCE.get()) {
            nametag.append(MoonsFonts.clickGuiText(
                    "  " + String.format(Locale.ROOT, "%.1fm", distance), DISTANCE_COLOR));
        }
        if (SAFE_MODE.get()) {
            return nametag;
        }

        float health = PlayerHealthResolver.resolve(player);
        float maxHealth = PlayerHealthResolver.max(player);
        int healthColor = health > maxHealth * 0.6F ? HEALTH_GOOD
                : health > maxHealth * 0.3F ? HEALTH_WARNING : HEALTH_LOW;
        nametag.append(MoonsFonts.clickGuiText(
                "  " + String.format(Locale.ROOT, "%.1f HP", health), healthColor));
        return nametag.append(MoonsFonts.clickGuiText(
                "  " + PlayerHitEstimator.text(Minecraft.getInstance(), player, health), HIT_COLOR));
    }

    private static void drawNametag(
            Minecraft client,
            PoseStack matrices,
            List<NameTagFeatureRenderer.Submit> submits,
            Camera camera,
            Player player,
            Vec3 renderPos,
            Component text,
            double distance
    ) {
        if (text == null || text.getString().isEmpty()) {
            return;
        }

        Font font = client.font;
        int width = font.width(text);

        float distanceScale = (float) Mth.clamp(
                distance / DISTANCE_SCALE_START,
                1.0D,
                DISTANCE_SCALE_CAP / DISTANCE_SCALE_START
        );

        float finalScale = (float) (VANILLA_TEXT_SCALE * SCALE.get() * distanceScale);

        matrices.pushPose();

        matrices.translate(
                renderPos.x,
                renderPos.y + player.getBbHeight() + NAMETAG_Y_OFFSET,
                renderPos.z
        );

        matrices.mulPose(new Quaternionf(camera.rotation()).rotateY((float) Math.PI));
        matrices.scale(-finalScale, -finalScale, finalScale);

        float x = -width / 2.0F;
        float y = 0.0F;

        Matrix4f pose = new Matrix4f(matrices.last().pose());
        submits.add(new NameTagFeatureRenderer.Submit(
                pose, x, y, text, FULL_BRIGHT_LIGHT, 0xFFFFFFFF,
                BACKGROUND_COLOR, Font.DisplayMode.SEE_THROUGH
        ));
        submits.add(new NameTagFeatureRenderer.Submit(
                pose, x, y, text, FULL_BRIGHT_LIGHT, 0xFFFFFFFF,
                0, Font.DisplayMode.NORMAL
        ));

        matrices.popPose();
    }

    private static void renderNametagBatch(Minecraft client, List<NameTagFeatureRenderer.Submit> submits) {
        if (submits.isEmpty()) {
            return;
        }
        FeatureFrameContext context = new FeatureFrameContext(
                client.gameRenderer.gameRenderState().optionsRenderState,
                client.font,
                client.getModelManager().getBlockStateModelSet(),
                client.getBlockColors(),
                client.getTextureManager(),
                client.getAtlasManager(),
                client.gameRenderer.lightmap(),
                TEXT_BUFFER
        );
        TEXT_RENDERER.beginPrepare(context);
        TEXT_RENDERER.prepareGroup(context, submits, false);
        TEXT_RENDERER.finishPrepare(context);
        TEXT_BUFFER.upload();
        TEXT_RENDERER.executeGroup(context, 0, submits, false);
        TEXT_RENDERER.finishExecute(context);
        TEXT_BUFFER.endFrame();
    }

    private static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Nametags: " + statusText()
                        + ", range: " + format(RANGE.get())
                        + ", scale: " + format(SCALE.get())
                        + ", safe mode: " + safeModeStatusText()
                        + ", highlighter: " + Chams.highlighterStatusText()
                        + ", chams: " + Chams.chamsStatusText()
                        + ". Usage: .moons nametag <enable|disable|range 8-256|scale 0.5-4|safe enable|disable|hlighter enable|disable|chams enable|disable>"
        );
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(
                client,
                "Nametags " + statusText()
                        + ". Range: " + format(RANGE.get())
                        + ", scale: " + format(SCALE.get())
                        + ", safe mode: " + safeModeStatusText()
                        + ", highlighter: " + Chams.highlighterStatusText()
                        + ", chams: " + Chams.chamsStatusText()
                        + "."
        );
        return 1;
    }

    private static int showHighlighterStatus(Minecraft client) {
        ClientChat.send(client, "Nametag highlighter: " + Chams.highlighterStatusText() + ". Usage: .moons nametag hlighter <enable|disable>");
        return 1;
    }

    public static int setHighlighterEnabled(Minecraft client, boolean newEnabled) {
        Chams.setHighlighterEnabled(newEnabled);
        ClientChat.send(client, "Nametag highlighter " + Chams.highlighterStatusText() + ".");
        return 1;
    }

    private static int showChamsStatus(Minecraft client) {
        ClientChat.send(client, "Nametag chams: " + Chams.chamsStatusText() + ". Usage: .moons nametag chams <enable|disable>");
        return 1;
    }

    public static int setChamsEnabled(Minecraft client, boolean newEnabled) {
        Chams.setChamsEnabled(newEnabled);
        ClientChat.send(client, "Nametag chams " + Chams.chamsStatusText() + ".");
        return 1;
    }

    private static int showSafeModeStatus(Minecraft client) {
        ClientChat.send(client, "Nametag safe mode: " + safeModeStatusText()
                + ". Usage: .moons nametag safe <enable|disable>");
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
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean isSafeMode() {
        return SAFE_MODE.get();
    }
}
