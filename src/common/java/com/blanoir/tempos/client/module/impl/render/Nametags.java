package com.blanoir.tempos.client.module.impl.render;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.customChoice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.feature.HitEstimateSettings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.frame.WorldRenderEvent;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.render.nametags.NametagTextCache;
import com.blanoir.tempos.client.render.WorldLabelFont;
import com.blanoir.tempos.client.render.WorldLabelRenderer;
import com.blanoir.tempos.client.render.WorldOverlayRenderer;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description =
                "Displays player labels with configurable distance, health, and equipment information.")
public final class Nametags extends ClientFeature {
    public static final Nametags INSTANCE = new Nametags();

    private static final double DISTANCE_SCALE_CAP = 64.0D;
    private static final double NAMETAG_Y_OFFSET = 0.55D;

    private static final float PIN_WIDTH = 0.08F;
    private static final float PIN_HEIGHT_PADDING = 0.65F;

    private static final int BACKGROUND_COLOR = 0x5A0A0E18;

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("nametags.enabled").defaultValue(true).build();

    private final DoubleSetting range =
            new DoubleSetting.Builder().name("nametags.range").defaultValue(128.0D).build();

    private final DoubleSetting scale =
            new DoubleSetting.Builder().name("nametags.scale").defaultValue(1.0D).build();

    private final ModeSetting<WorldLabelFont> font =
            new ModeSetting.Builder<WorldLabelFont>()
                    .name("nametags.font")
                    .defaultValue(WorldLabelFont.SMOOTH)
                    .option(WorldLabelFont.SMOOTH, "smooth")
                    .option(WorldLabelFont.MINECRAFT, "minecraft")
                    .build();

    private final BooleanSetting safeMode =
            new BooleanSetting.Builder().name("nametags.safeMode").defaultValue(false).build();

    private final BooleanSetting showDistance =
            new BooleanSetting.Builder().name("nametags.distance").defaultValue(true).build();

    private Nametags() {
        super("nametags", "Nametags");
    }

    @Override
    public void registerEvents() {
        Chams.INSTANCE.bindPlayerFilter(this::shouldRenderPlayer);
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "Nametags.context", event -> NametagTextCache.clear());
        EventBus.WORLD_RENDER.register("Nametags.worldRender", this::render);
    }

    private void render(WorldRenderEvent context) {
        if (!this.enabled.get()) {
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
        boolean showDistance = this.showDistance.get();
        boolean safeMode = this.safeMode.get();
        boolean highlighter = Chams.INSTANCE.isHighlighterEnabled();

        List<WorldOverlayRenderer.ColoredPin> pins = highlighter ? new ArrayList<>() : List.of();

        List<WorldLabelRenderer.Label> labels = new ArrayList<>();
        for (Player player : players) {
            if (!shouldRenderPlayer(client, player)) {
                continue;
            }

            Vec3 playerPos = interpolatedPosition(player, tickDelta);

            double distance = selfPos.distanceTo(playerPos);

            var text = NametagTextCache.format(client, player, distance, showDistance, safeMode);
            labels.add(
                    new WorldLabelRenderer.Label(
                            playerPos.add(0, player.getBbHeight() + NAMETAG_Y_OFFSET, 0),
                            text,
                            distance,
                            this.scale.get(),
                            DISTANCE_SCALE_CAP,
                            BACKGROUND_COLOR));

            if (highlighter) {
                pins.add(createPlayerPin(player, playerPos));
            }
        }

        WorldLabelRenderer.render(client, matrices, labels, this.font.get());

        if (pins.isEmpty()) {
            return;
        }

        matrices.pushPose();
        try {
            matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            WorldOverlayRenderer.renderPins(client, matrices, pins, "nametag player pins");
        } finally {
            matrices.popPose();
        }
    }

    public boolean shouldRenderPlayer(Minecraft client, Player player) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || player == null) {
            return false;
        }

        double range = this.range.get();
        boolean validTarget =
                player != currentPlayer
                        && !player.isRemoved()
                        && player.isAlive()
                        && player.isAttackable()
                        && !player.isSpectator()
                        && !Targeting.shouldHide(player)
                        && currentPlayer.distanceToSqr(player) <= range * range;
        return validTarget
                && (!this.safeMode.get()
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

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        if (!newEnabled) {
            NametagTextCache.clear();
        }
        ClientChat.send(
                client,
                "Nametags "
                        + statusText()
                        + ". Range: "
                        + NumberText.compactDouble(this.range.get())
                        + ", scale: "
                        + NumberText.compactDouble(this.scale.get())
                        + ", safe mode: "
                        + safeModeStatusText()
                        + ", highlighter: "
                        + Chams.INSTANCE.highlighterStatusText()
                        + ", chams: "
                        + Chams.INSTANCE.chamsStatusText()
                        + ".");
        return 1;
    }

    public static int setHighlighterEnabled(Minecraft client, boolean newEnabled) {
        Chams.INSTANCE.setHighlighterEnabled(newEnabled);
        ClientChat.send(
                client, "Nametag highlighter " + Chams.INSTANCE.highlighterStatusText() + ".");
        return 1;
    }

    public static int setChamsEnabled(Minecraft client, boolean newEnabled) {
        Chams.INSTANCE.setChamsEnabled(newEnabled);
        ClientChat.send(client, "Nametag chams " + Chams.INSTANCE.chamsStatusText() + ".");
        return 1;
    }

    public int setSafeMode(Minecraft client, boolean newSafeMode) {
        this.safeMode.set(newSafeMode);
        ClientChat.send(client, "Nametag safe mode " + safeModeStatusText() + ".");
        return 1;
    }

    public int setShowDistance(Minecraft client, boolean visible) {
        this.showDistance.set(visible);
        ClientChat.send(client, "Nametag distance " + (visible ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setRange(Minecraft client, double newRange) {
        this.range.set(newRange);
        ClientChat.send(
                client, "Nametag range set to " + NumberText.compactDouble(this.range.get()) + ".");
        return 1;
    }

    public int setScale(Minecraft client, double newScale) {
        this.scale.set(newScale);
        ClientChat.send(
                client, "Nametag scale set to " + NumberText.compactDouble(this.scale.get()) + ".");
        return 1;
    }

    public String fontMode() {
        return this.font.serialized();
    }

    public List<String> fontOptions() {
        return this.font.optionIds();
    }

    public int setFont(Minecraft ignoredClient, String mode) {
        return this.font.tryDeserialize(mode) ? 1 : 0;
    }

    private String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    private String safeModeStatusText() {
        return this.safeMode.get() ? "enabled" : "disabled";
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            range.describeStored("range", "Range", 8, 256, 1, this::setRange),
            scale.describeStored("scale", "Scale", .5, 4, .05, this::setScale),
            customChoice("font", "Font", this::fontMode, this.fontOptions(), this::setFont)
                    .withDefault("smooth"),
            showDistance.describeStored("show_distance", "Show distance", this::setShowDistance),
            safeMode.describeStored("safe_mode", "Safe mode", this::setSafeMode),
            customChoice(
                            "hit_estimate",
                            "Hit estimate",
                            HitEstimateSettings::mode,
                            HitEstimateSettings.modeOptions(),
                            HitEstimateSettings::setMode)
                    .withDefault("normal_estimate"),
            customChoice(
                            "critical_source",
                            "Critical percent source",
                            HitEstimateSettings::criticalSource,
                            HitEstimateSettings.criticalSourceOptions(),
                            HitEstimateSettings::setCriticalSource)
                    .withDefault("recorded")
                    .visibleWhen(HitEstimateSettings::criticalEstimate),
            number(
                            "critical_percent",
                            "Critical percent",
                            "hitestimate.customCriticalPercent",
                            50,
                            0,
                            100,
                            1,
                            HitEstimateSettings::setCustomCriticalPercent)
                    .visibleWhen(
                            () ->
                                    HitEstimateSettings.criticalEstimate()
                                            && !HitEstimateSettings.recordedSource()),
            bool(
                    "highlighter",
                    "Highlighter",
                    "chams.players.highlighter.enabled",
                    true,
                    Nametags::setHighlighterEnabled),
            bool("chams", "Chams", "chams.players.enabled", true, Nametags::setChamsEnabled)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
