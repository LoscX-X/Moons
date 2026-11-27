package com.blanoir.tempos.client.module.framework;

import com.blanoir.tempos.client.config.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Module descriptors, setting values, and their callback contracts. */
public final class ModuleModel {
    private ModuleModel() {}

    static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    static boolean safeEnabled(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static Module module(
            String id,
            String name,
            ModuleCategory category,
            BooleanSupplier enabled,
            Toggle toggle,
            Supplier<String> tag,
            Setting... settings) {
        String normalized = normalizeId(id);
        List<Setting> allSettings = new ArrayList<>(List.of(settings));
        allSettings.add(
                SettingFactory.bool(
                        "hide",
                        "Hide from HUD",
                        "module." + normalized + ".hide",
                        false,
                        (client, value) -> {
                            Settings.setBoolean("module." + normalized + ".hide", value);
                            return 1;
                        }));
        return new Module(
                normalized,
                name,
                category,
                enabled,
                toggle,
                tag,
                List.copyOf(allSettings),
                SettingFactory.defaultEnabled(enabled));
    }

    public record Module(
            String id,
            String name,
            ModuleCategory category,
            BooleanSupplier enabled,
            Toggle toggle,
            Supplier<String> tag,
            List<Setting> settings,
            boolean defaultEnabled,
            HudTag hudTag,
            String description) {
        public Module(
                String id,
                String name,
                ModuleCategory category,
                BooleanSupplier enabled,
                Toggle toggle,
                Supplier<String> tag,
                List<Setting> settings,
                boolean defaultEnabled) {
            this(
                    id,
                    name,
                    category,
                    enabled,
                    toggle,
                    tag,
                    settings,
                    defaultEnabled,
                    new HudTag(tag, List.of()),
                    "");
        }

        public Module withHudTag(Supplier<String> text, String... widthSamples) {
            return new Module(
                    id,
                    name,
                    category,
                    enabled,
                    toggle,
                    tag,
                    settings,
                    defaultEnabled,
                    new HudTag(text, List.of(widthSamples)),
                    description);
        }

        public Module withHudTag(String text) {
            return withHudTag(() -> text);
        }

        public Module withDefaultEnabled(boolean value) {
            return new Module(
                    id, name, category, enabled, toggle, tag, settings, value, hudTag, description);
        }

        public Module withDescription(String value) {
            return new Module(
                    id,
                    name,
                    category,
                    enabled,
                    toggle,
                    tag,
                    settings,
                    defaultEnabled,
                    hudTag,
                    value);
        }

        public String displayText() {
            String currentTag = tag.get();
            return currentTag == null || currentTag.isBlank() ? name : name + " " + currentTag;
        }

        public JsonObject toJson() {
            return ModuleSnapshot.module(this);
        }
    }

    /** Compact HUD text is separate from diagnostic status used by commands and settings. */
    public record HudTag(Supplier<String> text, List<String> widthSamples) {
        public HudTag {
            widthSamples = List.copyOf(widthSamples);
        }
    }

    public record Setting(
            String id,
            String name,
            String type,
            Supplier<JsonElement> value,
            Double min,
            Double max,
            Double step,
            List<String> options,
            BooleanSupplier visibleWhen,
            BooleanSupplier enabledWhen,
            SettingApply apply,
            JsonElement defaultValue) {
        public Setting(
                String id,
                String name,
                String type,
                Supplier<JsonElement> value,
                Double min,
                Double max,
                Double step,
                List<String> options,
                SettingApply apply) {
            this(
                    id,
                    name,
                    type,
                    value,
                    min,
                    max,
                    step,
                    options,
                    () -> true,
                    () -> true,
                    apply,
                    null);
        }

        public Setting withDefault(JsonElement value) {
            return new Setting(
                    id,
                    name,
                    type,
                    this.value,
                    min,
                    max,
                    step,
                    options,
                    visibleWhen,
                    enabledWhen,
                    apply,
                    Objects.requireNonNull(value).deepCopy());
        }

        public Setting withDefault(boolean value) {
            return withDefault(new JsonPrimitive(value));
        }

        public Setting withDefault(Number value) {
            return withDefault(new JsonPrimitive(value));
        }

        public Setting withDefault(String value) {
            return withDefault(new JsonPrimitive(value));
        }

        public Setting withDefault(Number low, Number high) {
            JsonArray range = new JsonArray();
            range.add(low);
            range.add(high);
            return withDefault(range);
        }

        public Setting visibleWhen(BooleanSupplier condition) {
            return new Setting(
                    id,
                    name,
                    type,
                    value,
                    min,
                    max,
                    step,
                    options,
                    condition,
                    enabledWhen,
                    apply,
                    defaultValue);
        }

        public Setting enabledWhen(BooleanSupplier condition) {
            return new Setting(
                    id,
                    name,
                    type,
                    value,
                    min,
                    max,
                    step,
                    options,
                    visibleWhen,
                    condition,
                    apply,
                    defaultValue);
        }

        public boolean isVisible() {
            return safeEnabled(visibleWhen);
        }

        public boolean isEnabled() {
            return isVisible() && safeEnabled(enabledWhen);
        }

        public JsonObject toJson() {
            return ModuleSnapshot.setting(this);
        }
    }

    @FunctionalInterface
    public interface Toggle {
        int apply(Minecraft ignoredClient, boolean value);
    }

    @FunctionalInterface
    public interface BoolSetter {
        int apply(Minecraft ignoredClient, boolean value);
    }

    @FunctionalInterface
    public interface DoubleSetter {
        int apply(Minecraft ignoredClient, double value);
    }

    @FunctionalInterface
    public interface IntSetter {
        int apply(Minecraft ignoredClient, int value);
    }

    @FunctionalInterface
    public interface TextSetter {
        int apply(Minecraft ignoredClient, String value);
    }

    @FunctionalInterface
    public interface NumericSetter {
        void apply(Minecraft ignoredClient, double value);
    }

    @FunctionalInterface
    public interface IntRangeSetter {
        int apply(Minecraft ignoredClient, int min, int max);
    }

    @FunctionalInterface
    public interface DoubleRangeSetter {
        int apply(Minecraft ignoredClient, double min, double max);
    }

    @FunctionalInterface
    public interface ColorSetter {
        void apply(Minecraft ignoredClient, int[] rgb);
    }

    @FunctionalInterface
    public interface SettingApply {
        void apply(Minecraft ignoredClient, JsonElement value);
    }
}
