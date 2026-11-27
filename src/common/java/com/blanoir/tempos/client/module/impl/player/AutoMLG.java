package com.blanoir.tempos.client.module.impl.player;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;
import com.blanoir.tempos.client.module.impl.player.automlg.AutoMlgRuntime;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Selects and uses a landing item when you fall.")
public final class AutoMLG extends ClientFeature {
    public static final AutoMLG INSTANCE = new AutoMLG();

    // Legacy storage/module ids are retained for existing profiles and key bindings.
    private final AutoMlgRuntime autoMlg = new AutoMlgRuntime();
    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("nofall.enabled").defaultValue(false).build();
    private final DoubleSetting threshold =
            new DoubleSetting.Builder()
                    .name("nofall.threshold")
                    .defaultValue(3.0D)
                    .range(1.0D, 10.0D)
                    .build();
    private final IntSetting predictTicks =
            new IntSetting.Builder()
                    .name("nofall.predictTicks")
                    .defaultValue(2)
                    .range(1, 5)
                    .build();
    private final BooleanSetting solidCheck =
            new BooleanSetting.Builder().name("nofall.solidCheck").defaultValue(true).build();
    private final BooleanSetting recovery =
            new BooleanSetting.Builder().name("nofall.recovery").defaultValue(true).build();
    private final ModeSetting<SilentPacketRotation.Mode> rotation =
            new ModeSetting.Builder<SilentPacketRotation.Mode>()
                    .name("nofall.rotation")
                    .defaultValue(SilentPacketRotation.Mode.INSTANT)
                    .option(SilentPacketRotation.Mode.INSTANT, "instant")
                    .option(SilentPacketRotation.Mode.SMOOTH, "smooth")
                    .build();
    private final IntSetting smoothTurnTicks =
            new IntSetting.Builder()
                    .name("nofall.smoothTurnTicks")
                    .defaultValue(2)
                    .range(1, 5)
                    .build();
    private final IntSetting smoothReturnTicks =
            new IntSetting.Builder()
                    .name("nofall.smoothReturnTicks")
                    .defaultValue(2)
                    .range(1, 5)
                    .build();

    private AutoMLG() {
        super("nofall", "AutoMLG");
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoMLG.context", event -> this.autoMlg.reset(null));
        EventBus.PLAYER_UPDATE.register(
                "AutoMLG.playerUpdate",
                event -> {
                    Minecraft client = event.client();
                    if (!this.enabled.get() || !ready(client)) {
                        this.autoMlg.reset(client);
                        return;
                    }
                    this.autoMlg.tick(
                            client,
                            this.threshold.get(),
                            this.predictTicks.get(),
                            this.solidCheck.get(),
                            this.recovery.get(),
                            this.rotation.get(),
                            this.smoothTurnTicks.get(),
                            this.smoothReturnTicks.get());
                });
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoMLG: "
                        + statusText()
                        + ", fall distance: "
                        + this.threshold.get()
                        + ", predict ticks: "
                        + this.predictTicks.get()
                        + ", solid check: "
                        + this.solidCheck.get()
                        + ", recovery: "
                        + this.recovery.get()
                        + ".");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        if (this.enabled.get() == enabled) return 1;
        this.enabled.set(enabled);
        this.autoMlg.reset(client);
        ClientChat.send(client, "AutoMLG " + statusText() + ".");
        return 1;
    }

    public void shutdown(Minecraft client) {
        this.autoMlg.reset(client);
    }

    public int setThreshold(Minecraft client, double value) {
        this.threshold.set(value);
        ClientChat.send(client, "AutoMLG fall distance set to " + this.threshold.get() + ".");
        return 1;
    }

    public int setPredictTicks(Minecraft ignoredClient, int value) {
        this.predictTicks.set(value);
        return 1;
    }

    public int setSolidCheck(Minecraft ignoredClient, boolean value) {
        this.solidCheck.set(value);
        return 1;
    }

    public int setRecovery(Minecraft ignoredClient, boolean value) {
        this.recovery.set(value);
        return 1;
    }

    @Override
    public String statusTag() {
        return this.rotation.serialized();
    }

    public int setRotation(Minecraft client, String mode) {
        this.rotation.deserialize(mode);
        return 1;
    }

    public boolean smoothRotationSelected() {
        return this.rotation.get() == SilentPacketRotation.Mode.SMOOTH;
    }

    public int setSmoothTurnTicks(Minecraft ignoredClient, int value) {
        this.smoothTurnTicks.set(value);
        return 1;
    }

    public int setSmoothReturnTicks(Minecraft ignoredClient, int value) {
        this.smoothReturnTicks.set(value);
        return 1;
    }

    private String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    private static boolean ready(Minecraft client) {
        if (!ClientReady.gameplay(client)) return false;
        if (client.player.isCreative() || client.player.isSpectator()) return false;
        return !client.player.getAbilities().invulnerable && !client.player.getAbilities().flying;
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("nofall.enabled", false);
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "nofall",
                new Setting[] {
                    rotation.describeStored("rotation", "Rotation", this::setRotation),
                    smoothTurnTicks
                            .describeStored(
                                    "smooth_turn_ticks",
                                    "Turn smoothing (ticks)",
                                    1,
                                    this::setSmoothTurnTicks)
                            .visibleWhen(this::smoothRotationSelected),
                    smoothReturnTicks
                            .describeStored(
                                    "smooth_return_ticks",
                                    "Return time (ticks)",
                                    1,
                                    this::setSmoothReturnTicks)
                            .visibleWhen(this::smoothRotationSelected),
                    threshold.describeStored("threshold", "Fall distance", .1, this::setThreshold),
                    predictTicks.describeStored(
                            "predict_ticks", "Predict ticks", 1, this::setPredictTicks),
                    solidCheck.describeStored("solid_check", "Solid check", this::setSolidCheck),
                    recovery.describeStored("recovery", "Recovery", this::setRecovery)
                });
    }
}
