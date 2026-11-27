package com.blanoir.tempos.client.config.settings;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.SettingFactory;

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

    public ModuleModel.Setting describe(String id, String label, ModuleModel.BoolSetter setter) {
        return SettingFactory.customBool(id, label, this::get, setter).withDefault(defaultValue);
    }

    public ModuleModel.Setting describeStored(
            String id, String label, ModuleModel.BoolSetter setter) {
        return SettingFactory.bool(id, label, key, defaultValue, setter);
    }

    public boolean storedValue() {
        return Settings.getBoolean(key, defaultValue);
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
