package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.player.automlg.AutoMlgRuntime;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;

public final class AutoMLG {
    // Legacy storage/module ids are retained for existing profiles and key bindings.
    private static final AutoMlgRuntime AUTO_MLG = new AutoMlgRuntime();
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("nofall.enabled").defaultValue(false).build();
    private static final DoubleSetting THRESHOLD =
            new DoubleSetting.Builder()
                    .name("nofall.threshold")
                    .defaultValue(3.0D)
                    .range(1.0D, 10.0D)
                    .build();
    private static final IntSetting PREDICT_TICKS =
            new IntSetting.Builder()
                    .name("nofall.predictTicks")
                    .defaultValue(2)
                    .range(1, 5)
                    .build();
    private static final BooleanSetting SOLID_CHECK =
            new BooleanSetting.Builder().name("nofall.solidCheck").defaultValue(true).build();
    private static final BooleanSetting RECOVERY =
            new BooleanSetting.Builder().name("nofall.recovery").defaultValue(true).build();

    private AutoMLG() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoMLG.context", event -> AUTO_MLG.reset(null));
        EventBus.PLAYER_UPDATE.register(
                "AutoMLG.playerUpdate",
                event -> {
                    Minecraft client = event.client();
                    if (!ENABLED.get() || !ready(client)) {
                        AUTO_MLG.reset(client);
                        return;
                    }
                    AUTO_MLG.tick(
                            client,
                            THRESHOLD.get(),
                            PREDICT_TICKS.get(),
                            SOLID_CHECK.get(),
                            RECOVERY.get());
                });
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoMLG: "
                        + statusText()
                        + ", fall distance: "
                        + THRESHOLD.get()
                        + ", predict ticks: "
                        + PREDICT_TICKS.get()
                        + ", solid check: "
                        + SOLID_CHECK.get()
                        + ", recovery: "
                        + RECOVERY.get()
                        + ".");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        if (ENABLED.get() == enabled) return 1;
        ENABLED.set(enabled);
        AUTO_MLG.reset(client);
        ClientChat.send(client, "AutoMLG " + statusText() + ".");
        return 1;
    }

    public static void shutdown(Minecraft client) {
        AUTO_MLG.reset(client);
    }

    public static int setThreshold(Minecraft client, double value) {
        THRESHOLD.set(value);
        ClientChat.send(client, "AutoMLG fall distance set to " + THRESHOLD.get() + ".");
        return 1;
    }

    public static int setPredictTicks(Minecraft ignoredClient, int value) {
        PREDICT_TICKS.set(value);
        return 1;
    }

    public static int setSolidCheck(Minecraft ignoredClient, boolean value) {
        SOLID_CHECK.set(value);
        return 1;
    }

    public static int setRecovery(Minecraft ignoredClient, boolean value) {
        RECOVERY.set(value);
        return 1;
    }

    public static String statusTag() {
        return "";
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static boolean ready(Minecraft client) {
        if (!ClientReady.gameplay(client)) return false;
        if (client.player.isCreative() || client.player.isSpectator()) return false;
        return !client.player.getAbilities().invulnerable && !client.player.getAbilities().flying;
    }
}
