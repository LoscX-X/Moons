package com.blanoir.tempos.client.config.settings;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.SettingFactory;

import java.util.List;

public final class StringSetting {
    private final String key;
    private final String defaultValue;
    private String value;
    private long valuesEpoch;

    private StringSetting(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.value = Settings.getString(key, defaultValue);
        this.valuesEpoch = Settings.valuesEpoch();
    }

    public String get() {
        long current = Settings.valuesEpoch();
        if (valuesEpoch != current) {
            value = Settings.getString(key, defaultValue);
            valuesEpoch = current;
        }
        return value;
    }

    public void set(String value) {
        this.value = value;
        Settings.setString(key, value);
    }

    public ModuleModel.Setting describeChoice(
            String id, String label, List<String> choices, ModuleModel.TextSetter setter) {
        return SettingFactory.choice(id, label, key, defaultValue, choices, setter);
    }

    public ModuleModel.Setting describeText(
            String id, String label, ModuleModel.TextSetter setter) {
        return SettingFactory.text(id, label, key, defaultValue, setter);
    }

    public static final class Builder {
        private String key;
        private String defaultValue;

        public Builder name(String key) {
            this.key = key;
            return this;
        }

        public Builder defaultValue(String value) {
            this.defaultValue = value;
            return this;
        }

        public StringSetting build() {
            if (key == null || key.isBlank())
                throw new IllegalStateException("Setting name cannot be empty.");
            return new StringSetting(key, defaultValue);
        }
    }
}
