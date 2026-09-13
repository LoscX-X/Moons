package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.*;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.player.invclear.InventoryCleanup;
import com.blanoir.moons.client.module.impl.player.invmanager.*;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.inventory.InventoryClicks;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Low-priority inventory cleanup. Armor and hotbar sorting always get the first opportunity. */
public final class InvClear {
    public static final String DEFAULT_JUNK =
            "minecraft:rotten_flesh, minecraft:poisonous_potato, minecraft:spider_eye, minecraft:bowl";
    private static final BooleanSetting ENABLED = flag("enabled", false);
    private static final BooleanSetting EQUIPMENT = flag("equipment", true);
    private static final BooleanSetting JUNK = flag("junk", true);
    private static final BooleanSetting PROTECT_HOTBAR = flag("protectHotbar", true);
    private static final BooleanSetting PROTECT_SPECIAL = flag("protectSpecial", true);
    private static final StringSetting DROP_LIST = text("dropList", DEFAULT_JUNK);
    private static final StringSetting KEEP_LIST = text("keepList", "");
    private static final IntSetting OPEN_DELAY = delay("openDelayMs", 250);
    private static final IntSetting ACTION_DELAY = delay("actionDelayMs", 150);
    private static final IntSetting MANUAL_DELAY = delay("manualDelayMs", 500);
    private static InventoryScreen screen;
    private static InventorySession session = new InventorySession();
    private static final Set<Integer> MOUSE_HELD = new HashSet<>();
    private static final List<InventoryCleanup.Drop> ATTEMPTS = new ArrayList<>();
    private static long nextActionAt, combatUntil, activityVersion;
    private static boolean stalled;
    private static String status = "Open inventory to clean";

    private InvClear() {}

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static String statusText() {
        return status;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset();
        return 1;
    }

    public static void init() {
        InventoryClicks.init();
        EventBus.CLIENT_CONTEXT_CHANGED.register("InvClear.context", event -> reset());
        EventBus.TICK.register(
                "InvClear.tick", EventPriority.LOWEST, event -> tick(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "InvClear.combat",
                event -> {
                    if (event.attacker() == Minecraft.getInstance().player)
                        combatUntil = System.nanoTime() + 500_000_000L;
                });
        EventBus.KEY_INPUT.register(
                "InvClear.key",
                event -> {
                    if (!event.isCancelled() && event.action() != InputConstants.RELEASE)
                        manualInput(Minecraft.getInstance());
                });
        EventBus.MOUSE_BUTTON.register(
                "InvClear.mouse",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!(MinecraftClientAccess.screen(client) instanceof InventoryScreen)) return;
                    if (!event.isCancelled()) manualInput(client);
                    if (event.action() == InputConstants.RELEASE)
                        MOUSE_HELD.remove(event.button().button());
                    else if (!event.isCancelled()) MOUSE_HELD.add(event.button().button());
                });
    }

    public static void reset() {
        screen = null;
        session = new InventorySession();
        MOUSE_HELD.clear();
        ATTEMPTS.clear();
        nextActionAt = combatUntil = 0;
        activityVersion = InventoryClicks.activityVersion();
        stalled = false;
        status = "Open inventory to clean";
    }

    private static void enter(InventoryScreen current, long now) {
        if (current == screen) return;
        screen = current;
        session = new InventorySession();
        MOUSE_HELD.clear();
        ATTEMPTS.clear();
        stalled = false;
        activityVersion = InventoryClicks.activityVersion();
        nextActionAt = now + OPEN_DELAY.get() * 1_000_000L;
    }

    private static void manualInput(Minecraft client) {
        if (!enabled()
                || !ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) return;
        long now = System.nanoTime();
        enter(current, now);
        session.manualInput(snapshot(client), now, MANUAL_DELAY.get());
    }

    private static void tick(Minecraft client) {
        if (!enabled()) return;
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) {
            if (screen != null) reset();
            return;
        }
        long now = System.nanoTime();
        enter(current, now);
        if (activityVersion != InventoryClicks.activityVersion()) {
            activityVersion = InventoryClicks.activityVersion();
            nextActionAt =
                    Math.max(nextActionAt, now + Math.max(100, ACTION_DELAY.get()) * 1_000_000L);
        }
        InventorySnapshot snapshot = snapshot(client);
        session.observe(snapshot, now);
        if (stalled) {
            status = "Inventory changed · reopen inventory to retry";
            return;
        }
        if (!client.isWindowActive() || !MOUSE_HELD.isEmpty() || session.paused(now)) {
            status = "Paused · manual input";
            return;
        }
        if (!client.player.inventoryMenu.getCarried().isEmpty()
                || AutoTotem.inventoryBusy()
                || InventoryClicks.busyExcept(null)
                || InventoryClicks.recentlyBusy(now)
                || HotbarLease.isHeld()
                || PlacementCoordinator.busy()) {
            status = "Paused · another inventory action";
            return;
        }
        if (client.player.isDeadOrDying()
                || client.player.isSpectator()
                || client.player.isUsingItem()
                || client.gameMode.isDestroying()
                || now < combatUntil
                || !client.player.onGround()
                || client.player.getDeltaMovement().horizontalDistanceSqr() > .0001
                || client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown()
                || client.options.keyJump.isDown()) {
            status = "Paused · movement or combat";
            return;
        }
        if (!InventoryRules.error().isEmpty()) {
            status = InventoryRules.error();
            return;
        }
        List<InventoryCleanup.Drop> plan;
        try {
            plan = plan(client, snapshot);
        } catch (IllegalArgumentException invalid) {
            status = invalid.getMessage();
            return;
        }
        if (plan.isEmpty()) {
            status = "Ready";
            return;
        }
        var drop = plan.getFirst();
        status = "Drop " + drop.item().getHoverName().getString() + " · " + drop.reason();
        if (now < nextActionAt) return;
        if (ATTEMPTS.size() >= 100
                || ATTEMPTS.stream()
                                .filter(
                                        old ->
                                                old.source() == drop.source()
                                                        && ItemStack.matches(
                                                                old.item(), drop.item()))
                                .count()
                        >= 2) {
            stalled = true;
            return;
        }
        ATTEMPTS.add(drop);
        if (!InventoryClicks.dropStack(
                client,
                client.player.inventoryMenu,
                snapshot.menuSlot(drop.source()),
                drop.item())) {
            stalled = true;
            status = "Inventory changed · reopen inventory to retry";
        }
        activityVersion = InventoryClicks.activityVersion();
        nextActionAt = now + ACTION_DELAY.get() * 1_000_000L;
    }

    private static InventorySnapshot snapshot(Minecraft client) {
        return InventorySnapshot.capture(client.player.inventoryMenu, client.player.getInventory());
    }

    private static List<InventoryCleanup.Drop> plan(Minecraft client, InventorySnapshot snapshot) {
        BitSet blocked = session.protectedSlots();
        blocked.set(client.player.getInventory().getSelectedSlot());
        var rules = InventoryRules.groups();
        for (int index = 0; index < 36; index++) {
            int slot = snapshot.menuSlot(index);
            ItemStack item = snapshot.item(index);
            if (slot < 0
                    || !client.player.inventoryMenu.getSlot(slot).mayPickup(client.player)
                    || rules.stream()
                            .flatMap(rule -> rule.include().stream())
                            .anyMatch(entry -> entry.matches(item))) blocked.set(index);
        }
        return InventoryCleanup.plan(
                snapshot,
                InvManagerConfig.roles(),
                blocked,
                PROTECT_HOTBAR.get(),
                PROTECT_SPECIAL.get(),
                EQUIPMENT.get(),
                JUNK.get() ? InventoryCleanup.itemIds(DROP_LIST.get()) : Set.of(),
                InventoryCleanup.itemIds(KEEP_LIST.get()));
    }

    public static List<InventoryCleanup.Drop> preview(Minecraft client) {
        if (!ClientReady.interaction(client) || !InventoryRules.error().isEmpty()) return List.of();
        try {
            return plan(client, snapshot(client));
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static int setList(Minecraft client, StringSetting setting, String value) {
        try {
            InventoryCleanup.itemIds(value);
        } catch (IllegalArgumentException invalid) {
            ClientChat.send(client, invalid.getMessage());
            return 0;
        }
        setting.set(value);
        return 1;
    }

    public static ModuleRegistry.Setting[] settings() {
        return new ModuleRegistry.Setting[] {
            EQUIPMENT.describe(
                    "equipment",
                    "Surplus equipment",
                    (c, v) -> {
                        EQUIPMENT.set(v);
                        return 1;
                    }),
            JUNK.describe(
                    "junk",
                    "Listed junk",
                    (c, v) -> {
                        JUNK.set(v);
                        return 1;
                    }),
            PROTECT_HOTBAR.describe(
                    "protect_hotbar",
                    "Protect hotbar",
                    (c, v) -> {
                        PROTECT_HOTBAR.set(v);
                        return 1;
                    }),
            PROTECT_SPECIAL.describe(
                    "protect_special",
                    "Protect special items",
                    (c, v) -> {
                        PROTECT_SPECIAL.set(v);
                        return 1;
                    }),
            ModuleRegistry.text(
                            "drop_list",
                            "Drop item IDs",
                            DROP_LIST::get,
                            (c, v) -> setList(c, DROP_LIST, v))
                    .withDefault(DEFAULT_JUNK)
                    .visibleWhen(JUNK::get),
            ModuleRegistry.text(
                            "keep_list",
                            "Keep item IDs",
                            KEEP_LIST::get,
                            (c, v) -> setList(c, KEEP_LIST, v))
                    .withDefault(""),
            OPEN_DELAY.describe(
                    "open_delay",
                    "Open delay (ms)",
                    10,
                    (c, v) -> {
                        OPEN_DELAY.set(v);
                        return 1;
                    }),
            ACTION_DELAY.describe(
                    "action_delay",
                    "Action delay (ms)",
                    10,
                    (c, v) -> {
                        ACTION_DELAY.set(v);
                        return 1;
                    }),
            MANUAL_DELAY.describe(
                    "manual_delay",
                    "Manual pause (ms)",
                    10,
                    (c, v) -> {
                        MANUAL_DELAY.set(v);
                        return 1;
                    })
        };
    }

    private static BooleanSetting flag(String key, boolean fallback) {
        return new BooleanSetting.Builder().name("invclear." + key).defaultValue(fallback).build();
    }

    private static IntSetting delay(String key, int fallback) {
        return new IntSetting.Builder()
                .name("invclear." + key)
                .defaultValue(fallback)
                .range(0, 1000)
                .build();
    }

    private static StringSetting text(String key, String fallback) {
        return new StringSetting.Builder().name("invclear." + key).defaultValue(fallback).build();
    }
}
