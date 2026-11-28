package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.frame.FrameEvent;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.input.click.ClickCadence;
import com.blanoir.tempos.client.manager.input.click.ClickItemList;
import com.blanoir.tempos.client.manager.input.click.ClickJitter;
import com.blanoir.tempos.client.manager.input.click.ClickTiming;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.math.RandomRange;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/** Automatic clicks routed through the shared input controller. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Repeats the attack input within the selected clicks-per-second range.")
public final class AutoClicker extends ClientFeature {
    public static final AutoClicker INSTANCE = new AutoClicker();

    private final BooleanSetting enabled = bool("autoclicker.enabled", false);
    private final DoubleSetting minCps = number("autoclicker.minCps", 13.0D, 1.0D, 20.0D);
    private final DoubleSetting maxCps = number("autoclicker.maxCps", 15.0D, 1.0D, 20.0D);
    private final BooleanSetting breakBlocks = bool("autoclicker.breakBlocks", true);
    private final BooleanSetting holdToClick = bool("autoclicker.holdToClick", true);
    private final BooleanSetting triggerMode = bool("autoclicker.triggerMode", false);
    private final BooleanSetting limitItems = bool("autoclicker.limitItems", false);
    private final BooleanSetting breakWhitelist = bool("autoclicker.breakBlocksWhitelist", false);
    private final BooleanSetting jitter = bool("autoclicker.jitter", false);
    private final DoubleSetting breakDelayMin = number("autoclicker.breakDelayMin", 0, 0, 2000);
    private final DoubleSetting breakDelayMax = number("autoclicker.breakDelayMax", 10, 0, 2000);
    private final ModeSetting<ClickTiming.Mode> randomization =
            new ModeSetting.Builder<ClickTiming.Mode>()
                    .name("autoclicker.randomization")
                    .defaultValue(ClickTiming.Mode.EXTRA)
                    .option(ClickTiming.Mode.NORMAL, "normal")
                    .option(ClickTiming.Mode.EXTRA, "extra")
                    .option(ClickTiming.Mode.EXTRA_PLUS, "extra+")
                    .build();
    private final ClickItemList clickItems =
            new ClickItemList("autoclicker.items", ClickItemList.Family.SWORDS);
    private final ClickItemList miningItems =
            new ClickItemList("autoclicker.breakItems", ClickItemList.Family.TOOLS);
    private final BooleanSetting sag = bool("autoclicker.sag", false);
    private final IntSetting sagBlockTicksSetting =
            integer("autoclicker.sagBlockingTicks", 4, 0, 20);
    private final IntSetting sagUnblockTicksSetting =
            integer("autoclicker.sagUnblockTicks", 0, 0, 20);

    private final ClickCadence cadence = new ClickCadence(RandomRange.nextLong());
    private int sagBlockTicks;
    private int sagUnblockTicks;
    private boolean sagActive, breaking;
    private BlockPos block;
    private long breakAt;
    private final ClickJitter clickJitter = new ClickJitter();

    private AutoClicker() {
        super("autoclicker", "AutoClicker");
    }

    @Override
    public void registerEvents() {
        EventBus.FRAME.register("AutoClicker.frame", this::frame);
        EventBus.TICK.register("AutoClicker.sag", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoClicker.context", event -> resetCycle(event.client()));
    }

    private boolean ready(Minecraft client) {
        return !CombatModuleCoordinator.automationSuspended()
                && enabled.get()
                && ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && (!holdToClick.get()
                        || PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack));
    }

    private boolean sagging(Minecraft client) {
        return sag.get()
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && PhysicalInput.isGameplayKeyDown(client, client.options.keyUse);
    }

    private boolean targetAllowed(Minecraft client) {
        return (!triggerMode.get() || client.hitResult instanceof EntityHitResult)
                && (!limitItems.get() || clickItems.allows(client.player.getMainHandItem()));
    }

    private void frame(FrameEvent event) {
        Minecraft client = event.client();
        if (!ready(client)) {
            resetCycle(client);
            return;
        }
        long now = System.nanoTime();
        breaking = false;
        if (breakBlocks.get()
                && PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack)
                && client.hitResult instanceof BlockHitResult hit
                && (!breakWhitelist.get() || miningItems.allows(client.player.getMainHandItem()))) {
            if (!hit.getBlockPos().equals(block)) {
                block = hit.getBlockPos().immutable();
                breakAt =
                        now
                                + (long)
                                        (RandomRange.nextDouble(
                                                        breakDelayMin.get(), breakDelayMax.get())
                                                * 1_000_000);
            }
            breaking = now - breakAt >= 0;
        } else block = null;
        if (breaking || !targetAllowed(client) || sagging(client)) {
            cadence.reset();
            if (!sagging(client) || breaking) resetSag(client);
            return;
        }
        resetSag(client);
        if (cadence.poll(now, true, minCps.get(), maxCps.get(), randomization.get())) {
            CombatInputController.click(client, client.options.keyAttack);
            if (jitter.get()) clickJitter.clicked();
        }
        if (jitter.get()) clickJitter.frame(client.player, client.options.sensitivity().get());
    }

    private void tick(Minecraft client) {
        if (!ready(client) || breaking || !targetAllowed(client)) {
            resetSag(client);
            return;
        }
        if (jitter.get()) clickJitter.tick();
        if (!sagging(client)) {
            resetSag(client);
            return;
        }
        sagActive = true;
        tickSag(client);
    }

    private void tickSag(Minecraft client) {
        if (this.sagBlockTicks > 0) {
            this.sagBlockTicks--;
            client.options.keyUse.setDown(true);
            return;
        }
        if (this.sagUnblockTicks > 0) {
            this.sagUnblockTicks--;
            client.options.keyUse.setDown(false);
            return;
        }
        client.options.keyUse.setDown(false);
        CombatInputController.click(client, client.options.keyAttack);
        this.sagBlockTicks = this.sagBlockTicksSetting.get();
        this.sagUnblockTicks = this.sagUnblockTicksSetting.get();
        client.options.keyUse.setDown(true);
    }

    private void resetCycle(Minecraft client) {
        cadence.reset();
        block = null;
        breaking = false;
        clickJitter.reset();
        resetSag(client);
    }

    public void reset(Minecraft client) {
        resetCycle(client);
    }

    private void resetSag(Minecraft client) {
        this.sagBlockTicks = 0;
        this.sagUnblockTicks = 0;
        if (sagActive && client != null && client.options != null) {
            client.options.keyUse.setDown(
                    PhysicalInput.isGameplayKeyDown(client, client.options.keyUse));
        }
        sagActive = false;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        if (!value) resetCycle(client);
        ClientChat.send(client, "AutoClicker " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public String hudTag() {
        return format(this.minCps.get()) + "-" + format(this.maxCps.get());
    }

    public int setMinCps(Minecraft ignoredClient, double value) {
        this.minCps.set(value);
        return 1;
    }

    public int setMaxCps(Minecraft ignoredClient, double value) {
        this.maxCps.set(value);
        return 1;
    }

    public int setBreakBlocks(Minecraft ignoredClient, boolean value) {
        this.breakBlocks.set(value);
        return 1;
    }

    public int setSag(Minecraft ignoredClient, boolean value) {
        this.sag.set(value);
        return 1;
    }

    public int setSagBlockingTicks(Minecraft ignoredClient, int value) {
        this.sagBlockTicksSetting.set(value);
        return 1;
    }

    public int setSagUnblockTicks(Minecraft ignoredClient, int value) {
        this.sagUnblockTicksSetting.set(value);
        return 1;
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? Integer.toString((int) value)
                : String.format("%.1f", value);
    }

    private static BooleanSetting bool(String name, boolean value) {
        return new BooleanSetting.Builder().name(name).defaultValue(value).build();
    }

    private static DoubleSetting number(String name, double value, double min, double max) {
        return new DoubleSetting.Builder().name(name).defaultValue(value).range(min, max).build();
    }

    private static IntSetting integer(String name, int value, int min, int max) {
        return new IntSetting.Builder().name(name).defaultValue(value).range(min, max).build();
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            holdToClick.describeStored(
                    "hold_to_click",
                    "Hold to click",
                    (client, value) -> {
                        holdToClick.set(value);
                        resetCycle(client);
                        return 1;
                    }),
            triggerMode.describeStored(
                    "trigger_mode",
                    "Trigger mode",
                    (client, value) -> {
                        triggerMode.set(value);
                        return 1;
                    }),
            minCps.describeStored("min_cps", "Min CPS", .1, this::setMinCps),
            maxCps.describeStored("max_cps", "Max CPS", .1, this::setMaxCps),
            randomization.describe(
                    "randomization",
                    "Randomization",
                    (client, value) -> {
                        randomization.deserialize(value);
                        resetCycle(client);
                        return 1;
                    }),
            jitter.describeStored(
                    "jitter",
                    "Jitter",
                    (client, value) -> {
                        jitter.set(value);
                        clickJitter.reset();
                        return 1;
                    }),
            limitItems.describeStored(
                    "limit_items",
                    "Limit items",
                    (client, value) -> {
                        limitItems.set(value);
                        return 1;
                    }),
            clickItems.setting("item_whitelist", "Item whitelist").visibleWhen(limitItems::get),
            breakBlocks.describeStored("break_blocks", "Break blocks", this::setBreakBlocks),
            breakDelayMin
                    .describeStoredRange(
                            "break_delay",
                            "Break blocks delay",
                            breakDelayMax,
                            1,
                            (client, low, high) -> {
                                breakDelayMin.set(low);
                                breakDelayMax.set(high);
                                block = null;
                                return 1;
                            })
                    .visibleWhen(breakBlocks::get),
            breakWhitelist
                    .describeStored(
                            "break_whitelist",
                            "Break blocks whitelist",
                            (client, value) -> {
                                breakWhitelist.set(value);
                                return 1;
                            })
                    .visibleWhen(breakBlocks::get),
            miningItems
                    .setting("break_items", "Break block items")
                    .visibleWhen(() -> breakBlocks.get() && breakWhitelist.get()),
            sag.describeStored("sag", "Sag", this::setSag),
            sagBlockTicksSetting.describeStored(
                    "sag_block_ticks", "Sag blocking ticks", 1, this::setSagBlockingTicks),
            sagUnblockTicksSetting.describeStored(
                    "sag_unblock_ticks", "Sag unblock ticks", 1, this::setSagUnblockTicks)
        };
    }
}
