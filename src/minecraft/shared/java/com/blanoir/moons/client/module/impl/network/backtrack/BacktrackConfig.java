package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;

import net.minecraft.client.Minecraft;

import java.util.List;

/** Only user-facing choices live here. Combat policy and rendering defaults are internal. */
public final class BacktrackConfig {
    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("backtrack.enabled").defaultValue(false).build();
    // Reuse the old upper bounds so saved delay/range preferences keep working.
    private final IntSetting delay =
            new IntSetting.Builder()
                    .name("backtrack.delay.max")
                    .defaultValue(200)
                    .range(0, 1000)
                    .build();
    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("backtrack.range.max")
                    .defaultValue(6.0)
                    .range(0.0, 10.0)
                    .build();
    private final ModeSetting<EspMode> esp =
            new ModeSetting.Builder<EspMode>()
                    .name("backtrack.esp")
                    .defaultValue(EspMode.BOX)
                    .option(EspMode.BOX, "box")
                    .option(EspMode.MODEL, "model")
                    .option(EspMode.WIREFRAME, "wireframe")
                    .option(EspMode.NONE, "none")
                    .build();

    public boolean enabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public int delayMillis() {
        return delay.get();
    }

    public double maxRange() {
        double value = range.get();
        return Double.isFinite(value) ? value : 6.0;
    }

    EspMode espMode() {
        return esp.get();
    }

    public List<String> espModeOptions() {
        return esp.optionIds();
    }

    public int setDelay(Minecraft client, String raw) {
        Integer value;
        try {
            value = Integer.valueOf(raw == null ? "" : raw.trim());
        } catch (NumberFormatException exception) {
            value = null;
        }
        if (value == null || value < 0 || value > 1000) {
            ClientChat.send(client, "Invalid Backtrack delay. Use 0-1000 ms.");
            return 0;
        }
        Settings.beginBatch();
        try {
            delay.set(value);
            removeLegacyTuning();
        } finally {
            Settings.endBatch();
        }
        ClientChat.send(client, "Backtrack delay set to " + value + " ms.");
        return 1;
    }

    public int setRange(Minecraft client, String raw) {
        Double value;
        try {
            value = Double.valueOf(raw == null ? "" : raw.trim());
        } catch (NumberFormatException exception) {
            value = null;
        }
        if (value == null || !Double.isFinite(value) || value < 0 || value > 10) {
            ClientChat.send(client, "Invalid Backtrack max range. Use 0-10 blocks.");
            return 0;
        }
        Settings.beginBatch();
        try {
            range.set(value);
            removeLegacyTuning();
        } finally {
            Settings.endBatch();
        }
        ClientChat.send(client, "Backtrack max range set to " + value + ".");
        return 1;
    }

    public int setEsp(Minecraft client, String value) {
        if (!esp.tryDeserialize(value)) {
            ClientChat.send(client, "Invalid Backtrack ESP. Use box, model, wireframe or none.");
            return 0;
        }
        ClientChat.send(client, "Backtrack ESP set to " + esp.serialized() + ".");
        return 1;
    }

    private static void removeLegacyTuning() {
        for (String key :
                List.of(
                        "range.min",
                        "delay.min",
                        "nextBacktrackDelay.min",
                        "nextBacktrackDelay.max",
                        "trackingBuffer",
                        "chance",
                        "targetMode",
                        "pauseOnHurtTime.enabled",
                        "pauseOnHurtTime.hurtTime",
                        "lastAttackTimeToWork",
                        "maxQueueSize",
                        "speedFactor",
                        "pingRatio",
                        "actionbar")) {
            Settings.remove("backtrack." + key);
        }
    }

    enum EspMode {
        BOX,
        MODEL,
        WIREFRAME,
        NONE
    }
}
