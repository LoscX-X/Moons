package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.compat.input.InputConstants;
import com.blanoir.moons.client.compat.math.VecMath;
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
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.item.ItemStack;

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

    private static final BooleanSetting PROTECT_SPECIAL =
            flag("protectSpecial", Settings.getBoolean("invmanager.protectSpecial", true));
    private static final List<BooleanSetting> LOCKS =
            List.of(lock("feet"), lock("legs"), lock("chest"), lock("head"));
    private static final IntSetting OPEN_DELAY = delay("openDelayMs", 250);
    private static final IntSetting MANUAL_DELAY = delay("manualDelayMs", 500);
    private static final IntSetting DELAY_MIN = delay("delayMinMs", 100);
    private static final IntSetting DELAY_MAX = delay("delayMaxMs", 150);
    private static InventorySession session = new InventorySession();
    private static GuiInventory screen;
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
                    if (event.attacker() == Minecraft.getMinecraft().thePlayer)
                        combatUntil = System.nanoTime() + 500_000_000L;
                });
        EventBus.KEY_INPUT.register(
                "AutoArmor.key",
                event -> {
                    if (!event.isCancelled() && event.action() != InputConstants.RELEASE)
                        manualInput(Minecraft.getMinecraft());
                });
        EventBus.MOUSE_BUTTON.register(
                "AutoArmor.mouse",
                event -> {
                    Minecraft client = Minecraft.getMinecraft();
                    if (!(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory))
                        return;
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

    private static void enter(GuiInventory current, long now) {
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
                || !(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory current)
                || client.thePlayer.openContainer != client.thePlayer.inventoryContainer) return;
        long now = System.nanoTime();
        enter(current, now);
        session.manualInput(snapshot(client), now, MANUAL_DELAY.get());
    }

    private static void tick(Minecraft client) {
        long now = System.nanoTime();
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory current)
                || client.thePlayer.openContainer != client.thePlayer.inventoryContainer) {
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
        if (!client.inGameHasFocus || !mouseHeld.isEmpty() || session.paused(now)) {
            status = "Paused · manual input";
            return;
        }
        if (!LegacyItems.empty(LegacyItems.carried(client.thePlayer.inventoryContainer))) {
            status = "Paused · cursor holds an item";
            return;
        }
        if (InventoryClicks.busyExcept(null)
                || InventoryClicks.recentlyBusy(now)
                || HotbarLease.isHeld()
                || PlacementCoordinator.busy()) {
            status = "Paused · another inventory action";
            return;
        }
        if (client.thePlayer.isDead
                || client.thePlayer.isSpectator()
                || client.thePlayer.isUsingItem()
                || client.playerController.getIsHittingBlock()
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
        var menu = client.thePlayer.inventoryContainer;
        int source = snapshot.menuSlot(action.source());
        int target = snapshot.menuSlot(action.target());
        boolean hotbar = action.source() < 9;
        // QUICK_MOVE needs somewhere to put the old piece. Never drop it to make room.
        if (!hotbar
                && !LegacyItems.empty(action.beforeTarget())
                && !InventoryClicks.hasEmptyStorage(client, menu, action.beforeTarget())) {
            status = "Paused · free one inventory slot to change armor";
            return;
        }
        int clickSlot = hotbar || !LegacyItems.empty(action.beforeTarget()) ? target : source;
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
        } else if (!LegacyItems.empty(action.beforeTarget())) {
            changed = InventoryClicks.quickMove(client, menu, target, action.beforeTarget());
        } else {
            changed = InventoryClicks.quickMove(client, menu, source, action.beforeSource());
            changed &= LegacyItems.matches(menu.getSlot(target).getStack(), action.beforeSource());
        }
        status = changed ? "Armor updated" : "Inventory changed · waiting to retry";
        nextActionAt = now + (changed ? Math.max(50, nextDelay()) : 1000) * 1_000_000L;
    }

    private static boolean moving(Minecraft client) {
        return !client.thePlayer.onGround
                || VecMath.horizontalDistanceSqr(VecMath.motion(client.thePlayer)) > .0001
                || client.gameSettings.keyBindForward.isKeyDown()
                || client.gameSettings.keyBindBack.isKeyDown()
                || client.gameSettings.keyBindLeft.isKeyDown()
                || client.gameSettings.keyBindRight.isKeyDown()
                || client.gameSettings.keyBindJump.isKeyDown();
    }

    private static InventorySnapshot snapshot(Minecraft client) {
        return InventorySnapshot.capture(
                client.thePlayer.inventoryContainer, client.thePlayer.inventory);
    }

    private static List<InventoryAction> plan(Minecraft client, InventorySnapshot snapshot) {
        return plan(client, snapshot, PROTECT_SPECIAL.get());
    }

    private static List<InventoryAction> plan(
            Minecraft client, InventorySnapshot snapshot, boolean protectSpecial) {
        var blocked = session.protectedSlots();
        var locks = new BitSet(4);
        for (int i = 0; i < 4; i++) if (LOCKS.get(i).get()) locks.set(i);
        for (int i = 0; i < 40; i++) {
            int slot = snapshot.menuSlot(i);
            if (slot < 0
                    || !client.thePlayer
                            .inventoryContainer
                            .getSlot(slot)
                            .canTakeStack(client.thePlayer)) blocked.set(i);
        }
        return InventoryArmor.plan(
                        snapshot, blocked, InvManagerConfig.roles(), protectSpecial, locks)
                .stream()
                .filter(
                        action -> {
                            var source =
                                    client.thePlayer.inventoryContainer.getSlot(
                                            snapshot.menuSlot(action.source()));
                            var target =
                                    client.thePlayer.inventoryContainer.getSlot(
                                            snapshot.menuSlot(action.target()));
                            return target.isItemValid(action.beforeSource())
                                    && (LegacyItems.empty(action.beforeTarget())
                                            || source.isItemValid(action.beforeTarget()));
                        })
                .toList();
    }

    public static List<ArmorView> preview(Minecraft client) {
        var snapshot = ClientReady.interaction(client) ? snapshot(client) : null;
        var actions = snapshot == null ? List.<InventoryAction>of() : plan(client, snapshot);
        var result = new ArrayList<ArmorView>();
        for (int index = 39; index >= 36; index--) {
            final int target = index;
            ItemStack item = snapshot == null ? LegacyItems.EMPTY : snapshot.item(index);
            var action =
                    actions.stream().filter(candidate -> candidate.target() == target).findFirst();
            String reason =
                    !enabled()
                            ? "AutoArmor off"
                            : locked(index)
                                    ? "Locked"
                                    : session.protectedSlots().get(index)
                                            ? "Manually placed this session"
                                            : PROTECT_SPECIAL.get()
                                                            && !InventoryItems.protection(item)
                                                                    .isEmpty()
                                                    ? InventoryItems.protection(item)
                                                    : snapshot != null
                                                                    && snapshot.menuSlot(index) >= 0
                                                                    && !client.thePlayer
                                                                            .inventoryContainer
                                                                            .getSlot(
                                                                                    snapshot
                                                                                            .menuSlot(
                                                                                                    index))
                                                                            .canTakeStack(
                                                                                    client.thePlayer)
                                                            ? "Cannot remove this armor"
                                                            : action.isPresent()
                                                                    ? action.get().reason()
                                                                    : "No better available armor";
            result.add(
                    new ArmorView(
                            index,
                            LegacyItems.copy(item),
                            action.map(InventoryAction::beforeSource)
                                    .orElse(LegacyItems.EMPTY)
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
