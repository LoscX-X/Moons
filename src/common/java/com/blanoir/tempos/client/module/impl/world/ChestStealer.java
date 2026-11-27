package com.blanoir.tempos.client.module.impl.world;


import static com.blanoir.tempos.client.module.framework.SettingFactory.customChoice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.rangeValue;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.inventory.InventoryClickFailure;
import com.blanoir.tempos.client.manager.inventory.InventoryClicks;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.module.impl.world.cheststealer.ChestStealPlan;

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

@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Transfers items from an open container using the selected timing.")
public final class ChestStealer extends ClientFeature {
    public static final ChestStealer INSTANCE = new ChestStealer();

    private final InventoryClickFailure failure = new InventoryClickFailure("cheststealer");
    private long failureResumeAt;

    public com.blanoir.tempos.client.module.framework.ModuleModel.Setting failureSetting() {
        return this.failure.setting();
    }

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("cheststealer.enabled").defaultValue(false).build();

    private final BooleanSetting all =
            new BooleanSetting.Builder().name("cheststealer.all").defaultValue(true).build();
    private final ModeSetting<String> mode =
            new ModeSetting.Builder<String>()
                    .name("cheststealer.mode")
                    .defaultValue("legit")
                    .option("legit", "legit")
                    .option("blatant", "blatant")
                    .build();
    private final BooleanSetting autoClose =
            new BooleanSetting.Builder().name("cheststealer.autoClose").defaultValue(true).build();
    private final IntSetting closeDelayMinMs =
            new IntSetting.Builder()
                    .name("cheststealer.closeDelayMinMs")
                    .defaultValue(0)
                    .range(0, 5000)
                    .build();
    private final IntSetting closeDelayMaxMs =
            new IntSetting.Builder()
                    .name("cheststealer.closeDelayMaxMs")
                    .defaultValue(0)
                    .range(0, 5000)
                    .build();

    private final StringSetting items =
            new StringSetting.Builder().name("cheststealer.items").defaultValue("").build();

    private final IntSetting delayMinMs =
            new IntSetting.Builder()
                    .name("cheststealer.delayMinMs")
                    .defaultValue(legacyDelay(100))
                    .range(0, 1000)
                    .build();
    private final IntSetting delayMaxMs =
            new IntSetting.Builder()
                    .name("cheststealer.delayMaxMs")
                    .defaultValue(legacyDelay(150))
                    .range(0, 1000)
                    .build();

    private final Set<Identifier> lockedItems = loadLockedItems();
    private AbstractContainerMenu lastHandler;
    private long nextStealAtNanos;
    private boolean tookItems;
    private int closeAfterTick = Integer.MIN_VALUE;
    private long closeAtNanos;
    private boolean blatantStalled;
    private final Object inventoryOwner = new Object();

    private ChestStealer() {
        super("cheststealer", "ChestStealer");
    }

    @Override
    public void registerEvents() {
        InventoryClicks.init();
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "ChestStealer.context", event -> resetScreenState());
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

    private void tick(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        var currentGameMode = client == null ? null : client.gameMode;
        if (!this.enabled.get()
                || client == null
                || currentPlayer == null
                || currentGameMode == null) {
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
        if (currentPlayer.containerMenu != handler
                || !handler.getCarried().isEmpty()
                || InventoryClicks.busyExcept(this.inventoryOwner)) {
            resetCloseState();
            return;
        }
        long now = System.nanoTime();

        if (handler != this.lastHandler) {
            this.failure.reset();
            this.failureResumeAt = 0;
            this.lastHandler = handler;
            this.nextStealAtNanos = now + nextDelayMs() * 1_000_000L;
            this.tookItems = false;
            resetCloseState();
            this.blatantStalled = false;
        }

        if (!legitMode()) {
            if (now < this.failureResumeAt) return;
            if (this.blatantStalled) return;
            if (this.closeAfterTick != Integer.MIN_VALUE) {
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
        resetCloseState();
        if (now - this.nextStealAtNanos < 0L) {
            return;
        }

        Slot source = handler.slots.get(slotIndex.getAsInt());
        if (this.failure.beforeClick(client, handler, slotIndex.getAsInt(), this.inventoryOwner)) {
            this.nextStealAtNanos = now + Math.max(50, nextDelayMs()) * 1_000_000L;
            return;
        }
        int before = source.getItem().getCount();
        currentGameMode.handleContainerInput(
                handler.containerId,
                slotIndex.getAsInt(),
                0,
                ContainerInput.QUICK_MOVE,
                currentPlayer);
        this.tookItems |= source.getItem().getCount() < before;

        this.nextStealAtNanos = System.nanoTime() + nextDelayMs() * 1_000_000L;
        closeIfFinished(client, handler);
    }

    private void stealBlatant(Minecraft client, AbstractContainerMenu handler) {
        if (!InventoryClicks.acquire(this.inventoryOwner)) return;
        try {
            int[] inventorySlots = new int[36];
            Arrays.fill(inventorySlots, -1);
            var sources = new java.util.ArrayList<Integer>();
            for (int index = 0; index < handler.slots.size(); index++) {
                Slot slot = handler.slots.get(index);
                if (slot.container == client.player.getInventory()) {
                    int inventoryIndex = slot.getContainerSlot();
                    if (inventoryIndex >= 0 && inventoryIndex < 36)
                        inventorySlots[inventoryIndex] = index;
                } else if (slot.hasItem()
                        && shouldSteal(slot.getItem())
                        && slot.mayPickup(client.player)) {
                    sources.add(index);
                }
            }
            var plan =
                    ChestStealPlan.build(
                            handler.slots.stream().map(slot -> slot.getItem().copy()).toList(),
                            inventorySlots,
                            sources);
            // Native prediction updates each pair before the next pair in the same client tick.
            // A 36-stack batch uses at most 63 swaps (27 are internal hotbar shuttles).
            for (var action : plan) {
                if (this.failure.beforeClick(client, handler, action.slot(), this.inventoryOwner)) {
                    this.failureResumeAt =
                            System.nanoTime() + Math.max(50, nextDelayMs()) * 1_000_000L;
                    return;
                }
                if (!preparedSwap(client, handler, action)) {
                    this.blatantStalled = true;
                    return;
                }
                this.tookItems |= action.fromContainer();
            }
        } finally {
            InventoryClicks.release(this.inventoryOwner);
        }
    }

    private static boolean preparedSwap(
            Minecraft client, AbstractContainerMenu handler, ChestStealPlan.Swap action) {
        if (client.player.containerMenu != handler || !handler.getCarried().isEmpty()) return false;
        Slot slot = handler.getSlot(action.slot()), hotbar = handler.getSlot(action.hotbarSlot());
        if (!ItemStack.matches(slot.getItem(), action.beforeSlot())
                || !ItemStack.matches(hotbar.getItem(), action.beforeHotbar())
                || !slot.mayPickup(client.player)
                || !hotbar.mayPickup(client.player)
                || !action.beforeSlot().isEmpty() && !hotbar.mayPlace(action.beforeSlot())
                || !action.beforeHotbar().isEmpty() && !slot.mayPlace(action.beforeHotbar()))
            return false;
        // Survival middle click is CLONE/button 2, interpreted as MIDDLE/NOTHING by
        // Bukkit. It must target the very same slot as the following NUMBER_KEY/SWAP.
        // Creative CLONE creates a carried item, so creative uses only the real swap.
        if (!client.player.getAbilities().instabuild) {
            client.gameMode.handleContainerInput(
                    handler.containerId, action.slot(), 2, ContainerInput.CLONE, client.player);
            if (client.player.containerMenu != handler
                    || !handler.getCarried().isEmpty()
                    || !ItemStack.matches(slot.getItem(), action.beforeSlot())
                    || !ItemStack.matches(hotbar.getItem(), action.beforeHotbar())) return false;
        }
        client.gameMode.handleContainerInput(
                handler.containerId,
                action.slot(),
                action.button(),
                ContainerInput.SWAP,
                client.player);
        return handler.getCarried().isEmpty()
                && ItemStack.matches(slot.getItem(), action.beforeHotbar())
                && ItemStack.matches(hotbar.getItem(), action.beforeSlot());
    }

    private OptionalInt findNextContainerSlotIndex(AbstractContainerMenu handler) {
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

    private boolean shouldSteal(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return this.all.get() || this.lockedItems.contains(id);
    }

    private void closeIfFinished(Minecraft client, AbstractContainerMenu handler) {
        // Finish a real looting session; an opening menu can be temporarily empty
        // before its contents arrive. Failed/full-inventory clicks do not finish it.
        if (!this.autoClose.get() || !this.tookItems) {
            resetCloseState();
            return;
        }
        for (int index = 0; index < handler.slots.size() - 36; index++) {
            ItemStack stack = handler.slots.get(index).getItem();
            if (!stack.isEmpty() && shouldSteal(stack)) {
                resetCloseState();
                return;
            }
        }
        // Sample once after looting finishes. Keep Blatant's next-tick safeguard;
        // every waiting pass still rechecks contents so late slot updates resume looting.
        long now = System.nanoTime();
        if (this.closeAfterTick == Integer.MIN_VALUE) {
            this.closeAfterTick = client.player.tickCount + (legitMode() ? 0 : 1);
            int delay =
                    ThreadLocalRandom.current().nextInt(closeDelayMinMs(), closeDelayMaxMs() + 1);
            this.closeAtNanos = now + delay * 1_000_000L;
        }
        if (client.player.tickCount - this.closeAfterTick < 0 || now - this.closeAtNanos < 0L)
            return;
        client.player.closeContainer();
        resetScreenState();
    }

    private void resetScreenState() {
        this.failure.reset();
        this.failureResumeAt = 0;
        this.lastHandler = null;
        this.nextStealAtNanos = 0L;
        this.tookItems = false;
        resetCloseState();
        this.blatantStalled = false;
    }

    private void resetCloseState() {
        this.closeAfterTick = Integer.MIN_VALUE;
        this.closeAtNanos = 0L;
    }

    public int showStatus(Minecraft client) {
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
                        + ", close delay: "
                        + closeDelayMinMs()
                        + "-"
                        + closeDelayMaxMs()
                        + "ms"
                        + ", locked items: "
                        + lockedItemsText()
                        + ".");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        resetScreenState();
        ClientChat.send(client, "ChestStealer " + statusText() + ". Mode: " + modeName() + ".");
        return 1;
    }

    public int setDelayMs(Minecraft ignoredClient, int delayMs) {
        return setDelayRange(ignoredClient, delayMs, delayMs);
    }

    public int setDelayRange(Minecraft ignoredClient, int minMs, int maxMs) {
        this.delayMinMs.set(Math.min(minMs, maxMs));
        this.delayMaxMs.set(Math.max(minMs, maxMs));
        resetScreenState();
        return 1;
    }

    public int delayMinMs() {
        return Math.min(this.delayMinMs.get(), this.delayMaxMs.get());
    }

    public int delayMaxMs() {
        return Math.max(this.delayMinMs.get(), this.delayMaxMs.get());
    }

    private int nextDelayMs() {
        return ThreadLocalRandom.current().nextInt(delayMinMs(), delayMaxMs() + 1);
    }

    private static int legacyDelay(int fallback) {
        return Math.clamp(Settings.getInt("cheststealer.delayMs", fallback), 0, 1000);
    }

    public int setAll(Minecraft ignoredClient, boolean all) {
        this.all.set(all);
        resetScreenState();
        return 1;
    }

    public int setMode(Minecraft client, String mode) {
        if (!this.mode.tryDeserialize(mode)) {
            throw new IllegalArgumentException("ChestStealer mode must be legit or blatant.");
        }
        resetScreenState();
        return 1;
    }

    public int setAutoClose(Minecraft ignoredClient, boolean autoClose) {
        this.autoClose.set(autoClose);
        resetCloseState();
        return 1;
    }

    public boolean autoClose() {
        return this.autoClose.get();
    }

    public int setCloseDelayRange(Minecraft ignoredClient, int minMs, int maxMs) {
        this.closeDelayMinMs.set(Math.min(minMs, maxMs));
        this.closeDelayMaxMs.set(Math.max(minMs, maxMs));
        resetCloseState();
        return 1;
    }

    public int closeDelayMinMs() {
        return Math.min(this.closeDelayMinMs.get(), this.closeDelayMaxMs.get());
    }

    public int closeDelayMaxMs() {
        return Math.max(this.closeDelayMinMs.get(), this.closeDelayMaxMs.get());
    }

    public boolean legitMode() {
        return this.mode.get().equals("legit");
    }

    public boolean allItems() {
        return this.all.get();
    }

    public String modeName() {
        return this.mode.serialized();
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    public com.google.gson.JsonArray selectedItems() {
        var result = new com.google.gson.JsonArray();
        this.lockedItems.forEach(id -> result.add(SettingFactory.entry(id.toString(), null)));
        return result;
    }

    public void setSelectedItems(Minecraft client, com.google.gson.JsonElement value) {
        if (!SettingFactory.valid("item_list", value))
            throw new IllegalArgumentException("Invalid item list");
        this.lockedItems.clear();
        value.getAsJsonArray()
                .forEach(
                        entry ->
                                this.lockedItems.add(
                                        Identifier.parse(
                                                entry.getAsJsonObject().get("id").getAsString())));
        this.items.set(
                this.lockedItems.stream()
                        .map(Identifier::toString)
                        .collect(Collectors.joining(",")));
    }

    private String lockedItemsText() {
        if (this.all.get()) return "all";
        if (this.lockedItems.isEmpty()) {
            return "none";
        }

        return this.lockedItems.stream()
                .map(Identifier::toString)
                .collect(Collectors.joining(", "));
    }

    private Set<Identifier> loadLockedItems() {
        String value = this.items.get();

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

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("cheststealer.enabled", false);
    }

    @Override
    protected String statusTag() {
        return this.modeName();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            customChoice(
                            "mode",
                            "Mode",
                            this::modeName,
                            java.util.List.of("legit", "blatant"),
                            this::setMode)
                    .withDefault("legit"),
            all.describeStored("all", "All", this::setAll),
            this.failureSetting(),
            autoClose.describeStored("auto_close", "Auto close", this::setAutoClose),
            rangeValue(
                            "close_delay_ms",
                            "Close delay (ms)",
                            this::closeDelayMinMs,
                            this::closeDelayMaxMs,
                            0,
                            5000,
                            10,
                            (client, low, high) ->
                                    this.setCloseDelayRange(client, (int) low, (int) high))
                    .withDefault(0, 0)
                    .visibleWhen(this::autoClose),
            rangeValue(
                            "delay_ms",
                            "Delay (ms)",
                            this::delayMinMs,
                            this::delayMaxMs,
                            0,
                            1000,
                            1,
                            (client, low, high) ->
                                    this.setDelayRange(client, (int) low, (int) high))
                    .withDefault(100, 150)
                    .visibleWhen(this::legitMode),
            SettingFactory.registryList(
                            "items",
                            "Items",
                            "item",
                            this::selectedItems,
                            this::setSelectedItems,
                            new com.google.gson.JsonArray())
                    .visibleWhen(() -> !this.allItems())
        };
    }
}
