package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.SettingSpec;

import net.minecraft.client.Minecraft;

import java.util.List;

/** The compact editor exposes core controls; saved advanced policy remains active. */
public final class BacktrackConfig {
    private final BooleanSetting enabled =
            new BooleanSetting.Builder().spec(BacktrackSettings.ENABLED).build();
    // Reuse the old upper bounds so saved delay/range preferences keep working.
    private final IntSetting delay =
            new IntSetting.Builder().spec(BacktrackSettings.DELAY_MAX).build();
    private final ModeSetting<TargetMode> targetMode =
            new ModeSetting.Builder<TargetMode>().spec(BacktrackSettings.TARGET_MODE).build();
    private final DoubleSetting range =
            new DoubleSetting.Builder().spec(BacktrackSettings.RANGE_MAX).build();
    private final ModeSetting<EspMode> esp =
            new ModeSetting.Builder<EspMode>().spec(BacktrackSettings.ESP).build();

    private static boolean readBoolean(SettingSpec<Boolean> spec) {
        return Settings.getBoolean(spec.key(), spec.defaultValue());
    }

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
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.DELAY_MIN.key(),
                        BacktrackSettings.DELAY_MIN.defaultValue()),
                BacktrackSettings.DELAY_MIN.min(),
                delayMillis());
    }

    public double minRange() {
        return Math.clamp(
                Settings.getDouble(
                        BacktrackSettings.RANGE_MIN.key(),
                        BacktrackSettings.RANGE_MIN.defaultValue()),
                BacktrackSettings.RANGE_MIN.min(),
                maxRange());
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
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.LAST_ATTACK.key(),
                        BacktrackSettings.LAST_ATTACK.defaultValue()),
                BacktrackSettings.LAST_ATTACK.min(),
                BacktrackSettings.LAST_ATTACK.max());
    }

    int trackingBufferMillis() {
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.TRACKING_BUFFER.key(),
                        BacktrackSettings.TRACKING_BUFFER.defaultValue()),
                BacktrackSettings.TRACKING_BUFFER.min(),
                BacktrackSettings.TRACKING_BUFFER.max());
    }

    double chance() {
        return Math.clamp(
                Settings.getDouble(
                        BacktrackSettings.CHANCE.key(), BacktrackSettings.CHANCE.defaultValue()),
                BacktrackSettings.CHANCE.min(),
                BacktrackSettings.CHANCE.max());
    }

    int nextDelayMin() {
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.NEXT_MIN.key(),
                        BacktrackSettings.NEXT_MIN.defaultValue()),
                BacktrackSettings.NEXT_MIN.min(),
                BacktrackSettings.NEXT_MIN.max());
    }

    int nextDelayMax() {
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.NEXT_MAX.key(),
                        BacktrackSettings.NEXT_MAX.defaultValue()),
                nextDelayMin(),
                BacktrackSettings.NEXT_MAX.max());
    }

    boolean pauseOnHurt() {
        return readBoolean(BacktrackSettings.PAUSE_HURT);
    }

    int hurtTime() {
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.HURT_TIME.key(),
                        BacktrackSettings.HURT_TIME.defaultValue()),
                BacktrackSettings.HURT_TIME.min(),
                BacktrackSettings.HURT_TIME.max());
    }

    int queueLimit() {
        return Math.clamp(
                Settings.getInt(
                        BacktrackSettings.QUEUE_LIMIT.key(),
                        BacktrackSettings.QUEUE_LIMIT.defaultValue()),
                BacktrackSettings.QUEUE_LIMIT.min(),
                BacktrackSettings.QUEUE_LIMIT.max());
    }

    double pingRatio() {
        return Math.clamp(
                Settings.getDouble(
                        BacktrackSettings.PING_RATIO.key(),
                        BacktrackSettings.PING_RATIO.defaultValue()),
                BacktrackSettings.PING_RATIO.min(),
                BacktrackSettings.PING_RATIO.max());
    }

    boolean actionBar() {
        return readBoolean(BacktrackSettings.ACTION_BAR);
    }

    public int setTargetMode(Minecraft client, String value) {
        return targetMode.tryDeserialize(value) ? 1 : 0;
    }

    public double maxRange() {
        double value = range.get();
        return Double.isFinite(value) ? value : BacktrackSettings.RANGE_MAX.defaultValue();
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
        if (low < BacktrackSettings.DELAY_MIN.min()
                || high > BacktrackSettings.DELAY_MAX.max()
                || low > high) {
            ClientChat.send(client, "Invalid Backtrack delay. Use 0-1000 ms.");
            return 0;
        }
        Settings.beginBatch();
        try {
            delay.set(high);
            Settings.setInt(BacktrackSettings.DELAY_MIN.key(), low);
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
        if (value == null
                || !Double.isFinite(value)
                || value < BacktrackSettings.RANGE_MAX.min()
                || value > BacktrackSettings.RANGE_MAX.max()) {
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

    public enum TargetMode {
        ATTACK,
        RANGE,
        INTENT;

        boolean acceptsAttackAge(long elapsed, int duration) {
            return this == INTENT || elapsed >= 0 && elapsed <= duration;
        }
    }

    public enum EspMode {
        BOX,
        MODEL,
        WIREFRAME,
        NONE
    }
}
