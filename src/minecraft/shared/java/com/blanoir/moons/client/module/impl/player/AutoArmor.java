package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.player.invmanager.*;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.inventory.InventoryClickFailure;
import com.blanoir.moons.client.utils.inventory.InventoryClicks;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Independent armor scheduling, sharing checked inventory transactions with other modules. */
public final class AutoArmor {
    private static final InventoryClickFailure FAILURE = new InventoryClickFailure("autoarmor");

    public record ArmorView(
            int index, ItemStack item, ItemStack candidate, String status, boolean locked) {}

    private static final BooleanSetting ENABLED =
            flag(
                    "enabled",
                    Settings.getBoolean("invmanager.enabled", false)
                            && Settings.getBoolean("invmanager.autoArmor", false));
    private static final BooleanSetting KEEP_ELYTRA =
            flag("keepElytra", Settings.getBoolean("invmanager.keepElytra", true));
    private static final BooleanSetting PROTECT_SPECIAL =
            flag("protectSpecial", Settings.getBoolean("invmanager.protectSpecial", true));
    private static final List<BooleanSetting> LOCKS =
            List.of(lock("feet"), lock("legs"), lock("chest"), lock("head"));
    private static final IntSetting OPEN_DELAY = delay("openDelayMs", 250);
    private static final IntSetting MANUAL_DELAY = delay("manualDelayMs", 500);
    private static final IntSetting DELAY_MIN = delay("delayMinMs", 100);
    private static final IntSetting DELAY_MAX = delay("delayMaxMs", 150);
    private static InventorySession session = new InventorySession();
    private static InventoryScreen screen;
    private static final Set<Integer> mouseHeld = new HashSet<>();
    private static long nextActionAt, combatUntil;
    private static String status = "Open inventory to equip armor";
    private static String lastStatus = "";

    private AutoArmor() {}

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static String statusText() {
        return screen == null && !lastStatus.isEmpty() ? "Last inventory: " + lastStatus : status;
    }

    public static boolean locked(int index) {
        return LOCKS.get(index - 36).get();
    }

    public static void setLocked(int index, boolean value) {
        LOCKS.get(index - 36).set(value);
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset();
        return 1;
    }

    public static void init() {
        InventoryClicks.init();
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoArmor.context", event -> reset());
        EventBus.TICK.register("AutoArmor.tick", EventPriority.LOW, event -> tick(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "AutoArmor.combat",
                event -> {
                    if (event.attacker() == Minecraft.getInstance().player)
                        combatUntil = System.nanoTime() + 500_000_000L;
                });
        EventBus.KEY_INPUT.register(
                "AutoArmor.key",
                event -> {
                    if (!event.isCancelled() && event.action() != InputConstants.RELEASE)
                        manualInput(Minecraft.getInstance());
                });
        EventBus.MOUSE_BUTTON.register(
                "AutoArmor.mouse",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!(MinecraftClientAccess.screen(client) instanceof InventoryScreen)) return;
                    if (!event.isCancelled()) manualInput(client);
                    if (event.action() == InputConstants.RELEASE)
                        mouseHeld.remove(event.button().button());
                    else if (!event.isCancelled()) mouseHeld.add(event.button().button());
                });
    }

    public static void reset() {
        FAILURE.reset();
        session = new InventorySession();
        screen = null;
        mouseHeld.clear();
        nextActionAt = combatUntil = 0;
        status = "Open inventory to equip armor";
        lastStatus = "";
    }

    private static void enter(InventoryScreen current, long now) {
        if (current == screen) return;
        FAILURE.reset();
        screen = current;
        session = new InventorySession();
        mouseHeld.clear();
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
        long now = System.nanoTime();
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) {
            if (screen != null) {
                String previous = status;
                reset();
                lastStatus = previous;
            }
            return;
        }
        enter(current, now);
        if (!enabled()) return;
        var snapshot = snapshot(client);
        session.observe(snapshot, now);
        if (!client.isWindowActive() || !mouseHeld.isEmpty() || session.paused(now)) {
            status = "Paused · manual input";
            return;
        }
        if (!client.player.inventoryMenu.getCarried().isEmpty()) {
            status = "Paused · cursor holds an item";
            return;
        }
        if (AutoTotem.inventoryBusy()
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
                || moving(client)) {
            status = "Paused · movement or combat";
            return;
        }
        if (now < nextActionAt) return;
        var actions = plan(client, snapshot);
        if (actions.isEmpty()) {
            status =
                    PROTECT_SPECIAL.get() && !plan(client, snapshot, false).isEmpty()
                            ? "Paused · Protect special items blocks armor"
                            : session.protectedSlots().isEmpty()
                                    ? "Ready"
                                    : "Ready · manual slots protected";
            return;
        }
        var action = actions.getFirst();
        status = action.reason();
        var menu = client.player.inventoryMenu;
        int source = snapshot.menuSlot(action.source());
        int target = snapshot.menuSlot(action.target());
        boolean hotbar = action.source() < 9;
        // QUICK_MOVE needs somewhere to put the old piece. Never drop it to make room.
        if (!hotbar
                && !action.beforeTarget().isEmpty()
                && !InventoryClicks.hasEmptyStorage(client, menu, action.beforeTarget())) {
            status = "Paused · free one inventory slot to change armor";
            return;
        }
        int clickSlot = hotbar || !action.beforeTarget().isEmpty() ? target : source;
        if (FAILURE.beforeClick(client, menu, clickSlot, null)) {
            status = "Misclick · retrying";
            nextActionAt = now + Math.max(50, nextDelay()) * 1_000_000L;
            return;
        }
        if (!session.reserve(action)) {
            status = "Armor action cooling down";
            nextActionAt = now + 1_000_000_000L;
            return;
        }
        boolean changed;
        if (hotbar) {
            changed =
                    InventoryClicks.swap(
                            client,
                            menu,
                            target,
                            source,
                            action.source(),
                            action.beforeTarget(),
                            action.beforeSource());
        } else if (!action.beforeTarget().isEmpty()) {
            changed = InventoryClicks.quickMove(client, menu, target, action.beforeTarget());
        } else {
            changed = InventoryClicks.quickMove(client, menu, source, action.beforeSource());
            changed &= ItemStack.matches(menu.getSlot(target).getItem(), action.beforeSource());
        }
        status = changed ? "Armor updated" : "Inventory changed · waiting to retry";
        nextActionAt = now + (changed ? Math.max(50, nextDelay()) : 1000) * 1_000_000L;
    }

    private static boolean moving(Minecraft client) {
        return !client.player.onGround()
                || client.player.getDeltaMovement().horizontalDistanceSqr() > .0001
                || client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown()
                || client.options.keyJump.isDown();
    }

    private static InventorySnapshot snapshot(Minecraft client) {
        return InventorySnapshot.capture(client.player.inventoryMenu, client.player.getInventory());
    }

    private static List<InventoryAction> plan(Minecraft client, InventorySnapshot snapshot) {
        return plan(client, snapshot, PROTECT_SPECIAL.get());
    }

    private static List<InventoryAction> plan(
            Minecraft client, InventorySnapshot snapshot, boolean protectSpecial) {
        var blocked = session.protectedSlots();
        var locks = new BitSet(4);
        for (int i = 0; i < 4; i++) if (LOCKS.get(i).get()) locks.set(i);
        for (int i = 0; i < 41; i++) {
            int slot = snapshot.menuSlot(i);
            if (slot < 0 || !client.player.inventoryMenu.getSlot(slot).mayPickup(client.player))
                blocked.set(i);
        }
        return InventoryArmor.plan(
                        snapshot,
                        blocked,
                        InvManagerConfig.roles(),
                        protectSpecial,
                        KEEP_ELYTRA.get(),
                        locks)
                .stream()
                .filter(
                        action -> {
                            var source =
                                    client.player.inventoryMenu.getSlot(
                                            snapshot.menuSlot(action.source()));
                            var target =
                                    client.player.inventoryMenu.getSlot(
                                            snapshot.menuSlot(action.target()));
                            return target.mayPlace(action.beforeSource())
                                    && (action.beforeTarget().isEmpty()
                                            || source.mayPlace(action.beforeTarget()));
                        })
                .toList();
    }

    public static List<ArmorView> preview(Minecraft client) {
        var snapshot = ClientReady.interaction(client) ? snapshot(client) : null;
        var actions = snapshot == null ? List.<InventoryAction>of() : plan(client, snapshot);
        var result = new ArrayList<ArmorView>();
        for (int index = 39; index >= 36; index--) {
            final int target = index;
            ItemStack item = snapshot == null ? ItemStack.EMPTY : snapshot.item(index);
            var action =
                    actions.stream().filter(candidate -> candidate.target() == target).findFirst();
            String reason =
                    !enabled()
                            ? "AutoArmor off"
                            : locked(index)
                                    ? "Locked"
                                    : session.protectedSlots().get(index)
                                            ? "Manually placed this session"
                                            : KEEP_ELYTRA.get() && item.is(Items.ELYTRA)
                                                    ? "Keeping elytra"
                                                    : PROTECT_SPECIAL.get()
                                                                    && !InventoryItems.protection(
                                                                                    item)
                                                                            .isEmpty()
                                                            ? InventoryItems.protection(item)
                                                            : snapshot != null
                                                                            && snapshot.menuSlot(
                                                                                            index)
                                                                                    >= 0
                                                                            && !client.player
                                                                                    .inventoryMenu
                                                                                    .getSlot(
                                                                                            snapshot
                                                                                                    .menuSlot(
                                                                                                            index))
                                                                                    .mayPickup(
                                                                                            client.player)
                                                                    ? "Cannot remove this armor"
                                                                    : action.isPresent()
                                                                            ? action.get().reason()
                                                                            : "No better available armor";
            result.add(
                    new ArmorView(
                            index,
                            item.copy(),
                            action.map(InventoryAction::beforeSource)
                                    .orElse(ItemStack.EMPTY)
                                    .copy(),
                            reason,
                            locked(index)));
        }
        return List.copyOf(result);
    }

    public static ModuleRegistry.Setting[] settings() {
        var result = new ArrayList<ModuleRegistry.Setting>();
        result.add(FAILURE.setting());
        result.add(
                KEEP_ELYTRA.describe(
                        "keep_elytra",
                        "Keep elytra",
                        (client, value) -> {
                            KEEP_ELYTRA.set(value);
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
        for (int i = 0; i < 4; i++) {
            int part = i;
            result.add(
                    LOCKS.get(i)
                            .describe(
                                    "armor_lock_" + i,
                                    "Lock " + InventoryArmor.label(36 + i),
                                    (client, value) -> {
                                        LOCKS.get(part).set(value);
                                        return 1;
                                    }));
        }
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
                                10,
                                (client, min, max) -> {
                                    DELAY_MIN.set((int) Math.round(min));
                                    DELAY_MAX.set((int) Math.round(max));
                                    return 1;
                                })
                        .withDefault(100, 150));
        return result.toArray(ModuleRegistry.Setting[]::new);
    }

    private static int nextDelay() {
        return ThreadLocalRandom.current()
                .nextInt(
                        Math.min(DELAY_MIN.get(), DELAY_MAX.get()),
                        Math.max(DELAY_MIN.get(), DELAY_MAX.get()) + 1);
    }

    private static BooleanSetting flag(String key, boolean fallback) {
        return new BooleanSetting.Builder().name("autoarmor." + key).defaultValue(fallback).build();
    }

    private static BooleanSetting lock(String name) {
        return flag(
                "armorLock." + name, Settings.getBoolean("invmanager.armorLock." + name, false));
    }

    private static IntSetting delay(String key, int fallback) {
        return new IntSetting.Builder()
                .name("autoarmor." + key)
                .defaultValue(Settings.getInt("invmanager." + key, fallback))
                .range(0, 1000)
                .build();
    }
}
