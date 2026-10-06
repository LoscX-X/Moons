package com.blanoir.moons.client.config.settings;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

public final class BooleanSetting {
    private final String key;
    private final boolean defaultValue;
    private volatile boolean value;
    private long valuesEpoch;

    private BooleanSetting(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.value = Settings.getBoolean(key, defaultValue);
        this.valuesEpoch = Settings.valuesEpoch();
    }

    public boolean get() {
        long current = Settings.valuesEpoch();
        if (valuesEpoch != current) {
            value = Settings.getBoolean(key, defaultValue);
            valuesEpoch = current;
        }
        return value;
    }

    public void set(boolean value) {
        this.value = value;
        Settings.setBoolean(key, value);
    }

    public ModuleRegistry.Setting describe(
            String id, String label, ModuleRegistry.BoolSetter setter) {
        return ModuleRegistry.customBool(id, label, this::get, setter).withDefault(defaultValue);
    }

    public static final class Builder {
        private String key;
        private boolean defaultValue;

        public Builder name(String key) {
            this.key = key;
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder spec(SettingSpec<Boolean> spec) {
            return name(spec.key()).defaultValue(spec.defaultValue());
        }

        public Builder defaultValue(boolean value) {
            this.defaultValue = value;
            return this;
        }

        public BooleanSetting build() {
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Setting name cannot be empty.");
            }
            return new BooleanSetting(key, defaultValue);
        }
    }
}
