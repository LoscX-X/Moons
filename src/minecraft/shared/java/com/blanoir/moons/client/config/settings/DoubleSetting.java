package com.blanoir.moons.client.config.settings;

import com.blanoir.moons.client.config.Settings;

import net.minecraft.util.Mth;

public final class DoubleSetting {
    private final String key;
    private final double min;
    private final double max;
    private double value;

    private DoubleSetting(String key, double defaultValue, double min, double max) {
        this.key = key;
        this.min = min;
        this.max = max;
        this.value = Mth.clamp(Settings.getDouble(key, defaultValue), min, max);
    }

    public double get() {
        return value;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public void set(double value) {
        this.value = Mth.clamp(value, min, max);
        Settings.setDouble(key, this.value);
    }

    public static final class Builder {
        private String key;
        private double defaultValue;
        private double min = -Double.MAX_VALUE;
        private double max = Double.MAX_VALUE;

        public Builder name(String key) {
            this.key = key;
            return this;
        }

        public Builder defaultValue(double value) {
            this.defaultValue = value;
            return this;
        }

        public Builder min(double value) {
            this.min = value;
            return this;
        }

        public Builder max(double value) {
            this.max = value;
            return this;
        }

        public Builder range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public DoubleSetting build() {
            if (key == null || key.isBlank())
                throw new IllegalStateException("Setting name cannot be empty.");
            if (min > max)
                throw new IllegalStateException("Setting min cannot be greater than max.");
            return new DoubleSetting(key, defaultValue, min, max);
        }
    }
}
