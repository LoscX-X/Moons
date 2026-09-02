package com.blanoir.moons.client.config.settings;

import com.blanoir.moons.client.config.Settings;

public final class StringSetting {
    private final String key;
    private String value;

    private StringSetting(String key, String defaultValue) {
        this.key = key;
        this.value = Settings.getString(key, defaultValue);
    }

    public String get() { return value; }

    public void set(String value) {
        this.value = value;
        Settings.setString(key, value);
    }

    public static final class Builder {
        private String key;
        private String defaultValue;

        public Builder name(String key) { this.key = key; return this; }
        public Builder defaultValue(String value) { this.defaultValue = value; return this; }

        public StringSetting build() {
            if (key == null || key.isBlank()) throw new IllegalStateException("Setting name cannot be empty.");
            return new StringSetting(key, defaultValue);
        }
    }
}
