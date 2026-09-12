package com.blanoir.moons.client.config.settings;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import net.minecraft.util.Mth;

public final class DoubleSetting {
    private final String key;
    private final double defaultValue;
    private final double min;
    private final double max;
    private double value;

    private DoubleSetting(String key, double defaultValue, double min, double max) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        double configured = Settings.getDouble(key, defaultValue);
        this.value =
                Double.isFinite(configured) && configured >= min && configured <= max
                        ? configured
                        : defaultValue;
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
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("Setting value must be finite: " + key);
        this.value = Mth.clamp(value, min, max);
        Settings.setDouble(key, this.value);
    }

    /** GUI/config metadata reads the same value and bounds as the feature. */
    public ModuleRegistry.Setting describe(
            String id, String label, double step, ModuleRegistry.DoubleSetter setter) {
        return ModuleRegistry.numeric(id, label, "number", this::get, min, max, step, setter::apply)
                .withDefault(defaultValue);
    }

    public ModuleRegistry.Setting describeRange(
            String id,
            String label,
            DoubleSetting upper,
            double step,
            ModuleRegistry.TextSetter setter) {
        if (min != upper.min || max != upper.max)
            throw new IllegalArgumentException("Range bounds differ: " + key);
        return ModuleRegistry.rangeValue(
                        id,
                        label,
                        this::get,
                        upper::get,
                        min,
                        max,
                        step,
                        (client, low, high) ->
                                setter.apply(
                                        client, Double.toString(low) + "-" + Double.toString(high)))
                .withDefault(defaultValue, upper.defaultValue);
    }

    public static final class Builder {
        private String key;
        private double defaultValue;
        private double min = -Double.MAX_VALUE;
        private double max = Double.MAX_VALUE;

        public Builder name(String key) {
            this.key = key;
            this.defaultValue = defaultValue;
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
