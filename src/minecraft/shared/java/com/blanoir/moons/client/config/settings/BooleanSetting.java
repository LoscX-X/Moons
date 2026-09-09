package com.blanoir.moons.client.config.settings;

import com.blanoir.moons.client.config.Settings;

public final class BooleanSetting {
    private final String key;
    private volatile boolean value;

    private BooleanSetting(String key, boolean defaultValue) {
        this.key = key;
        this.value = Settings.getBoolean(key, defaultValue);
    }

    public boolean get() {
        return value;
    }

    public void set(boolean value) {
        this.value = value;
        Settings.setBoolean(key, value);
    }

    public static final class Builder {
        private String key;
        private boolean defaultValue;

        public Builder name(String key) {
            this.key = key;
            return this;
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
