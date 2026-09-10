package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;

import net.minecraft.client.Minecraft;

import java.util.List;

/** The compact editor exposes core controls; saved advanced policy remains active. */
public final class BacktrackConfig {
    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("backtrack.enabled").defaultValue(false).build();
    // Reuse the old upper bounds so saved delay/range preferences keep working.
    private final IntSetting delay =
            new IntSetting.Builder()
                    .name("backtrack.delay.max")
                    .defaultValue(150)
                    .range(0, 1000)
                    .build();
    private final ModeSetting<TargetMode> targetMode =
            new ModeSetting.Builder<TargetMode>()
                    .name("backtrack.targetMode")
                    .defaultValue(TargetMode.ATTACK)
                    .option(TargetMode.ATTACK, "attack")
                    .option(TargetMode.RANGE, "range")
                    .option(TargetMode.INTENT, "intent")
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

    public int minDelayMillis() {
        return Math.clamp(Settings.getInt("backtrack.delay.min", 100), 0, delayMillis());
    }

    public double minRange() {
        return Math.clamp(Settings.getDouble("backtrack.range.min", 1.0), 0.0, maxRange());
    }

    public String targetModeName() {
        return targetMode.serialized();
    }

    public List<String> targetModeOptions() {
        return targetMode.optionIds();
    }

    TargetMode targetMode() {
        return targetMode.get();
    }

    int lastAttackMillis() {
        return Math.clamp(Settings.getInt("backtrack.lastAttackTimeToWork", 1000), 0, 5000);
    }

    int trackingBufferMillis() {
        return Math.clamp(Settings.getInt("backtrack.trackingBuffer", 500), 0, 2000);
    }

    double chance() {
        return Math.clamp(Settings.getDouble("backtrack.chance", 100), 0, 100);
    }

    int nextDelayMin() {
        return Math.clamp(Settings.getInt("backtrack.nextBacktrackDelay.min", 0), 0, 2000);
    }

    int nextDelayMax() {
        return Math.clamp(
                Settings.getInt("backtrack.nextBacktrackDelay.max", 10), nextDelayMin(), 2000);
    }

    boolean pauseOnHurt() {
        return Settings.getBoolean("backtrack.pauseOnHurtTime.enabled", false);
    }

    int hurtTime() {
        return Math.clamp(Settings.getInt("backtrack.pauseOnHurtTime.hurtTime", 3), 0, 10);
    }

    int queueLimit() {
        return Math.clamp(Settings.getInt("backtrack.maxQueueSize", 256), 32, 1024);
    }

    double speedFactor() {
        return Math.clamp(Settings.getDouble("backtrack.speedFactor", 8), 0, 30);
    }

    double pingRatio() {
        return Math.clamp(Settings.getDouble("backtrack.pingRatio", 0), 0, 3);
    }

    boolean actionBar() {
        return Settings.getBoolean("backtrack.actionbar", false);
    }

    public int setTargetMode(Minecraft client, String value) {
        return targetMode.tryDeserialize(value) ? 1 : 0;
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
        int low;
        int high;
        try {
            String[] values = (raw == null ? "" : raw.trim()).split("-", -1);
            if (values.length < 1 || values.length > 2) return 0;
            low = Integer.parseInt(values[0].trim());
            high = values.length == 1 ? low : Integer.parseInt(values[1].trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
        if (low < 0 || high > 1000 || low > high) {
            ClientChat.send(client, "Invalid Backtrack delay. Use 0-1000 ms.");
            return 0;
        }
        Settings.beginBatch();
        try {
            delay.set(high);
            Settings.setInt("backtrack.delay.min", low);
        } finally {
            Settings.endBatch();
        }
        ClientChat.send(client, "Backtrack delay set to " + low + "–" + high + " ms.");
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

    enum TargetMode {
        ATTACK,
        RANGE,
        INTENT;

        boolean acceptsAttackAge(long elapsed, int duration) {
            return this == INTENT || elapsed >= 0 && elapsed <= duration;
        }
    }

    enum EspMode {
        BOX,
        MODEL,
        WIREFRAME,
        NONE
    }
}
