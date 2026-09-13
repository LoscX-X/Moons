package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class InvManagerConfig {
    private static final BooleanSetting ENABLED = bool("enabled", false);
    private static final BooleanSetting SORT = bool("sort", true);
    private static final BooleanSetting REFILL = bool("refill", true);
    private static final BooleanSetting PROTECT_SPECIAL = bool("protectSpecial", true);
    private static final IntSetting OPEN_DELAY = delay("openDelayMs", 250);
    private static final IntSetting MANUAL_DELAY = delay("manualDelayMs", 500);
    private static final IntSetting DELAY_MIN = delay("delayMinMs", 100);
    private static final IntSetting DELAY_MAX = delay("delayMaxMs", 150);
    private static final List<ModeSetting<InventoryRole>> ROLES = createRoles();
    public static final String ONCE_KEY = "keybind.action.invmanager_once";

    private InvManagerConfig() {}

    public static boolean enabled() {
        return ENABLED.get();
    }

    static void enabled(boolean value) {
        ENABLED.set(value);
    }

    static boolean sort() {
        return SORT.get();
    }

    static boolean refill() {
        return REFILL.get();
    }

    static boolean protectSpecial() {
        return PROTECT_SPECIAL.get();
    }

    static int openDelay() {
        return OPEN_DELAY.get();
    }

    static int manualDelay() {
        return MANUAL_DELAY.get();
    }

    static int nextDelay() {
        return ThreadLocalRandom.current()
                .nextInt(
                        Math.min(DELAY_MIN.get(), DELAY_MAX.get()),
                        Math.max(DELAY_MIN.get(), DELAY_MAX.get()) + 1);
    }

    public static List<InventoryRole> roles() {
        return ROLES.stream().map(ModeSetting::get).toList();
    }

    public static void setRole(int slot, InventoryRole role) {
        if (slot < 0 || slot >= ROLES.size())
            throw new IllegalArgumentException("Invalid role slot");
        ROLES.get(slot).set(role);
    }

    public static String onceKey() {
        return Settings.getString(ONCE_KEY, "");
    }

    public static ModuleRegistry.Setting[] settings() {
        var result = new ArrayList<ModuleRegistry.Setting>();
        result.add(
                ModuleRegistry.text(
                                "item_rules",
                                "Item rules",
                                () -> Settings.getString("invmanager.rules", ""),
                                (client, value) -> {
                                    Settings.setString("invmanager.rules", value);
                                    return 1;
                                })
                        .withDefault("")
                        .visibleWhen(() -> false));
        for (int i = 0; i < 10; i++) {
            final String key = "invmanager.rule.slot." + i;
            result.add(
                    ModuleRegistry.text(
                                    "rule_slot_" + i,
                                    "Item group " + (i + 1),
                                    () -> Settings.getString(key, ""),
                                    (client, value) -> {
                                        Settings.setString(key, value);
                                        return 1;
                                    })
                            .withDefault("")
                            .visibleWhen(() -> false));
        }
        result.add(
                SORT.describe(
                        "sort",
                        "Sort hotbar",
                        (client, value) -> {
                            SORT.set(value);
                            return 1;
                        }));
        result.add(
                REFILL.describe(
                        "refill",
                        "Refill empty slots",
                        (client, value) -> {
                            REFILL.set(value);
                            return 1;
                        }));
        result.add(
                PROTECT_SPECIAL.describe(
                        "protect_special",
                        "Protect special items",
                        (client, value) -> {
                            PROTECT_SPECIAL.set(value);
                            return 1;
                        }));
        result.add(
                OPEN_DELAY.describe(
                        "open_delay",
                        "Open delay (ms)",
                        10,
                        (client, value) -> {
                            OPEN_DELAY.set(value);
                            return 1;
                        }));
        result.add(
                MANUAL_DELAY.describe(
                        "manual_delay",
                        "Manual pause (ms)",
                        10,
                        (client, value) -> {
                            MANUAL_DELAY.set(value);
                            return 1;
                        }));
        result.add(
                ModuleRegistry.rangeValue(
                                "delay_ms",
                                "Action delay (ms)",
                                () -> Math.min(DELAY_MIN.get(), DELAY_MAX.get()),
                                () -> Math.max(DELAY_MIN.get(), DELAY_MAX.get()),
                                0,
                                1000,
                                1,
                                (client, low, high) -> {
                                    DELAY_MIN.set((int) Math.min(low, high));
                                    DELAY_MAX.set((int) Math.max(low, high));
                                    return 1;
                                })
                        .withDefault(100, 150));
        for (int i = 0; i < ROLES.size(); i++) {
            int index = i;
            result.add(
                    ROLES.get(i)
                            .describe(
                                    "slot_" + (i + 1),
                                    i == 9 ? "Offhand" : "Slot " + (i + 1),
                                    (client, value) -> {
                                        if (!ROLES.get(index).tryDeserialize(value))
                                            throw new IllegalArgumentException("Unknown slot role");
                                        return 1;
                                    }));
        }
        return result.toArray(ModuleRegistry.Setting[]::new);
    }

    private static List<ModeSetting<InventoryRole>> createRoles() {
        InventoryRole[] defaults = {
            InventoryRole.SWORD,
            InventoryRole.BLOCK,
            InventoryRole.PICKAXE,
            InventoryRole.AXE,
            InventoryRole.FREE,
            InventoryRole.FREE,
            InventoryRole.PEARL,
            InventoryRole.BOW,
            InventoryRole.FOOD,
            InventoryRole.LOCKED
        };
        var roles = new ArrayList<ModeSetting<InventoryRole>>();
        for (int i = 0; i < defaults.length; i++) {
            var builder =
                    new ModeSetting.Builder<InventoryRole>()
                            .name("invmanager.slot." + i)
                            .defaultValue(defaults[i]);
            for (InventoryRole role : InventoryRole.values()) builder.option(role, role.id());
            roles.add(builder.build());
        }
        return List.copyOf(roles);
    }

    private static BooleanSetting bool(String name, boolean value) {
        return new BooleanSetting.Builder().name("invmanager." + name).defaultValue(value).build();
    }

    private static IntSetting delay(String name, int value) {
        return new IntSetting.Builder()
                .name("invmanager." + name)
                .defaultValue(value)
                .range(0, 1000)
                .build();
    }
}
