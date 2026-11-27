package com.blanoir.tempos.client.module.impl.player.invmanager;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.input.InputKeys;
import com.blanoir.tempos.client.manager.inventory.InventoryClickFailure;
import com.blanoir.tempos.client.manager.inventory.InventoryClicks;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleKeybinds;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.player.AutoTotem;
import com.blanoir.tempos.client.ui.clickgui.CategoryScreen;
import com.blanoir.tempos.client.lifecycle.ClientReady;
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
@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Sorts inventory items and refills hotbar slots.")
public final class InvManager extends ClientFeature {
    public static final InvManager INSTANCE = new InvManager();

    private final InventoryClickFailure failure = new InventoryClickFailure("invmanager");

    public com.blanoir.tempos.client.module.framework.ModuleModel.Setting failureSetting() {
        return this.failure.setting();
    }

    public record SlotView(int index, InventoryRole role, ItemStack item, String protection) {}

    public record View(String status, List<SlotView> slots, List<String> plan) {}

    private InventoryScreen screen;
    private InventorySession session = new InventorySession();
    private final Set<Integer> mouseHeld = new HashSet<>();
    private boolean once;
    private long nextActionAt;
    private long combatUntil;
    private String status = "Open inventory to organize";
    private String lastStatus = "";
    private Object bindingScreen;

    private InvManager() {
        super("invmanager", "InvManager");
    }

    @Override
    public void registerEvents() {
        InventoryClicks.init();
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
                        this.combatUntil = System.nanoTime() + 500_000_000L;
                });
        EventBus.KEY_INPUT.register(
                "InvManager.key",
                EventPriority.HIGHEST,
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    var key = InputKeys.fromEvent(event.key());
                    if (this.bindingScreen != null
                            && this.bindingScreen != MinecraftClientAccess.screen(client))
                        this.bindingScreen = null;
                    if (this.bindingScreen != null && event.action() == InputConstants.PRESS) {
                        if (key.getValue() == InputConstants.KEY_ESCAPE) this.bindingScreen = null;
                        else if (key.getValue() == InputConstants.KEY_BACKSPACE
                                || key.getValue() == InputConstants.KEY_DELETE) {
                            Settings.setString(InvManagerConfig.ONCE_KEY, "");
                            this.bindingScreen = null;
                        } else if (InputKeys.isValid(key) && !ModuleKeybinds.isGuiKey(key)) {
                            Settings.setString(InvManagerConfig.ONCE_KEY, key.getName());
                            this.bindingScreen = null;
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
                        this.mouseHeld.remove(event.button().button());
                    else if (!event.isCancelled()) this.mouseHeld.add(event.button().button());
                });
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        reset();
        InvManagerConfig.enabled(value);
        ClientChat.send(client, "InvManager " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public void organizeOnce(Minecraft client) {
        if (!ClientReady.interaction(client)) {
            this.status = "Join a world first";
            return;
        }
        Object current = MinecraftClientAccess.screen(client);
        if (current != null
                && !(current instanceof InventoryScreen)
                && !(current instanceof CategoryScreen)) {
            this.status = "Close the other screen first";
            return;
        }
        if (client.player.containerMenu != client.player.inventoryMenu
                || !client.player.inventoryMenu.getCarried().isEmpty()) {
            this.status = "Finish the current inventory action first";
            return;
        }
        if (!(current instanceof InventoryScreen))
            MinecraftClientAccess.setScreen(client, new InventoryScreen(client.player));
        this.once = true;
        this.session.retry();
        this.status = "Organize once queued";
    }

    public void beginKeyBinding() {
        Object current = MinecraftClientAccess.screen(Minecraft.getInstance());
        if (current instanceof CategoryScreen) this.bindingScreen = current;
    }

    public boolean bindingKey() {
        return this.bindingScreen != null;
    }

    public void cancelKeyBinding() {
        this.bindingScreen = null;
    }

    public String statusText() {
        return this.screen == null && !this.lastStatus.isEmpty()
                ? "Last inventory: " + this.lastStatus
                : this.status;
    }

    public void reset() {
        this.failure.reset();
        this.screen = null;
        this.session = new InventorySession();
        this.mouseHeld.clear();
        this.once = false;
        this.bindingScreen = null;
        this.nextActionAt = 0;
        this.combatUntil = 0;
        this.status = "Open inventory to organize";
        this.lastStatus = "";
    }

    private void enter(InventoryScreen current, long now) {
        if (this.screen == current) return;
        this.failure.reset();
        this.screen = current;
        this.session = new InventorySession();
        this.mouseHeld.clear();
        this.nextActionAt = now + InvManagerConfig.openDelay() * 1_000_000L;
    }

    private void manualInput(Minecraft client) {
        if ((!InvManagerConfig.enabled() && !this.once)
                || !ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) return;
        long now = System.nanoTime();
        enter(current, now);
        this.session.manualInput(snapshot(client), now, InvManagerConfig.manualDelay());
    }

    private void tick(Minecraft client) {
        if (this.bindingScreen != null
                && this.bindingScreen != MinecraftClientAccess.screen(client))
            this.bindingScreen = null;
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) {
            if (this.screen != null) {
                this.lastStatus = this.status;
                this.screen = null;
                this.session = new InventorySession();
            }
            this.mouseHeld.clear();
            this.once = false;
            this.status = "Open inventory to organize";
            return;
        }
        long now = System.nanoTime();
        enter(current, now);
        if (!InvManagerConfig.enabled() && !this.once) {
            this.status = "Automatic off · once key available";
            return;
        }
        var snapshot = snapshot(client);
        this.session.observe(snapshot, now);
        String wait = waitReason(client, now);
        if (!wait.isEmpty()) {
            this.status = wait;
            return;
        }
        if (now < this.nextActionAt) return;
        List<InventoryAction> plan = plan(client, snapshot);
        if (plan.isEmpty()) {
            if (InvManagerConfig.protectSpecial() && !plan(client, snapshot, false).isEmpty()) {
                this.status = "Paused · Protect special items blocks sorting";
                return;
            }
            this.status =
                    (this.once ? "Organized" : "Ready")
                            + (this.session.protectedSlots().isEmpty()
                                    ? ""
                                    : " · manual slots protected");
            this.once = false;
            return;
        }
        InventoryAction action = plan.getFirst();
        this.status = action.reason();
        if (this.failure.beforeClick(
                client, client.player.inventoryMenu, snapshot.menuSlot(action.source()), null)) {
            this.status = "Misclick · retrying";
            this.nextActionAt = now + Math.max(50, InvManagerConfig.nextDelay()) * 1_000_000L;
            return;
        }
        if (!this.session.reserve(action)
                || !InventoryClicks.swap(
                        client,
                        client.player.inventoryMenu,
                        snapshot.menuSlot(action.source()),
                        snapshot.menuSlot(action.target()),
                        action.target(),
                        action.beforeSource(),
                        action.beforeTarget())) {
            this.nextActionAt = now + 1_000_000_000L;
            this.status = "Inventory changed · waiting to retry";
            return;
        }
        this.nextActionAt = System.nanoTime() + InvManagerConfig.nextDelay() * 1_000_000L;
    }

    private String waitReason(Minecraft client, long now) {
        if (!InventoryRules.error().isEmpty()) return InventoryRules.error();
        if (!client.isWindowActive()) return "Paused · window unfocused";
        if (!client.player.inventoryMenu.getCarried().isEmpty())
            return "Paused · cursor holds an item";
        if (!this.mouseHeld.isEmpty() || this.session.paused(now)) return "Paused · manual input";
        if (AutoTotem.INSTANCE.inventoryBusy()
                || InventoryClicks.busyExcept(null)
                || InventoryClicks.recentlyBusy(now)) return "Paused · another inventory action";
        if (HotbarLease.isHeld() || PlacementCoordinator.busy()) return "Paused · hotbar in use";
        if (client.player.isDeadOrDying() || client.player.isSpectator())
            return "Paused · player unavailable";
        if (client.player.isUsingItem() || client.gameMode.isDestroying() || now < this.combatUntil)
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

    private BitSet protectedSlots() {
        BitSet blocked = this.session.protectedSlots();
        if (AutoTotem.INSTANCE.reservesOffhand()) blocked.set(40);
        return blocked;
    }

    private List<InventoryAction> plan(Minecraft client, InventorySnapshot snapshot) {
        return plan(client, snapshot, InvManagerConfig.protectSpecial());
    }

    private List<InventoryAction> plan(
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
    public View preview(Minecraft client) {
        var roles = InvManagerConfig.roles();
        var views = new ArrayList<SlotView>();
        InventorySnapshot snapshot = ClientReady.interaction(client) ? snapshot(client) : null;
        for (int i = 0; i < 10; i++) {
            int slot = i == 9 ? 40 : i;
            var item = snapshot == null ? ItemStack.EMPTY : snapshot.item(slot);
            String protection = roles.get(i) == InventoryRole.LOCKED ? "Locked slot" : "";
            if (this.session.protectedSlots().get(slot))
                protection = "Manually placed this session";
            if (!InventoryRules.resolve(i, roles.get(i))
                    .mayMove(item, InvManagerConfig.protectSpecial()))
                protection = InventoryItems.protection(item);
            if (slot == 40 && AutoTotem.INSTANCE.reservesOffhand())
                protection = "Reserved by AutoTotem";
            views.add(new SlotView(i, roles.get(i), item.copy(), protection));
        }
        var actions = snapshot == null ? List.<InventoryAction>of() : plan(client, snapshot);
        return new View(
                statusText(),
                List.copyOf(views),
                actions.stream().map(InventoryAction::reason).toList());
    }

    @Override
    public boolean isEnabled() {
        return InvManagerConfig.enabled();
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return InvManagerConfig.settings();
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withHudTag("");
    }
}
