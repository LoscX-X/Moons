package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.impl.player.AutoTotem;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.inventory.InventoryClickFailure;
import com.blanoir.moons.client.utils.inventory.InventoryClicks;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** First-stage manager: player-inventory sorting/refill with manual input taking precedence. */
public final class InvManager {
    private static final InventoryClickFailure FAILURE = new InventoryClickFailure("invmanager");

    public static com.blanoir.moons.client.module.framework.ModuleRegistry.Setting
            failureSetting() {
        return FAILURE.setting();
    }

    public record SlotView(int index, InventoryRole role, ItemStack item, String protection) {}

    public record View(String status, List<SlotView> slots, List<String> plan) {}

    private static InventoryScreen screen;
    private static InventorySession session = new InventorySession();
    private static final Set<Integer> mouseHeld = new HashSet<>();
    private static boolean once;
    private static long nextActionAt;
    private static long combatUntil;
    private static String status = "Open inventory to organize";
    private static String lastStatus = "";
    private static Object bindingScreen;

    private InvManager() {}

    public static void init() {
        InventoryClicks.init();
        InventoryEditorInput.init();
        ModuleKeybinds.registerAction(
                "invmanager_once", () -> organizeOnce(Minecraft.getInstance()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("InvManager.context", event -> reset());
        // AutoTotem gets the first opportunity to reserve its cursor/offhand transaction.
        EventBus.TICK.register(
                "InvManager.tick", EventPriority.LOWEST, event -> tick(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "InvManager.combat",
                event -> {
                    if (event.attacker() == Minecraft.getInstance().player)
                        combatUntil = System.nanoTime() + 500_000_000L;
                });
        EventBus.KEY_INPUT.register(
                "InvManager.key",
                EventPriority.HIGHEST,
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    var key = ModuleKeybinds.fromEvent(event.key());
                    if (bindingScreen != null
                            && bindingScreen != MinecraftClientAccess.screen(client))
                        bindingScreen = null;
                    if (bindingScreen != null && event.action() == InputConstants.PRESS) {
                        if (key.getValue() == InputConstants.KEY_ESCAPE) bindingScreen = null;
                        else if (key.getValue() == InputConstants.KEY_BACKSPACE
                                || key.getValue() == InputConstants.KEY_DELETE) {
                            Settings.setString(InvManagerConfig.ONCE_KEY, "");
                            bindingScreen = null;
                        } else if (ModuleKeybinds.isValid(key) && !ModuleKeybinds.isGuiKey(key)) {
                            Settings.setString(InvManagerConfig.ONCE_KEY, key.getName());
                            bindingScreen = null;
                        }
                        event.cancel();
                        return;
                    }
                    if (!(MinecraftClientAccess.screen(client) instanceof InventoryScreen)) return;
                    // The global module binding router intentionally ignores open screens. This one
                    // action is explicitly usable inside the player's inventory as well.
                    if (event.action() == InputConstants.PRESS
                            && key.getName().equals(InvManagerConfig.onceKey())) {
                        organizeOnce(client);
                        event.cancel();
                        return;
                    }
                    if (!event.isCancelled() && event.action() != InputConstants.RELEASE)
                        manualInput(client);
                });
        EventBus.MOUSE_BUTTON.register(
                "InvManager.mouse",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!(MinecraftClientAccess.screen(client) instanceof InventoryScreen)) return;
                    if (!event.isCancelled()) manualInput(client);
                    if (event.action() == InputConstants.RELEASE)
                        mouseHeld.remove(event.button().button());
                    else if (!event.isCancelled()) mouseHeld.add(event.button().button());
                });
    }

    public static int setEnabled(Minecraft client, boolean value) {
        reset();
        InvManagerConfig.enabled(value);
        ClientChat.send(client, "InvManager " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static void organizeOnce(Minecraft client) {
        if (!ClientReady.interaction(client)) {
            status = "Join a world first";
            return;
        }
        Object current = MinecraftClientAccess.screen(client);
        if (current != null
                && !(current instanceof InventoryScreen)
                && !(current instanceof MoonsComposeScreen)) {
            status = "Close the other screen first";
            return;
        }
        if (client.player.containerMenu != client.player.inventoryMenu
                || !client.player.inventoryMenu.getCarried().isEmpty()) {
            status = "Finish the current inventory action first";
            return;
        }
        if (!(current instanceof InventoryScreen))
            MinecraftClientAccess.setScreen(client, new InventoryScreen(client.player));
        once = true;
        session.retry();
        status = "Organize once queued";
    }

    public static void beginKeyBinding() {
        Object current = MinecraftClientAccess.screen(Minecraft.getInstance());
        if (current instanceof MoonsComposeScreen) bindingScreen = current;
    }

    public static boolean bindingKey() {
        return bindingScreen != null;
    }

    public static void cancelKeyBinding() {
        bindingScreen = null;
    }

    public static String statusText() {
        return screen == null && !lastStatus.isEmpty() ? "Last inventory: " + lastStatus : status;
    }

    public static void reset() {
        FAILURE.reset();
        screen = null;
        session = new InventorySession();
        mouseHeld.clear();
        once = false;
        bindingScreen = null;
        nextActionAt = 0;
        combatUntil = 0;
        status = "Open inventory to organize";
        lastStatus = "";
    }

    private static void enter(InventoryScreen current, long now) {
        if (screen == current) return;
        FAILURE.reset();
        screen = current;
        session = new InventorySession();
        mouseHeld.clear();
        nextActionAt = now + InvManagerConfig.openDelay() * 1_000_000L;
    }

    private static void manualInput(Minecraft client) {
        if ((!InvManagerConfig.enabled() && !once)
                || !ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) return;
        long now = System.nanoTime();
        enter(current, now);
        session.manualInput(snapshot(client), now, InvManagerConfig.manualDelay());
    }

    private static void tick(Minecraft client) {
        if (bindingScreen != null && bindingScreen != MinecraftClientAccess.screen(client))
            bindingScreen = null;
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) {
            if (screen != null) {
                lastStatus = status;
                screen = null;
                session = new InventorySession();
            }
            mouseHeld.clear();
            once = false;
            status = "Open inventory to organize";
            return;
        }
        long now = System.nanoTime();
        enter(current, now);
        if (!InvManagerConfig.enabled() && !once) {
            status = "Automatic off · once key available";
            return;
        }
        var snapshot = snapshot(client);
        session.observe(snapshot, now);
        String wait = waitReason(client, now);
        if (!wait.isEmpty()) {
            status = wait;
            return;
        }
        if (now < nextActionAt) return;
        List<InventoryAction> plan = plan(client, snapshot);
        if (plan.isEmpty()) {
            if (InvManagerConfig.protectSpecial() && !plan(client, snapshot, false).isEmpty()) {
                status = "Paused · Protect special items blocks sorting";
                return;
            }
            status =
                    (once ? "Organized" : "Ready")
                            + (session.protectedSlots().isEmpty()
                                    ? ""
                                    : " · manual slots protected");
            once = false;
            return;
        }
        InventoryAction action = plan.getFirst();
        status = action.reason();
        if (FAILURE.beforeClick(
                client, client.player.inventoryMenu, snapshot.menuSlot(action.source()), null)) {
            status = "Misclick · retrying";
            nextActionAt = now + Math.max(50, InvManagerConfig.nextDelay()) * 1_000_000L;
            return;
        }
        if (!session.reserve(action)
                || !InventoryClicks.swap(
                        client,
                        client.player.inventoryMenu,
                        snapshot.menuSlot(action.source()),
                        snapshot.menuSlot(action.target()),
                        action.target(),
                        action.beforeSource(),
                        action.beforeTarget())) {
            nextActionAt = now + 1_000_000_000L;
            status = "Inventory changed · waiting to retry";
            return;
        }
        nextActionAt = System.nanoTime() + InvManagerConfig.nextDelay() * 1_000_000L;
    }

    private static String waitReason(Minecraft client, long now) {
        if (!InventoryRules.error().isEmpty()) return InventoryRules.error();
        if (!client.isWindowActive()) return "Paused · window unfocused";
        if (!client.player.inventoryMenu.getCarried().isEmpty())
            return "Paused · cursor holds an item";
        if (!mouseHeld.isEmpty() || session.paused(now)) return "Paused · manual input";
        if (AutoTotem.inventoryBusy()
                || InventoryClicks.busyExcept(null)
                || InventoryClicks.recentlyBusy(now)) return "Paused · another inventory action";
        if (HotbarLease.isHeld() || PlacementCoordinator.busy()) return "Paused · hotbar in use";
        if (client.player.isDeadOrDying() || client.player.isSpectator())
            return "Paused · player unavailable";
        if (client.player.isUsingItem() || client.gameMode.isDestroying() || now < combatUntil)
            return "Paused · combat or item use";
        if (!client.player.onGround()
                || client.player.getDeltaMovement().horizontalDistanceSqr() > .0001
                || client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown()
                || client.options.keyJump.isDown()) return "Paused · moving";
        return "";
    }

    private static InventorySnapshot snapshot(Minecraft client) {
        return InventorySnapshot.capture(client.player.inventoryMenu, client.player.getInventory());
    }

    private static BitSet protectedSlots() {
        BitSet blocked = session.protectedSlots();
        if (AutoTotem.reservesOffhand()) blocked.set(40);
        return blocked;
    }

    private static List<InventoryAction> plan(Minecraft client, InventorySnapshot snapshot) {
        return plan(client, snapshot, InvManagerConfig.protectSpecial());
    }

    private static List<InventoryAction> plan(
            Minecraft client, InventorySnapshot snapshot, boolean protectSpecial) {
        var blocked = protectedSlots();
        for (int i = 0; i < 41; i++) {
            int slot = snapshot.menuSlot(i);
            if (slot < 0 || !client.player.inventoryMenu.slots.get(slot).mayPickup(client.player))
                blocked.set(i);
        }
        var result = new ArrayList<InventoryAction>();
        result.addAll(
                InventoryPlan.build(
                        snapshot,
                        InvManagerConfig.roles(),
                        blocked,
                        InvManagerConfig.sort(),
                        InvManagerConfig.refill(),
                        protectSpecial));
        return result.stream()
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

    /** Immutable preview read by the editor; it never performs inventory operations. */
    public static View preview(Minecraft client) {
        var roles = InvManagerConfig.roles();
        var views = new ArrayList<SlotView>();
        InventorySnapshot snapshot = ClientReady.interaction(client) ? snapshot(client) : null;
        for (int i = 0; i < 10; i++) {
            int slot = i == 9 ? 40 : i;
            var item = snapshot == null ? ItemStack.EMPTY : snapshot.item(slot);
            String protection = roles.get(i) == InventoryRole.LOCKED ? "Locked slot" : "";
            if (session.protectedSlots().get(slot)) protection = "Manually placed this session";
            if (!InventoryRules.resolve(i, roles.get(i))
                    .mayMove(item, InvManagerConfig.protectSpecial()))
                protection = InventoryItems.protection(item);
            if (slot == 40 && AutoTotem.reservesOffhand()) protection = "Reserved by AutoTotem";
            views.add(new SlotView(i, roles.get(i), item.copy(), protection));
        }
        var actions = snapshot == null ? List.<InventoryAction>of() : plan(client, snapshot);
        return new View(
                statusText(),
                List.copyOf(views),
                actions.stream().map(InventoryAction::reason).toList());
    }
}
