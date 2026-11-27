package com.blanoir.tempos.client.module.impl.player;

import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.action.UseInputEvent;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/** Repeats vanilla use actions while the user holds their bound use key. */
@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Repeats the use action while you hold the use key.")
public final class RightClick extends ClientFeature {
    public static final RightClick INSTANCE = new RightClick();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("rightclick.enabled").defaultValue(false).build();
    private final DoubleSetting minCps = cps("rightclick.minCps", 13);
    private final DoubleSetting maxCps = cps("rightclick.maxCps", 15);
    private final BooleanSetting blocksOnly =
            new BooleanSetting.Builder().name("rightclick.blocksOnly").defaultValue(false).build();

    private long nextClickAtNanos;

    private RightClick() {
        super("rightclick", "RightClick");
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register("RightClick.tick", event -> tick(event.client()));
        EventBus.USE_INPUT_PRE.register("RightClick.use", EventPriority.LOWEST, this::beforeUse);
        EventBus.CLIENT_CONTEXT_CHANGED.register("RightClick.context", event -> reset());
    }

    private void tick(Minecraft client) {
        if (!canClick(client)) {
            reset();
            return;
        }
        if (System.nanoTime() < this.nextClickAtNanos) return;
        // Run through the existing use hook immediately, leaving no queued key press
        // to leak into a screen or a later tick. Vanilla owns both hands and item cooldowns.
        GameAccess.invokeStartUseItem(client);
    }

    private void beforeUse(UseInputEvent.Pre event) {
        // A placement module's own transaction is independent of the user's click rate.
        if (SilentPacketRotation.isInvokingSimulatedUse() || !canClick(event.client())) return;
        long now = System.nanoTime();
        if (now < this.nextClickAtNanos) {
            // Also gate vanilla held-use repeats (and FastPlace) so low CPS values
            // cannot be exceeded by the ordinary four-tick right-click repeat.
            event.cancel();
            return;
        }
        double cps = RandomRange.nextDouble(this.minCps.get(), this.maxCps.get());
        this.nextClickAtNanos = now + (long) (1_000_000_000.0D / cps);
    }

    private boolean canClick(Minecraft client) {
        return this.enabled.get()
                && ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !client.player.isSpectator()
                && !client.player.isUsingItem()
                && !HotbarLease.isHeld()
                && !RotationLease.hasSilentRotation()
                && PhysicalInput.isGameplayKeyDown(client, client.options.keyUse)
                && client.options.keyUse.isDown()
                && (!this.blocksOnly.get()
                        || client.player.getMainHandItem().getItem() instanceof BlockItem
                        || client.player.getOffhandItem().getItem() instanceof BlockItem);
    }

    public void reset() {
        this.nextClickAtNanos = 0L;
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
            this.minCps.describe(
                    "min_cps", "Min CPS", .1, (client, value) -> setCps(this.minCps, value)),
            this.maxCps.describe(
                    "max_cps", "Max CPS", .1, (client, value) -> setCps(this.maxCps, value)),
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

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return this.settings();
    }
}
