package com.blanoir.tempos.client.module.impl.network;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.choice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.customChoice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.numeric;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.SettingSpec;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.network.SessionToken;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.network.backtrack.BacktrackConfig;
import com.blanoir.tempos.client.module.impl.network.backtrack.BacktrackRuntime;
import com.blanoir.tempos.client.module.impl.network.backtrack.BacktrackSettings;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Module entry points; tracking, replay and rendering belong to the Backtrack runtime. */
@ModuleInfo(category = ModuleCategory.NETWORK)
public final class Backtrack extends ClientFeature {
    public static final Backtrack INSTANCE = new Backtrack();

    private final BacktrackConfig config = new BacktrackConfig();
    private final BacktrackRuntime runtime = new BacktrackRuntime(this.config);
    private volatile long activationGeneration;

    private Backtrack() {
        super("backtrack", "Backtrack");
    }

    public void initPacketListeners() {
        EventBus.PACKET_RECEIVE_BUNDLE.register(
                "Backtrack.bundleExpansion",
                event -> {
                    if (isEnabled()) event.requestExpansion();
                });
        EventBus.PACKET_RECEIVE_PRE.register(
                "Backtrack.packetReceive",
                event -> {
                    if (!event.bundleExpansionRequested()
                            && handleIncomingPacket(event.packet(), event.listener())) {
                        event.cancel();
                    }
                });
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "Backtrack.context", event -> this.runtime.discard());
        EventBus.PACKET_SEND_POST.register(
                "Backtrack.attackSent",
                event -> {
                    if (isEnabled() && event.packet() instanceof ServerboundAttackPacket attack) {
                        Minecraft client = Minecraft.getInstance();
                        var connection = client.getConnection();
                        var level = client.level;
                        var session = new SessionToken<>(connection, level);
                        long activation = this.activationGeneration;
                        client.execute(
                                () -> {
                                    if (activation == this.activationGeneration
                                            && connection != null
                                            && level != null
                                            && session.matches(
                                                    client.getConnection(), client.level)) {
                                        onAttack(level.getEntity(attack.entityId()));
                                    }
                                });
                    }
                });
        EventBus.TICK.register("Backtrack.tick", event -> this.runtime.tick(event.client()));
        EventBus.FRAME.register("Backtrack.frame", this.runtime::frame);
        EventBus.WORLD_RENDER.register("Backtrack.worldRender", this.runtime::renderEsp);
    }

    @Override
    public boolean isEnabled() {
        return this.config.enabled();
    }

    /** Core deactivation discards retained state; it never starts a replay. */
    public void suspend() {
        this.activationGeneration++;
        this.runtime.discard();
    }

    public boolean handleIncomingPacket(Packet<?> packet, PacketListener listener) {
        return this.runtime.handleIncomingPacket(packet, listener);
    }

    /** Called after vanilla dispatches either a manual or an automatic attack. */
    public void onAttack(Entity entity) {
        this.runtime.attack(Minecraft.getInstance(), entity);
    }

    public boolean isLagging() {
        return isEnabled() && this.runtime.isLagging();
    }

    public String hudStats() {
        return this.runtime.hudStats();
    }

    public void renderModel(
            PoseStack poses, LevelRenderState state, SubmitNodeCollector collector) {
        this.runtime.renderModel(poses, state, collector);
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.runtime.release();
        this.config.setEnabled(enabled);
        ClientChat.send(client, "Backtrack " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setDelay(Minecraft client, String value) {
        int result = this.config.setDelay(client, value);
        if (result != 0) this.runtime.release();
        return result;
    }

    public int setRange(Minecraft client, String value) {
        int result = this.config.setRange(client, value);
        if (result != 0) this.runtime.release();
        return result;
    }

    public int delayMillis() {
        return this.config.delayMillis();
    }

    public int minDelayMillis() {
        return this.config.minDelayMillis();
    }

    public String targetModeName() {
        return this.config.targetModeName();
    }

    public List<String> targetModeOptions() {
        return this.config.targetModeOptions();
    }

    public int setTargetMode(Minecraft client, String value) {
        if (!this.config.targetModeOptions().contains(value)) return 0;
        this.runtime.release();
        return this.config.setTargetMode(client, value);
    }

    public double maxRange() {
        return this.config.maxRange();
    }

    public int setEsp(Minecraft client, String value) {
        return this.config.setEsp(client, value);
    }

    public List<String> espModeOptions() {
        return this.config.espModeOptions();
    }

    @Override
    protected String statusTag() {
        return this.hudStats();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            customChoice(
                            "target_mode",
                            "Mode",
                            this::targetModeName,
                            this.targetModeOptions(),
                            this::setTargetMode)
                    .withDefault(BacktrackSettings.TARGET_MODE.defaultId()),
            new Setting(
                            "delay",
                            "Time (ms)",
                            "range",
                            () -> {
                                var value = new com.google.gson.JsonArray();
                                value.add(this.minDelayMillis());
                                value.add(this.delayMillis());
                                return value;
                            },
                            BacktrackSettings.DELAY_MAX.min().doubleValue(),
                            BacktrackSettings.DELAY_MAX.max().doubleValue(),
                            1.0,
                            java.util.List.of(),
                            (client, value) ->
                                    this.setDelay(
                                            client,
                                            value.getAsJsonArray().get(0).getAsInt()
                                                    + "-"
                                                    + value.getAsJsonArray().get(1).getAsInt()))
                    .withDefault(
                            BacktrackSettings.DELAY_MIN.defaultValue(),
                            BacktrackSettings.DELAY_MAX.defaultValue()),
            numeric(
                            "range",
                            "Max range",
                            "number",
                            this::maxRange,
                            BacktrackSettings.RANGE_MAX.min(),
                            BacktrackSettings.RANGE_MAX.max(),
                            .1,
                            (client, value) -> this.setRange(client, Double.toString(value)))
                    .withDefault(BacktrackSettings.RANGE_MAX.defaultValue()),
            choice(
                    "esp",
                    "ESP",
                    BacktrackSettings.ESP.key(),
                    BacktrackSettings.ESP.defaultId(),
                    this.espModeOptions(),
                    this::setEsp),
            internalNumber("min_range", "Min range", BacktrackSettings.RANGE_MIN),
            internalInt("next_min", "Next delay min", BacktrackSettings.NEXT_MIN),
            internalInt("next_max", "Next delay max", BacktrackSettings.NEXT_MAX),
            internalInt("tracking_buffer", "Tracking buffer", BacktrackSettings.TRACKING_BUFFER),
            internalNumber("chance", "Chance", BacktrackSettings.CHANCE),
            internalBool("pause_hurt", "Pause on hurt", BacktrackSettings.PAUSE_HURT),
            internalInt("hurt_time", "Hurt time", BacktrackSettings.HURT_TIME),
            internalInt("last_attack", "Last attack", BacktrackSettings.LAST_ATTACK),
            internalInt("max_queue", "Queue limit", BacktrackSettings.QUEUE_LIMIT),
            internalNumber("ping_ratio", "Ping ratio", BacktrackSettings.PING_RATIO),
            internalBool("actionbar", "Action bar", BacktrackSettings.ACTION_BAR)
        };
    }

    private static Setting internalInt(String id, String label, SettingSpec<Integer> spec) {
        return numeric(
                        id,
                        label,
                        "integer",
                        () ->
                                Math.clamp(
                                        Settings.getInt(spec.key(), spec.defaultValue()),
                                        spec.min(),
                                        spec.max()),
                        spec.min(),
                        spec.max(),
                        1,
                        (client, value) -> {
                            Settings.setInt(spec.key(), (int) Math.round(value));
                        })
                .withDefault(spec.defaultValue())
                .visibleWhen(() -> false);
    }

    private static Setting internalNumber(String id, String label, SettingSpec<Double> spec) {
        return numeric(
                        id,
                        label,
                        "number",
                        () -> {
                            double value = Settings.getDouble(spec.key(), spec.defaultValue());
                            return Double.isFinite(value)
                                    ? Math.clamp(value, spec.min(), spec.max())
                                    : spec.defaultValue();
                        },
                        spec.min(),
                        spec.max(),
                        .1,
                        (client, value) -> {
                            Settings.setDouble(spec.key(), value);
                        })
                .withDefault(spec.defaultValue())
                .visibleWhen(() -> false);
    }

    private static Setting internalBool(String id, String label, SettingSpec<Boolean> spec) {
        return bool(
                        id,
                        label,
                        spec.key(),
                        spec.defaultValue(),
                        (client, value) -> {
                            Settings.setBoolean(spec.key(), value);
                            return 1;
                        })
                .visibleWhen(() -> false);
    }
}
