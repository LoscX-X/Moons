package com.blanoir.moons.client.config.settings;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import net.minecraft.util.Mth;

public final class IntSetting {
    private final String key;
    private final int defaultValue;
    private final int min;
    private final int max;
    private int value;

    private IntSetting(String key, int defaultValue, int min, int max) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        int configured = Settings.getInt(key, defaultValue);
        this.value = configured >= min && configured <= max ? configured : defaultValue;
    }

    public int get() {
        return value;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public void set(int value) {
        this.value = Mth.clamp(value, min, max);
        Settings.setInt(key, this.value);
    }

    public ModuleRegistry.Setting describe(
            String id, String label, int step, ModuleRegistry.IntSetter setter) {
        return ModuleRegistry.numeric(
                        id,
                        label,
                        "integer",
                        this::get,
                        min,
                        max,
                        step,
                        (client, value) -> setter.apply(client, (int) Math.round(value)))
                .withDefault(defaultValue);
    }

    public static final class Builder {
        private String key;
        private int defaultValue;
        private int min = Integer.MIN_VALUE;
        private int max = Integer.MAX_VALUE;

        public Builder name(String key) {
            this.key = key;
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder defaultValue(int value) {
            this.defaultValue = value;
            return this;
        }

        public Builder min(int value) {
            this.min = value;
            return this;
        }

        public Builder max(int value) {
            this.max = value;
            return this;
        }

        public Builder range(int min, int max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public IntSetting build() {
            if (key == null || key.isBlank())
                throw new IllegalStateException("Setting name cannot be empty.");
            if (min > max)
                throw new IllegalStateException("Setting min cannot be greater than max.");
            return new IntSetting(key, defaultValue, min, max);
        }
    }
}
