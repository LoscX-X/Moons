package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.input.InputConstants;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.inventory.InventoryClickFailure;
import com.blanoir.moons.client.utils.inventory.InventoryClicks;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.item.ItemStack;

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

    private static GuiInventory screen;
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
                "invmanager_once", () -> organizeOnce(Minecraft.getMinecraft()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("InvManager.context", event -> reset());
        // Preserve the shared inventory transaction priority before planning a new action.
        EventBus.TICK.register(
                "InvManager.tick", EventPriority.LOWEST, event -> tick(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "InvManager.combat",
                event -> {
                    if (event.attacker() == Minecraft.getMinecraft().thePlayer)
                        combatUntil = System.nanoTime() + 500_000_000L;
                });
        EventBus.KEY_INPUT.register(
                "InvManager.key",
                EventPriority.HIGHEST,
                event -> {
                    Minecraft client = Minecraft.getMinecraft();
                    var key = ModuleKeybinds.fromEvent(event.key());
                    if (bindingScreen != null
                            && bindingScreen != MinecraftClientAccess.currentScreen(client))
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
                    if (!(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory))
                        return;
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
                    Minecraft client = Minecraft.getMinecraft();
                    if (!(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory))
                        return;
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
        Object current = MinecraftClientAccess.currentScreen(client);
        if (current != null
                && !(current instanceof GuiInventory)
                && !(current instanceof MoonsComposeScreen)) {
            status = "Close the other screen first";
            return;
        }
        if (client.thePlayer.openContainer != client.thePlayer.inventoryContainer
                || !LegacyItems.empty(LegacyItems.carried(client.thePlayer.inventoryContainer))) {
            status = "Finish the current inventory action first";
            return;
        }
        if (!(current instanceof GuiInventory))
            MinecraftClientAccess.setScreen(client, new GuiInventory(client.thePlayer));
        once = true;
        session.retry();
        status = "Organize once queued";
    }

    public static void beginKeyBinding() {
        Object current = MinecraftClientAccess.currentScreen(Minecraft.getMinecraft());
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

    private static void enter(GuiInventory current, long now) {
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
                || !(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory current)
                || client.thePlayer.openContainer != client.thePlayer.inventoryContainer) return;
        long now = System.nanoTime();
        enter(current, now);
        session.manualInput(snapshot(client), now, InvManagerConfig.manualDelay());
    }

    private static void tick(Minecraft client) {
        if (bindingScreen != null && bindingScreen != MinecraftClientAccess.currentScreen(client))
            bindingScreen = null;
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.currentScreen(client) instanceof GuiInventory current)
                || client.thePlayer.openContainer != client.thePlayer.inventoryContainer) {
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
                client,
                client.thePlayer.inventoryContainer,
                snapshot.menuSlot(action.source()),
                null)) {
            status = "Misclick · retrying";
            nextActionAt = now + Math.max(50, InvManagerConfig.nextDelay()) * 1_000_000L;
            return;
        }
        if (!session.reserve(action)
                || !InventoryClicks.swap(
                        client,
                        client.thePlayer.inventoryContainer,
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
        if (!client.inGameHasFocus) return "Paused · window unfocused";
        if (!LegacyItems.empty(LegacyItems.carried(client.thePlayer.inventoryContainer)))
            return "Paused · cursor holds an item";
        if (!mouseHeld.isEmpty() || session.paused(now)) return "Paused · manual input";
        if (InventoryClicks.busyExcept(null) || InventoryClicks.recentlyBusy(now))
            return "Paused · another inventory action";
        if (HotbarLease.isHeld() || PlacementCoordinator.busy()) return "Paused · hotbar in use";
        if (client.thePlayer.isDead || client.thePlayer.isSpectator())
            return "Paused · player unavailable";
        if (client.thePlayer.isUsingItem()
                || client.playerController.getIsHittingBlock()
                || now < combatUntil) return "Paused · combat or item use";
        if (!client.thePlayer.onGround
                || VecMath.horizontalDistanceSqr(VecMath.motion(client.thePlayer)) > .0001
                || client.gameSettings.keyBindForward.isKeyDown()
                || client.gameSettings.keyBindBack.isKeyDown()
                || client.gameSettings.keyBindLeft.isKeyDown()
                || client.gameSettings.keyBindRight.isKeyDown()
                || client.gameSettings.keyBindJump.isKeyDown()) return "Paused · moving";
        return "";
    }

    private static InventorySnapshot snapshot(Minecraft client) {
        return InventorySnapshot.capture(
                client.thePlayer.inventoryContainer, client.thePlayer.inventory);
    }

    private static BitSet protectedSlots() {
        BitSet blocked = session.protectedSlots();
        return blocked;
    }

    private static List<InventoryAction> plan(Minecraft client, InventorySnapshot snapshot) {
        return plan(client, snapshot, InvManagerConfig.protectSpecial());
    }

    private static List<InventoryAction> plan(
            Minecraft client, InventorySnapshot snapshot, boolean protectSpecial) {
        var blocked = protectedSlots();
        for (int i = 0; i < 40; i++) {
            int slot = snapshot.menuSlot(i);
            if (slot < 0
                    || !client.thePlayer
                            .inventoryContainer
                            .inventorySlots
                            .get(slot)
                            .canTakeStack(client.thePlayer)) blocked.set(i);
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

    /** Immutable preview read by the editor; it never performs inventory operations. */
    public static View preview(Minecraft client) {
        var roles = InvManagerConfig.roles();
        var views = new ArrayList<SlotView>();
        InventorySnapshot snapshot = ClientReady.interaction(client) ? snapshot(client) : null;
        for (int i = 0; i < 9; i++) {
            int slot = i;
            var item = snapshot == null ? LegacyItems.EMPTY : snapshot.item(slot);
            String protection = roles.get(i) == InventoryRole.LOCKED ? "Locked slot" : "";
            if (session.protectedSlots().get(slot)) protection = "Manually placed this session";
            if (!InventoryRules.resolve(i, roles.get(i))
                    .mayMove(item, InvManagerConfig.protectSpecial()))
                protection = InventoryItems.protection(item);
            views.add(new SlotView(i, roles.get(i), LegacyItems.copy(item), protection));
        }
        var actions = snapshot == null ? List.<InventoryAction>of() : plan(client, snapshot);
        return new View(
                statusText(),
                List.copyOf(views),
                actions.stream().map(InventoryAction::reason).toList());
    }
}
