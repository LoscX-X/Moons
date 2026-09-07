package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.math.RandomMath;

import net.minecraft.IdentifierException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

public final class ChestStealer {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("cheststealer.enabled").defaultValue(false).build();

    private static final StringSetting ITEMS =
            new StringSetting.Builder().name("cheststealer.items").defaultValue("").build();

    private static final IntSetting MIN_MISS =
            new IntSetting.Builder()
                    .name("cheststealer.miss.min")
                    .defaultValue(0)
                    .range(0, 10_000)
                    .build();

    private static final IntSetting MAX_MISS =
            new IntSetting.Builder()
                    .name("cheststealer.miss.max")
                    .defaultValue(0)
                    .range(0, 10_000)
                    .build();

    private static final Set<Identifier> lockedItems = loadLockedItems();
    private static AbstractContainerMenu lastHandler;
    private static long nextStealAtMs;

    private ChestStealer() {}

    public static void init() {
        EventBus.TICK.register(
                "ChestStealer.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private static void tick(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        var currentGameMode = client == null ? null : client.gameMode;
        if (!ENABLED.get() || client == null || currentPlayer == null || currentGameMode == null) {
            resetScreenState();
            return;
        }

        // Only steal from chest-like storage (chests, barrels, ender chests, shulker boxes).
        // Crafting tables, furnaces, anvils and other utility screens are ignored.
        if (!(MinecraftClientAccess.screen(client) instanceof ContainerScreen)
                && !(MinecraftClientAccess.screen(client) instanceof ShulkerBoxScreen)) {
            resetScreenState();
            return;
        }

        AbstractContainerMenu handler =
                ((AbstractContainerScreen<?>) MinecraftClientAccess.screen(client)).getMenu();
        long now = System.currentTimeMillis();

        if (handler != lastHandler) {
            lastHandler = handler;
            nextStealAtMs = now + randomMissMs();
            return;
        }

        if (now < nextStealAtMs) {
            return;
        }

        OptionalInt slotIndex = findNextContainerSlotIndex(handler);
        if (slotIndex.isEmpty()) {
            return;
        }

        currentGameMode.handleContainerInput(
                handler.containerId,
                slotIndex.getAsInt(),
                0,
                ContainerInput.QUICK_MOVE,
                currentPlayer);

        nextStealAtMs = now + randomMissMs();
    }

    private static OptionalInt findNextContainerSlotIndex(AbstractContainerMenu handler) {
        int playerInventoryStart = Math.max(0, handler.slots.size() - 36);

        for (int index = 0; index < playerInventoryStart; index++) {
            Slot slot = handler.slots.get(index);
            ItemStack stack = slot.getItem();

            if (!stack.isEmpty() && shouldSteal(stack)) {
                return OptionalInt.of(index);
            }
        }

        return OptionalInt.empty();
    }

    private static boolean shouldSteal(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return lockedItems.isEmpty() || lockedItems.contains(id);
    }

    private static void resetScreenState() {
        lastHandler = null;
        nextStealAtMs = 0L;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "ChestStealer: "
                        + statusText()
                        + ", miss: "
                        + missText()
                        + ", locked items: "
                        + lockedItemsText()
                        + ". Usage: .moons cheststealer <enable|disable|add item> or .moons cheststealmiss <ms|ms-ms>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        resetScreenState();
        ClientChat.send(client, "ChestStealer " + statusText() + ". Miss: " + missText() + ".");
        return 1;
    }

    public static int setMiss(Minecraft client, String missRange) {
        int[] range = parseMissRange(missRange);

        if (range == null) {
            ClientChat.send(
                    client,
                    "Invalid cheststealmiss value. Use milliseconds, e.g. .moons cheststealmiss 150 or .moons cheststealmiss 100-250.");
            return 0;
        }

        MIN_MISS.set(range[0]);
        MAX_MISS.set(range[1]);

        resetScreenState();
        ClientChat.send(client, "ChestStealer miss set to " + missText() + ".");
        return 1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static String missText() {
        return MIN_MISS.get() == MAX_MISS.get()
                ? MIN_MISS.get() + "ms"
                : MIN_MISS.get() + "-" + MAX_MISS.get() + "ms";
    }

    private static String lockedItemsText() {
        if (lockedItems.isEmpty()) {
            return "all";
        }

        return lockedItems.stream().map(Identifier::toString).collect(Collectors.joining(", "));
    }

    private static Set<Identifier> loadLockedItems() {
        String value = ITEMS.get();

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(ChestStealer::normalizeItemId)
                .filter(id -> id != null)
                .filter(ChestStealer::itemExists)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Identifier normalizeItemId(String itemName) {
        String normalized = itemName.trim().toLowerCase();

        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }

        try {
            return Identifier.parse(normalized);
        } catch (IdentifierException exception) {
            return null;
        }
    }

    private static boolean itemExists(Identifier id) {
        return BuiltInRegistries.ITEM.getOptional(id).isPresent();
    }

    private static int[] parseMissRange(String value) {
        String[] parts = value.trim().split("-", 2);

        try {
            int min = Integer.parseInt(parts[0].trim());
            int max = parts.length == 1 ? min : Integer.parseInt(parts[1].trim());

            if (min > max) {
                int temp = min;
                min = max;
                max = temp;
            }

            return new int[] {min, max};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int randomMissMs() {
        int minMissMs = MIN_MISS.get();
        int maxMissMs = MAX_MISS.get();

        if (minMissMs == maxMissMs) {
            return minMissMs;
        }

        return RandomMath.betweenInclusive(minMissMs, maxMissMs);
    }
}
