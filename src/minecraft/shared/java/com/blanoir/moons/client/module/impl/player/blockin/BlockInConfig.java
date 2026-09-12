package com.blanoir.moons.client.module.impl.player.blockin;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class BlockInConfig {
    private static final BooleanSetting ENABLED = bool("enabled", false);
    private static final BooleanSetting ROOF = bool("roof", true);
    private static final BooleanSetting FLOOR = bool("floor", true);
    private static final BooleanSetting AUTO_DISABLE = bool("autoDisable", true);
    private static final IntSetting DELAY_MIN = delay("delayMinMs", 0);
    private static final IntSetting DELAY_MAX = delay("delayMaxMs", 50);
    private static final StringSetting BLOCKS =
            new StringSetting.Builder().name("blockin.blocks").defaultValue("[]").build();

    private BlockInConfig() {}

    public static boolean enabled() {
        return ENABLED.get();
    }

    static void enabled(boolean value) {
        ENABLED.set(value);
    }

    public static boolean roof() {
        return ROOF.get();
    }

    public static boolean floor() {
        return FLOOR.get();
    }

    public static boolean autoDisable() {
        return AUTO_DISABLE.get();
    }

    public static ModuleRegistry.Setting[] settings() {
        return new ModuleRegistry.Setting[] {
            ROOF.describe(
                    "roof",
                    "Roof",
                    (client, value) -> {
                        ROOF.set(value);
                        BlockInRuntime.restart(client);
                        return 1;
                    }),
            FLOOR.describe(
                    "floor",
                    "Fill floor",
                    (client, value) -> {
                        FLOOR.set(value);
                        BlockInRuntime.restart(client);
                        return 1;
                    }),
            AUTO_DISABLE.describe(
                    "auto_disable",
                    "Auto disable",
                    (client, value) -> {
                        AUTO_DISABLE.set(value);
                        return 1;
                    }),
            ModuleRegistry.rangeValue(
                            "delay_ms",
                            "Place delay (ms)",
                            () -> Math.min(DELAY_MIN.get(), DELAY_MAX.get()),
                            () -> Math.max(DELAY_MIN.get(), DELAY_MAX.get()),
                            0,
                            1000,
                            1,
                            (client, low, high) -> setDelay((int) low, (int) high))
                    .withDefault(0, 50),
            RegistryLists.setting(
                    "blocks",
                    "Block priority",
                    "item",
                    BlockInConfig::blocks,
                    BlockInConfig::setBlocks,
                    new JsonArray())
        };
    }

    private static int setDelay(int first, int second) {
        DELAY_MIN.set(Math.min(first, second));
        DELAY_MAX.set(Math.max(first, second));
        return 1;
    }

    static int nextDelayMs() {
        int low = Math.min(DELAY_MIN.get(), DELAY_MAX.get());
        int high = Math.max(DELAY_MIN.get(), DELAY_MAX.get());
        return ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    public static JsonArray blocks() {
        try {
            JsonElement value = JsonParser.parseString(BLOCKS.get());
            if (RegistryLists.valid("item_list", value)) return value.getAsJsonArray();
        } catch (RuntimeException ignored) {
        }
        return new JsonArray();
    }

    static List<Identifier> priorities() {
        return blocks().asList().stream()
                .map(entry -> Identifier.parse(entry.getAsJsonObject().get("id").getAsString()))
                .toList();
    }

    private static void setBlocks(Minecraft client, JsonElement value) {
        if (!RegistryLists.valid("item_list", value))
            throw new IllegalArgumentException("Invalid block list");
        BLOCKS.set(value.toString());
        BlockInRuntime.restart(client);
    }

    private static BooleanSetting bool(String name, boolean fallback) {
        return new BooleanSetting.Builder().name("blockin." + name).defaultValue(fallback).build();
    }

    private static IntSetting delay(String name, int fallback) {
        return new IntSetting.Builder()
                .name("blockin." + name)
                .defaultValue(fallback)
                .range(0, 1000)
                .build();
    }
}
