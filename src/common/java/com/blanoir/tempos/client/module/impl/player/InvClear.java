package com.blanoir.tempos.client.module.impl.player;

import static com.blanoir.tempos.client.module.framework.SettingFactory.text;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.*;
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
import com.blanoir.tempos.client.module.impl.player.invclear.InventoryCleanup;
import com.blanoir.tempos.client.module.impl.player.invmanager.*;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Low-priority inventory cleanup. Armor and hotbar sorting always get the first opportunity. */
@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Removes unwanted inventory items using your cleanup settings.")
public final class InvClear extends ClientFeature {
    public static final InvClear INSTANCE = new InvClear();

    private final InventoryClickFailure failure = new InventoryClickFailure("invclear");
    public static final String DEFAULT_JUNK =
            "minecraft:rotten_flesh, minecraft:poisonous_potato, minecraft:spider_eye, minecraft:bowl";
    private final BooleanSetting enabled = flag("enabled", false);
    private final BooleanSetting equipment = flag("equipment", true);
    private final BooleanSetting junk = flag("junk", true);
    private final BooleanSetting protectHotbar = flag("protectHotbar", true);
    private final BooleanSetting protectSpecial = flag("protectSpecial", true);
    private final StringSetting dropList = text("dropList", DEFAULT_JUNK);
    private final StringSetting keepList = text("keepList", "");
    private final IntSetting openDelay = delay("openDelayMs", 250);
    private final IntSetting actionDelay = delay("actionDelayMs", 150);
    private final IntSetting manualDelay = delay("manualDelayMs", 500);
    private InventoryScreen screen;
    private InventorySession session = new InventorySession();
    private final Set<Integer> mouseHeld = new HashSet<>();
    private final List<InventoryCleanup.Drop> attempts = new ArrayList<>();
    private long nextActionAt, combatUntil, activityVersion;
    private boolean stalled;
    private String status = "Open inventory to clean";

    private InvClear() {
        super("invclear", "InvClear");
    }

    public boolean enabled() {
        return this.enabled.get();
    }

    public String statusText() {
        return this.status;
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
        EventBus.CLIENT_CONTEXT_CHANGED.register("InvClear.context", event -> reset());
        EventBus.TICK.register(
                "InvClear.tick", EventPriority.LOWEST, event -> tick(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "InvClear.combat",
                event -> {
                    if (event.attacker() == Minecraft.getInstance().player)
                        this.combatUntil = System.nanoTime() + 500_000_000L;
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
                        this.mouseHeld.remove(event.button().button());
                    else if (!event.isCancelled()) this.mouseHeld.add(event.button().button());
                });
    }

    public void reset() {
        this.failure.reset();
        this.screen = null;
        this.session = new InventorySession();
        this.mouseHeld.clear();
        this.attempts.clear();
        this.nextActionAt = this.combatUntil = 0;
        this.activityVersion = InventoryClicks.activityVersion();
        this.stalled = false;
        this.status = "Open inventory to clean";
    }

    private void enter(InventoryScreen current, long now) {
        if (current == this.screen) return;
        this.failure.reset();
        this.screen = current;
        this.session = new InventorySession();
        this.mouseHeld.clear();
        this.attempts.clear();
        this.stalled = false;
        this.activityVersion = InventoryClicks.activityVersion();
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
        if (!enabled()) return;
        if (!ClientReady.interaction(client)
                || !(MinecraftClientAccess.screen(client) instanceof InventoryScreen current)
                || client.player.containerMenu != client.player.inventoryMenu) {
            if (this.screen != null) reset();
            return;
        }
        long now = System.nanoTime();
        enter(current, now);
        if (this.activityVersion != InventoryClicks.activityVersion()) {
            this.activityVersion = InventoryClicks.activityVersion();
            this.nextActionAt =
                    Math.max(
                            this.nextActionAt,
                            now + Math.max(100, this.actionDelay.get()) * 1_000_000L);
        }
        InventorySnapshot snapshot = snapshot(client);
        this.session.observe(snapshot, now);
        if (this.stalled) {
            this.status = "Inventory changed · reopen inventory to retry";
            return;
        }
        if (!client.isWindowActive() || !this.mouseHeld.isEmpty() || this.session.paused(now)) {
            this.status = "Paused · manual input";
            return;
        }
        if (!client.player.inventoryMenu.getCarried().isEmpty()
                || AutoTotem.INSTANCE.inventoryBusy()
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
                || !client.player.onGround()
                || client.player.getDeltaMovement().horizontalDistanceSqr() > .0001
                || client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown()
                || client.options.keyJump.isDown()) {
            this.status = "Paused · movement or combat";
            return;
        }
        if (!InventoryRules.error().isEmpty()) {
            this.status = InventoryRules.error();
            return;
        }
        List<InventoryCleanup.Drop> plan;
        try {
            plan = plan(client, snapshot);
        } catch (IllegalArgumentException invalid) {
            this.status = invalid.getMessage();
            return;
        }
        if (plan.isEmpty()) {
            this.status = "Ready";
            return;
        }
        var drop = plan.getFirst();
        this.status = "Drop " + drop.item().getHoverName().getString() + " · " + drop.reason();
        if (now < this.nextActionAt) return;
        if (this.failure.beforeClick(
                client, client.player.inventoryMenu, snapshot.menuSlot(drop.source()), null)) {
            this.status = "Misclick · retrying";
            this.activityVersion = InventoryClicks.activityVersion();
            this.nextActionAt = now + Math.max(50, this.actionDelay.get()) * 1_000_000L;
            return;
        }
        if (this.attempts.size() >= 100
                || this.attempts.stream()
                                .filter(
                                        old ->
                                                old.source() == drop.source()
                                                        && ItemStack.matches(
                                                                old.item(), drop.item()))
                                .count()
                        >= 2) {
            this.stalled = true;
            return;
        }
        this.attempts.add(drop);
        if (!InventoryClicks.dropStack(
                client,
                client.player.inventoryMenu,
                snapshot.menuSlot(drop.source()),
                drop.item())) {
            this.stalled = true;
            this.status = "Inventory changed · reopen inventory to retry";
        }
        this.activityVersion = InventoryClicks.activityVersion();
        this.nextActionAt = now + this.actionDelay.get() * 1_000_000L;
    }

    private static InventorySnapshot snapshot(Minecraft client) {
        return InventorySnapshot.capture(client.player.inventoryMenu, client.player.getInventory());
    }

    private List<InventoryCleanup.Drop> plan(Minecraft client, InventorySnapshot snapshot) {
        BitSet blocked = this.session.protectedSlots();
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
                this.protectHotbar.get(),
                this.protectSpecial.get(),
                this.equipment.get(),
                this.junk.get() ? InventoryCleanup.itemIds(this.dropList.get()) : Set.of(),
                InventoryCleanup.itemIds(this.keepList.get()));
    }

    public List<InventoryCleanup.Drop> preview(Minecraft client) {
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

    public ModuleModel.Setting[] settings() {
        return new ModuleModel.Setting[] {
            this.failure.setting(),
            this.equipment.describe(
                    "equipment",
                    "Surplus equipment",
                    (c, v) -> {
                        this.equipment.set(v);
                        return 1;
                    }),
            this.junk.describe(
                    "junk",
                    "Listed junk",
                    (c, v) -> {
                        this.junk.set(v);
                        return 1;
                    }),
            this.protectHotbar.describe(
                    "protect_hotbar",
                    "Protect hotbar",
                    (c, v) -> {
                        this.protectHotbar.set(v);
                        return 1;
                    }),
            this.protectSpecial.describe(
                    "protect_special",
                    "Protect special items",
                    (c, v) -> {
                        this.protectSpecial.set(v);
                        return 1;
                    }),
            SettingFactory.text(
                            "drop_list",
                            "Drop item IDs",
                            this.dropList::get,
                            (c, v) -> setList(c, this.dropList, v))
                    .withDefault(DEFAULT_JUNK)
                    .visibleWhen(this.junk::get),
            SettingFactory.text(
                            "keep_list",
                            "Keep item IDs",
                            this.keepList::get,
                            (c, v) -> setList(c, this.keepList, v))
                    .withDefault(""),
            this.openDelay.describe(
                    "open_delay",
                    "Open delay (ms)",
                    10,
                    (c, v) -> {
                        this.openDelay.set(v);
                        return 1;
                    }),
            this.actionDelay.describe(
                    "action_delay",
                    "Action delay (ms)",
                    10,
                    (c, v) -> {
                        this.actionDelay.set(v);
                        return 1;
                    }),
            this.manualDelay.describe(
                    "manual_delay",
                    "Manual pause (ms)",
                    10,
                    (c, v) -> {
                        this.manualDelay.set(v);
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
        return ModulePreview.attach(super.toDescriptor().withHudTag(""),LocalInventoryPreview::cleanup);
    }
}
