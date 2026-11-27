package com.blanoir.tempos.client.module.impl.player;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.manager.inventory.InventoryClickFailure;
import com.blanoir.tempos.client.manager.inventory.InventoryClicks;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.ui.widget.LocalInventoryPreview;
import com.blanoir.tempos.client.ui.widget.ModulePreview;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.module.impl.player.invmanager.*;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Independent armor scheduling, sharing checked inventory transactions with other modules. */
@ModuleInfo(category = ModuleCategory.PLAYER, description = "Equips armor from your inventory.")
public final class AutoArmor extends ClientFeature {
    public static final AutoArmor INSTANCE = new AutoArmor();

    private final InventoryClickFailure failure = new InventoryClickFailure("autoarmor");

    public record ArmorView(
            int index, ItemStack item, ItemStack candidate, String status, boolean locked) {}

    private final BooleanSetting enabled =
            flag(
                    "enabled",
                    Settings.getBoolean("invmanager.enabled", false)
                            && Settings.getBoolean("invmanager.autoArmor", false));
    private final BooleanSetting keepElytra =
            flag("keepElytra", Settings.getBoolean("invmanager.keepElytra", true));
    private final BooleanSetting protectSpecial =
            flag("protectSpecial", Settings.getBoolean("invmanager.protectSpecial", true));
    private final List<BooleanSetting> locks =
            List.of(lock("feet"), lock("legs"), lock("chest"), lock("head"));
    private final IntSetting openDelay = delay("openDelayMs", 250);
    private final IntSetting manualDelay = delay("manualDelayMs", 500);
    private final IntSetting delayMin = delay("delayMinMs", 100);
    private final IntSetting delayMax = delay("delayMaxMs", 150);
    private InventorySession session = new InventorySession();
    private InventoryScreen screen;
    private final Set<Integer> mouseHeld = new HashSet<>();
    private long nextActionAt, combatUntil;
    private String status = "Open inventory to equip armor";
    private String lastStatus = "";

    private AutoArmor() {
        super("autoarmor", "AutoArmor");
    }

    public boolean enabled() {
        return this.enabled.get();
    }

    public String statusText() {
        return this.screen == null && !this.lastStatus.isEmpty()
                ? "Last inventory: " + this.lastStatus
                : this.status;
    }

    public boolean locked(int index) {
        return this.locks.get(index - 36).get();
    }

    public void setLocked(int index, boolean value) {
        this.locks.get(index - 36).set(value);
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        reset();
        return 1;
    }

    @Override
    public void registerEvents() {
        InventoryClicks.init();
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoArmor.context", event -> reset());
        EventBus.TICK.register("AutoArmor.tick", EventPriority.LOW, event -> tick(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "AutoArmor.combat",
                event -> {
                    if (event.attacker() == Minecraft.getInstance().player)
                        this.combatUntil = System.nanoTime() + 500_000_000L;
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
                        this.mouseHeld.remove(event.button().button());
                    else if (!event.isCancelled()) this.mouseHeld.add(event.button().button());
                });
    }

    public void reset() {
        this.failure.reset();
        this.session = new InventorySession();
        this.screen = null;
        this.mouseHeld.clear();
        this.nextActionAt = this.combatUntil = 0;
        this.status = "Open inventory to equip armor";
        this.lastStatus = "";
    }

    private void enter(InventoryScreen current, long now) {
        if (current == this.screen) return;
        this.failure.reset();
        this.screen = current;
        this.session = new InventorySession();
        this.mouseHeld.clear();
        this.nextActionAt = now + this.openDelay.get() * 1_000_000L;
    }

    private void manualInput(Minecraft client) {
        if (!enabled()
                || !ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) return;
        long now = System.nanoTime();
        enter(current, now);
        this.session.manualInput(snapshot(client), now, this.manualDelay.get());
    }

    private void tick(Minecraft client) {
        long now = System.nanoTime();
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) {
            if (this.screen != null) {
                String previous = this.status;
                reset();
                this.lastStatus = previous;
            }
            return;
        }
        enter(current, now);
        if (!enabled()) return;
        var snapshot = snapshot(client);
        this.session.observe(snapshot, now);
        if (!client.isWindowActive() || !this.mouseHeld.isEmpty() || this.session.paused(now)) {
            this.status = "Paused · manual input";
            return;
        }
        if (!client.player.inventoryMenu.getCarried().isEmpty()) {
            this.status = "Paused · cursor holds an item";
            return;
        }
        if (AutoTotem.INSTANCE.inventoryBusy()
                || InventoryClicks.busyExcept(null)
                || InventoryClicks.recentlyBusy(now)
                || HotbarLease.isHeld()
                || PlacementCoordinator.busy()) {
            this.status = "Paused · another inventory action";
            return;
        }
        if (client.player.isDeadOrDying()
                || client.player.isSpectator()
                || client.player.isUsingItem()
                || client.gameMode.isDestroying()
                || now < this.combatUntil
                || moving(client)) {
            this.status = "Paused · movement or combat";
            return;
        }
        if (now < this.nextActionAt) return;
        var actions = plan(client, snapshot);
        if (actions.isEmpty()) {
            this.status =
                    this.protectSpecial.get() && !plan(client, snapshot, false).isEmpty()
                            ? "Paused · Protect special items blocks armor"
                            : this.session.protectedSlots().isEmpty()
                                    ? "Ready"
                                    : "Ready · manual slots protected";
            return;
        }
        var action = actions.getFirst();
        this.status = action.reason();
        var menu = client.player.inventoryMenu;
        int source = snapshot.menuSlot(action.source());
        int target = snapshot.menuSlot(action.target());
        boolean hotbar = action.source() < 9;
        // QUICK_MOVE needs somewhere to put the old piece. Never drop it to make room.
        if (!hotbar
                && !action.beforeTarget().isEmpty()
                && !InventoryClicks.hasEmptyStorage(client, menu, action.beforeTarget())) {
            this.status = "Paused · free one inventory slot to change armor";
            return;
        }
        int clickSlot = hotbar || !action.beforeTarget().isEmpty() ? target : source;
        if (this.failure.beforeClick(client, menu, clickSlot, null)) {
            this.status = "Misclick · retrying";
            this.nextActionAt = now + Math.max(50, nextDelay()) * 1_000_000L;
            return;
        }
        if (!this.session.reserve(action)) {
            this.status = "Armor action cooling down";
            this.nextActionAt = now + 1_000_000_000L;
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
        this.status = changed ? "Armor updated" : "Inventory changed · waiting to retry";
        this.nextActionAt = now + (changed ? Math.max(50, nextDelay()) : 1000) * 1_000_000L;
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

    private List<InventoryAction> plan(Minecraft client, InventorySnapshot snapshot) {
        return plan(client, snapshot, this.protectSpecial.get());
    }

    private List<InventoryAction> plan(
            Minecraft client, InventorySnapshot snapshot, boolean protectSpecial) {
        var blocked = this.session.protectedSlots();
        var locks = new BitSet(4);
        for (int i = 0; i < 4; i++) if (this.locks.get(i).get()) locks.set(i);
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
                        this.keepElytra.get(),
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

    public List<ArmorView> preview(Minecraft client) {
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
                                    : this.session.protectedSlots().get(index)
                                            ? "Manually placed this session"
                                            : this.keepElytra.get() && item.is(Items.ELYTRA)
                                                    ? "Keeping elytra"
                                                    : this.protectSpecial.get()
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

    public ModuleModel.Setting[] settings() {
        var result = new ArrayList<ModuleModel.Setting>();
        result.add(this.failure.setting());
        result.add(
                this.keepElytra.describe(
                        "keep_elytra",
                        "Keep elytra",
                        (client, value) -> {
                            this.keepElytra.set(value);
                            return 1;
                        }));
        result.add(
                this.protectSpecial.describe(
                        "protect_special",
                        "Protect special items",
                        (client, value) -> {
                            this.protectSpecial.set(value);
                            return 1;
                        }));
        for (int i = 0; i < 4; i++) {
            int part = i;
            result.add(
                    this.locks
                            .get(i)
                            .describe(
                                    "armor_lock_" + i,
                                    "Lock " + InventoryArmor.label(36 + i),
                                    (client, value) -> {
                                        this.locks.get(part).set(value);
                                        return 1;
                                    }));
        }
        result.add(
                this.openDelay.describe(
                        "open_delay",
                        "Open delay (ms)",
                        10,
                        (client, value) -> {
                            this.openDelay.set(value);
                            return 1;
                        }));
        result.add(
                this.manualDelay.describe(
                        "manual_delay",
                        "Manual pause (ms)",
                        10,
                        (client, value) -> {
                            this.manualDelay.set(value);
                            return 1;
                        }));
        result.add(
                SettingFactory.rangeValue(
                                "delay_ms",
                                "Action delay (ms)",
                                () -> Math.min(this.delayMin.get(), this.delayMax.get()),
                                () -> Math.max(this.delayMin.get(), this.delayMax.get()),
                                0,
                                1000,
                                10,
                                (client, min, max) -> {
                                    this.delayMin.set((int) Math.round(min));
                                    this.delayMax.set((int) Math.round(max));
                                    return 1;
                                })
                        .withDefault(100, 150));
        return result.toArray(ModuleModel.Setting[]::new);
    }

    private int nextDelay() {
        return ThreadLocalRandom.current()
                .nextInt(
                        Math.min(this.delayMin.get(), this.delayMax.get()),
                        Math.max(this.delayMin.get(), this.delayMax.get()) + 1);
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

    @Override
    public boolean isEnabled() {
        return this.enabled();
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return this.settings();
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return ModulePreview.attach(super.toDescriptor().withHudTag(""),LocalInventoryPreview::armor);
    }
}
