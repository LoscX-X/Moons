package com.blanoir.moons.client.config.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A typed, canonical string setting for module modes. */
public final class ModeSetting<T> {
    private final StringSetting storage;
    private final T defaultValue;
    private final Map<String, Option<T>> lookup;
    private final List<Option<T>> options;
    private final BooleanSupplier visibleWhen;
    private final BooleanSupplier enabledWhen;

    private ModeSetting(Builder<T> builder) {
        defaultValue = Objects.requireNonNull(builder.defaultValue, "defaultValue");
        options = List.copyOf(builder.options);
        if (options.isEmpty()) throw new IllegalStateException("A mode setting needs at least one option.");

        Map<String, Option<T>> modes = new LinkedHashMap<>();
        for (Option<T> option : options) {
            if (option.id().isEmpty() || modes.putIfAbsent(option.id(), option) != null) {
                throw new IllegalStateException("Duplicate or empty mode id: " + option.id());
            }
        }
        lookup = Map.copyOf(modes);
        Option<T> fallback = optionForValue(defaultValue);
        if (fallback == null) throw new IllegalStateException("Default mode is not a legal option.");

        storage = new StringSetting.Builder()
                .name(builder.key)
                .defaultValue(fallback.id())
                .build();
        visibleWhen = builder.visibleWhen;
        enabledWhen = builder.enabledWhen;
    }

    public T get() {
        return option(storage.get()).value();
    }

    public String serialized() {
        return option(storage.get()).id();
    }

    public void set(T value) {
        Option<T> option = optionForValue(value);
        storage.set((option == null ? optionForValue(defaultValue) : option).id());
    }

    public void deserialize(String value) {
        storage.set(option(value).id());
    }

    /** Command input rejects unknown values without replacing the current mode. */
    public boolean tryDeserialize(String value) {
        Option<T> found = lookup.get(normalize(value));
        if (found == null) return false;
        storage.set(found.id());
        return true;
    }

    public List<String> optionIds() {
        return options.stream().map(Option::id).toList();
    }

    public boolean isVisible() {
        return safe(visibleWhen);
    }

    public boolean isEnabled() {
        return isVisible() && safe(enabledWhen);
    }

    private Option<T> option(String raw) {
        Option<T> found = lookup.get(normalize(raw));
        return found != null ? found : Objects.requireNonNull(optionForValue(defaultValue));
    }

    private Option<T> optionForValue(T value) {
        for (Option<T> option : options) if (Objects.equals(option.value(), value)) return option;
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }

    private static boolean safe(BooleanSupplier condition) {
        try { return condition.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    public record Option<T>(T value, String id) {
        public Option {
            Objects.requireNonNull(value, "value");
            id = normalize(id);
        }
    }

    public static final class Builder<T> {
        private String key;
        private T defaultValue;
        private final List<Option<T>> options = new ArrayList<>();
        private BooleanSupplier visibleWhen = () -> true;
        private BooleanSupplier enabledWhen = () -> true;

        public Builder<T> name(String key) { this.key = key; return this; }
        public Builder<T> defaultValue(T value) { defaultValue = value; return this; }
        public Builder<T> option(T value, String id) {
            options.add(new Option<>(value, id));
            return this;
        }
        public Builder<T> visibleWhen(BooleanSupplier condition) {
            visibleWhen = Objects.requireNonNull(condition); return this;
        }
        public Builder<T> enabledWhen(BooleanSupplier condition) {
            enabledWhen = Objects.requireNonNull(condition); return this;
        }
        public ModeSetting<T> build() {
            if (key == null || key.isBlank()) throw new IllegalStateException("Setting name cannot be empty.");
            return new ModeSetting<>(this);
        }
    }
}
