package com.blanoir.tempos.client.module.impl.combat;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RandomRange;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.BlockHitResult;

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
    private final BooleanSetting sag = bool("autoclicker.sag", false);
    private final IntSetting sagBlockTicksSetting =
            integer("autoclicker.sagBlockingTicks", 4, 0, 20);
    private final IntSetting sagUnblockTicksSetting =
            integer("autoclicker.sagUnblockTicks", 0, 0, 20);

    private long nextClickAtNanos;
    private int sagBlockTicks;
    private int sagUnblockTicks;

    private AutoClicker() {
        super("autoclicker", "AutoClicker");
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register("AutoClicker.tick", event -> tick(event.client()));
    }

    private void tick(Minecraft client) {
        if (!ClientReady.gameplay(client)
                || !this.enabled.get()
                || !PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack)) {
            resetCycle(client);
            return;
        }
        if (this.breakBlocks.get() && client.hitResult instanceof BlockHitResult) {
            resetSag(client);
            return;
        }

        boolean sagging =
                this.sag.get()
                        && client.player.getMainHandItem().is(ItemTags.SWORDS)
                        && PhysicalInput.isGameplayKeyDown(client, client.options.keyUse);
        if (sagging) {
            tickSag(client);
            return;
        }
        resetSag(client);

        long now = System.nanoTime();
        if (now < this.nextClickAtNanos) return;
        CombatInputController.click(client, client.options.keyAttack);
        scheduleNext(now);
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

    private void scheduleNext(long now) {
        double low = Math.min(this.minCps.get(), this.maxCps.get());
        double high = Math.max(this.minCps.get(), this.maxCps.get());
        double cps = RandomRange.nextDouble(low, high);
        this.nextClickAtNanos = now + (long) (1_000_000_000.0D / Math.max(1.0D, cps));
    }

    private void resetCycle(Minecraft client) {
        this.nextClickAtNanos = 0L;
        resetSag(client);
    }

    private void resetSag(Minecraft client) {
        this.sagBlockTicks = 0;
        this.sagUnblockTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyUse.setDown(
                    PhysicalInput.isGameplayKeyDown(client, client.options.keyUse));
        }
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
            minCps.describeStored("min_cps", "Min CPS", .1, this::setMinCps),
            maxCps.describeStored("max_cps", "Max CPS", .1, this::setMaxCps),
            breakBlocks.describeStored("break_blocks", "Break blocks", this::setBreakBlocks),
            sag.describeStored("sag", "Sag", this::setSag),
            sagBlockTicksSetting.describeStored(
                    "sag_block_ticks", "Sag blocking ticks", 1, this::setSagBlockingTicks),
            sagUnblockTicksSetting.describeStored(
                    "sag_unblock_ticks", "Sag unblock ticks", 1, this::setSagUnblockTicks)
        };
    }
}
