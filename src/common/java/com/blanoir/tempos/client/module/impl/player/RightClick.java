package com.blanoir.tempos.client.module.impl.player;

import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.action.UseInputEvent;
import com.blanoir.tempos.client.event.frame.FrameEvent;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.click.ClickItemList;
import com.blanoir.tempos.client.manager.input.click.ClickJitter;
import com.blanoir.tempos.client.manager.input.click.ClickTiming;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/** Repeats vanilla use actions while the user holds their bound use key. */
@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Repeats the use action with configurable click timing.")
public final class RightClick extends ClientFeature {
    public static final RightClick INSTANCE = new RightClick();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("rightclick.enabled").defaultValue(false).build();
    private final DoubleSetting minCps = cps("rightclick.minCps", 13);
    private final DoubleSetting maxCps = cps("rightclick.maxCps", 15);
    private final BooleanSetting blocksOnly =
            new BooleanSetting.Builder().name("rightclick.blocksOnly").defaultValue(false).build();
    private final BooleanSetting holdToClick = bool("rightclick.holdToClick", true);
    private final BooleanSetting sneakOnly = bool("rightclick.sneakOnly", false);
    private final BooleanSetting jitter = bool("rightclick.jitter", false);
    private final BooleanSetting itemWhitelist = bool("rightclick.itemWhitelist", false);
    private final DoubleSetting startDelay =
            new DoubleSetting.Builder()
                    .name("rightclick.startDelay")
                    .defaultValue(0)
                    .range(0, 1000)
                    .build();
    private final ModeSetting<ClickTiming.Mode> randomization =
            new ModeSetting.Builder<ClickTiming.Mode>()
                    .name("rightclick.randomization")
                    .defaultValue(ClickTiming.Mode.EXTRA_PLUS)
                    .option(ClickTiming.Mode.NORMAL, "normal")
                    .option(ClickTiming.Mode.EXTRA, "extra")
                    .option(ClickTiming.Mode.EXTRA_PLUS, "extra+")
                    .build();
    private final ClickItemList items =
            new ClickItemList("rightclick.items", ClickItemList.Family.BLOCKS);
    private final ClickTiming timing = new ClickTiming(RandomRange.nextLong());
    private final ClickJitter clickJitter = new ClickJitter();

    private long nextClickAtNanos;
    private long activationAt;
    private boolean active, hasClicked, invokingAutomatic;

    private RightClick() {
        super("rightclick", "RightClick");
    }

    @Override
    public void registerEvents() {
        EventBus.FRAME.register("RightClick.frame", this::frame);
        EventBus.TICK.register(
                "RightClick.jitter",
                event -> {
                    if (jitter.get() && canClick(event.client())) clickJitter.tick();
                });
        EventBus.USE_INPUT_PRE.register("RightClick.use", EventPriority.LOWEST, this::beforeUse);
        EventBus.CLIENT_CONTEXT_CHANGED.register("RightClick.context", event -> reset());
    }

    private void frame(FrameEvent frame) {
        Minecraft client = frame.client();
        if (!canClick(client)) {
            reset();
            return;
        }
        long now = System.nanoTime();
        activate(now);
        if (now - nextClickAtNanos >= 0) {
            // The existing use hook owns admission; no queued key press survives this frame.
            invokingAutomatic = true;
            try {
                GameAccess.invokeStartUseItem(client);
            } finally {
                invokingAutomatic = false;
            }
        }
        if (jitter.get()) clickJitter.frame(client.player, client.options.sensitivity().get());
    }

    private void beforeUse(UseInputEvent.Pre event) {
        // A placement module's own transaction is independent of the user's click rate.
        if (SilentPacketRotation.isInvokingSimulatedUse() || !canClick(event.client())) return;
        long now = System.nanoTime();
        activate(now);
        // A real first press remains immediate; Start Delay governs automatic repeats.
        if ((hasClicked || invokingAutomatic) && now - nextClickAtNanos < 0) {
            // Also gate vanilla held-use repeats (and FastPlace) so low CPS values
            // cannot be exceeded by the ordinary four-tick right-click repeat.
            event.cancel();
            return;
        }
        recordClick(now);
    }

    private void activate(long now) {
        if (active) return;
        active = true;
        activationAt = now;
        nextClickAtNanos = now + (long) ((50 + startDelay.get()) * 1_000_000);
    }

    private void recordClick(long now) {
        long interval =
                Math.max(
                                50,
                                timing.nextDelayMillis(
                                        now / 1_000_000,
                                        minCps.get(),
                                        maxCps.get(),
                                        randomization.get()))
                        * 1_000_000;
        long base = !hasClicked || now - nextClickAtNanos > 100_000_000 ? now : nextClickAtNanos;
        long next = base + interval;
        if (next - now <= 0) next = now + interval;
        if (next - now < 50_000_000L) next = now + 50_000_000L;
        if (!hasClicked) {
            long start = activationAt + (long) ((50 + startDelay.get()) * 1_000_000);
            if (start - next > 0) next = start;
        }
        nextClickAtNanos = next;
        hasClicked = true;
        if (jitter.get()) clickJitter.clicked();
    }

    private boolean canClick(Minecraft client) {
        return !CombatModuleCoordinator.automationSuspended()
                && this.enabled.get()
                && ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !client.player.isSpectator()
                && !client.player.isUsingItem()
                && !HotbarLease.isHeld()
                && !RotationLease.hasSilentRotation()
                && (!holdToClick.get()
                        || PhysicalInput.isGameplayKeyDown(client, client.options.keyUse)
                                && client.options.keyUse.isDown())
                && (!sneakOnly.get() || client.player.isShiftKeyDown())
                && allowedItems(client)
                && (!this.blocksOnly.get()
                        || client.player.getMainHandItem().getItem() instanceof BlockItem
                        || client.player.getOffhandItem().getItem() instanceof BlockItem);
    }

    public void reset() {
        this.nextClickAtNanos = 0L;
        active = hasClicked = invokingAutomatic = false;
        clickJitter.reset();
    }

    private boolean allowedItems(Minecraft client) {
        if (!itemWhitelist.get()) return true;
        var main = client.player.getMainHandItem();
        var off = client.player.getOffhandItem();
        boolean mainAllowed = items.allows(main), offAllowed = items.allows(off);
        if (!mainAllowed && offAllowed && main.getUseDuration(client.player) > 0) return false;
        return mainAllowed || offAllowed;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.enabled.set(enabled);
        reset();
        ClientChat.send(client, "RightClick " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public String hudTag() {
        return NumberText.trimmedDecimal(Math.min(this.minCps.get(), this.maxCps.get()), 1)
                + "-"
                + NumberText.trimmedDecimal(Math.max(this.minCps.get(), this.maxCps.get()), 1);
    }

    public Setting[] settings() {
        return new Setting[] {
            holdToClick.describe(
                    "hold_to_click",
                    "Hold to click",
                    (client, value) -> {
                        holdToClick.set(value);
                        reset();
                        return 1;
                    }),
            this.minCps.describe(
                    "min_cps", "Min CPS", .1, (client, value) -> setCps(this.minCps, value)),
            this.maxCps.describe(
                    "max_cps", "Max CPS", .1, (client, value) -> setCps(this.maxCps, value)),
            startDelay.describe(
                    "start_delay",
                    "Start Delay",
                    1,
                    (client, value) -> {
                        startDelay.set(value);
                        reset();
                        return 1;
                    }),
            randomization.describe(
                    "randomization",
                    "Randomization",
                    (client, value) -> {
                        randomization.deserialize(value);
                        reset();
                        return 1;
                    }),
            jitter.describe(
                    "jitter",
                    "Jitter",
                    (client, value) -> {
                        jitter.set(value);
                        clickJitter.reset();
                        return 1;
                    }),
            itemWhitelist.describe(
                    "item_whitelist",
                    "Use item whitelist",
                    (client, value) -> {
                        itemWhitelist.set(value);
                        reset();
                        return 1;
                    }),
            items.setting("items", "Item whitelist").visibleWhen(itemWhitelist::get),
            sneakOnly.describe(
                    "sneak_only",
                    "Sneak only",
                    (client, value) -> {
                        sneakOnly.set(value);
                        reset();
                        return 1;
                    }),
            this.blocksOnly.describe(
                    "blocks_only",
                    "Blocks only",
                    (client, value) -> {
                        this.blocksOnly.set(value);
                        reset();
                        return 1;
                    })
        };
    }

    private int setCps(DoubleSetting setting, double value) {
        setting.set(value);
        reset();
        return 1;
    }

    private static DoubleSetting cps(String key, double value) {
        return new DoubleSetting.Builder().name(key).defaultValue(value).range(1, 20).build();
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder().name(key).defaultValue(value).build();
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return this.settings();
    }
}
