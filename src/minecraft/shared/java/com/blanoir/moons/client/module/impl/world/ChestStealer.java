package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.registry.RegistryLists;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class ChestStealer {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("cheststealer.enabled").defaultValue(false).build();

    private static final BooleanSetting ALL =
            new BooleanSetting.Builder().name("cheststealer.all").defaultValue(true).build();
    private static final ModeSetting<String> MODE =
            new ModeSetting.Builder<String>()
                    .name("cheststealer.mode")
                    .defaultValue("legit")
                    .option("legit", "legit")
                    .option("blatant", "blatant")
                    .build();
    private static final BooleanSetting AUTO_CLOSE =
            new BooleanSetting.Builder().name("cheststealer.autoClose").defaultValue(true).build();

    private static final StringSetting ITEMS =
            new StringSetting.Builder().name("cheststealer.items").defaultValue("").build();

    private static final IntSetting DELAY_MIN_MS =
            new IntSetting.Builder()
                    .name("cheststealer.delayMinMs")
                    .defaultValue(legacyDelay(100))
                    .range(0, 1000)
                    .build();
    private static final IntSetting DELAY_MAX_MS =
            new IntSetting.Builder()
                    .name("cheststealer.delayMaxMs")
                    .defaultValue(legacyDelay(150))
                    .range(0, 1000)
                    .build();

    private static final Set<Identifier> lockedItems = loadLockedItems();
    private static AbstractContainerMenu lastHandler;
    private static long nextStealAtNanos;
    private static boolean tookItems;
    private static int closeAfterTick = Integer.MIN_VALUE;

    private ChestStealer() {}

    public static void init() {
        EventBus.TICK.register(
                "ChestStealer.tick",
                event -> {
                    if (!legitMode()) tick(event.client());
                });
        EventBus.FRAME.register(
                "ChestStealer.frame",
                event -> {
                    if (legitMode()) tick(event.client());
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
        long now = System.nanoTime();

        if (handler != lastHandler) {
            lastHandler = handler;
            nextStealAtNanos = now + nextDelayMs() * 1_000_000L;
            tookItems = false;
            closeAfterTick = Integer.MIN_VALUE;
        }

        if (!legitMode()) {
            if (closeAfterTick != Integer.MIN_VALUE) {
                closeIfFinished(client, handler);
                return;
            }
            stealBlatant(client, handler);
            closeIfFinished(client, handler);
            return;
        }

        OptionalInt slotIndex = findNextContainerSlotIndex(handler);
        if (slotIndex.isEmpty()) {
            closeIfFinished(client, handler);
            return;
        }
        if (now - nextStealAtNanos < 0L) {
            return;
        }

        Slot source = handler.slots.get(slotIndex.getAsInt());
        int before = source.getItem().getCount();
        currentGameMode.handleContainerInput(
                handler.containerId,
                slotIndex.getAsInt(),
                0,
                ContainerInput.QUICK_MOVE,
                currentPlayer);
        tookItems |= source.getItem().getCount() < before;

        nextStealAtNanos = System.nanoTime() + nextDelayMs() * 1_000_000L;
        closeIfFinished(client, handler);
    }

    private static void stealBlatant(Minecraft client, AbstractContainerMenu handler) {
        int inventoryStart = handler.slots.size() - 36;
        int hotbarStart = handler.slots.size() - 9;
        // Each vanilla click predicts the changed slots synchronously. The next
        // SWAP therefore uses the updated inventory in this same client tick.
        for (int source = 0; source < inventoryStart; source++) {
            Slot slot = handler.slots.get(source);
            if (!slot.hasItem() || !shouldSteal(slot.getItem()) || !slot.mayPickup(client.player))
                continue;

            int hotbar = findEmptySlot(handler, hotbarStart, handler.slots.size());
            if (hotbar < 0) {
                int storage = findEmptySlot(handler, inventoryStart, hotbarStart);
                if (storage < 0) return;
                hotbar = hotbarStart;
                // Make an empty hotbar slot without swapping owned items into
                // the chest. SWAP buttons address hotbar indices, not menu slots.
                swap(client, handler, storage, hotbar - hotbarStart);
                if (handler.slots.get(hotbar).hasItem()) return;
            }
            swap(client, handler, source, hotbar - hotbarStart);
            tookItems |= !slot.hasItem();
        }
    }

    private static int findEmptySlot(AbstractContainerMenu handler, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!handler.slots.get(index).hasItem()) return index;
        }
        return -1;
    }

    private static void swap(
            Minecraft client, AbstractContainerMenu handler, int slot, int hotbar) {
        client.gameMode.handleContainerInput(
                handler.containerId, slot, hotbar, ContainerInput.SWAP, client.player);
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
        return ALL.get() || lockedItems.contains(id);
    }

    private static void closeIfFinished(Minecraft client, AbstractContainerMenu handler) {
        // Finish a real looting session; an opening menu can be temporarily empty
        // before its contents arrive. Failed/full-inventory clicks do not finish it.
        if (!AUTO_CLOSE.get() || !tookItems) {
            closeAfterTick = Integer.MIN_VALUE;
            return;
        }
        for (int index = 0; index < handler.slots.size() - 36; index++) {
            ItemStack stack = handler.slots.get(index).getItem();
            if (!stack.isEmpty() && shouldSteal(stack)) {
                closeAfterTick = Integer.MIN_VALUE;
                return;
            }
        }
        // Keep the menu open through the batch's movement tick. Recheck its
        // contents on the next tick before closing; late slot updates can add work.
        if (!legitMode()) {
            if (closeAfterTick == Integer.MIN_VALUE) {
                closeAfterTick = client.player.tickCount + 1;
                return;
            }
            if (client.player.tickCount - closeAfterTick < 0) return;
        }
        client.player.closeContainer();
        resetScreenState();
    }

    private static void resetScreenState() {
        lastHandler = null;
        nextStealAtNanos = 0L;
        tookItems = false;
        closeAfterTick = Integer.MIN_VALUE;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "ChestStealer: "
                        + statusText()
                        + ", mode: "
                        + modeName()
                        + ", delay: "
                        + delayMinMs()
                        + "-"
                        + delayMaxMs()
                        + "ms"
                        + ", locked items: "
                        + lockedItemsText()
                        + ".");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        resetScreenState();
        ClientChat.send(client, "ChestStealer " + statusText() + ". Mode: " + modeName() + ".");
        return 1;
    }

    public static int setDelayMs(Minecraft ignoredClient, int delayMs) {
        return setDelayRange(ignoredClient, delayMs, delayMs);
    }

    public static int setDelayRange(Minecraft ignoredClient, int minMs, int maxMs) {
        DELAY_MIN_MS.set(Math.min(minMs, maxMs));
        DELAY_MAX_MS.set(Math.max(minMs, maxMs));
        resetScreenState();
        return 1;
    }

    public static int delayMinMs() {
        return Math.min(DELAY_MIN_MS.get(), DELAY_MAX_MS.get());
    }

    public static int delayMaxMs() {
        return Math.max(DELAY_MIN_MS.get(), DELAY_MAX_MS.get());
    }

    private static int nextDelayMs() {
        return ThreadLocalRandom.current().nextInt(delayMinMs(), delayMaxMs() + 1);
    }

    private static int legacyDelay(int fallback) {
        return Math.clamp(Settings.getInt("cheststealer.delayMs", fallback), 0, 1000);
    }

    public static int setAll(Minecraft ignoredClient, boolean all) {
        ALL.set(all);
        resetScreenState();
        return 1;
    }

    public static int setMode(Minecraft client, String mode) {
        if (!MODE.tryDeserialize(mode)) {
            throw new IllegalArgumentException("ChestStealer mode must be legit or blatant.");
        }
        resetScreenState();
        return 1;
    }

    public static int setAutoClose(Minecraft ignoredClient, boolean autoClose) {
        AUTO_CLOSE.set(autoClose);
        return 1;
    }

    public static boolean legitMode() {
        return MODE.get().equals("legit");
    }

    public static boolean allItems() {
        return ALL.get();
    }

    public static String modeName() {
        return MODE.serialized();
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static com.google.gson.JsonArray selectedItems() {
        var result = new com.google.gson.JsonArray();
        lockedItems.forEach(id -> result.add(RegistryLists.entry(id.toString(), null)));
        return result;
    }

    public static void setSelectedItems(Minecraft client, com.google.gson.JsonElement value) {
        if (!RegistryLists.valid("item_list", value))
            throw new IllegalArgumentException("Invalid item list");
        lockedItems.clear();
        value.getAsJsonArray()
                .forEach(
                        entry ->
                                lockedItems.add(
                                        Identifier.parse(
                                                entry.getAsJsonObject().get("id").getAsString())));
        ITEMS.set(lockedItems.stream().map(Identifier::toString).collect(Collectors.joining(",")));
    }

    private static String lockedItemsText() {
        if (ALL.get()) return "all";
        if (lockedItems.isEmpty()) {
            return "none";
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
}
