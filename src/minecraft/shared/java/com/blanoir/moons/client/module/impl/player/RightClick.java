package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.action.UseInputEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Setting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/** Repeats vanilla use actions while the user holds their bound use key. */
public final class RightClick {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("rightclick.enabled").defaultValue(false).build();
    private static final DoubleSetting MIN_CPS = cps("rightclick.minCps", 13);
    private static final DoubleSetting MAX_CPS = cps("rightclick.maxCps", 15);
    private static final BooleanSetting BLOCKS_ONLY =
            new BooleanSetting.Builder().name("rightclick.blocksOnly").defaultValue(false).build();

    private static long nextClickAtNanos;

    private RightClick() {}

    public static void init() {
        EventBus.TICK.register("RightClick.tick", event -> tick(event.client()));
        EventBus.USE_INPUT_PRE.register(
                "RightClick.use", EventPriority.LOWEST, RightClick::beforeUse);
        EventBus.CLIENT_CONTEXT_CHANGED.register("RightClick.context", event -> reset());
    }

    private static void tick(Minecraft client) {
        if (!canClick(client)) {
            reset();
            return;
        }
        if (System.nanoTime() < nextClickAtNanos) return;
        // Run through the existing use hook immediately, leaving no queued key press
        // to leak into a screen or a later tick. Vanilla owns both hands and item cooldowns.
        GameAccess.invokeStartUseItem(client);
    }

    private static void beforeUse(UseInputEvent.Pre event) {
        // A placement module's own transaction is independent of the user's click rate.
        if (SilentPacketRotation.isInvokingSimulatedUse() || !canClick(event.client())) return;
        long now = System.nanoTime();
        if (now < nextClickAtNanos) {
            // Also gate vanilla held-use repeats (and FastPlace) so low CPS values
            // cannot be exceeded by the ordinary four-tick right-click repeat.
            event.cancel();
            return;
        }
        double cps = RandomMath.nextDouble(MIN_CPS.get(), MAX_CPS.get());
        nextClickAtNanos = now + (long) (1_000_000_000.0D / cps);
    }

    private static boolean canClick(Minecraft client) {
        return ENABLED.get()
                && ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !client.player.isSpectator()
                && !client.player.isUsingItem()
                && !HotbarLease.isHeld()
                && !RotationLease.hasSilentRotation()
                && CombatInputController.isPhysicallyDown(client, client.options.keyUse)
                && client.options.keyUse.isDown()
                && (!BLOCKS_ONLY.get()
                        || client.player.getMainHandItem().getItem() instanceof BlockItem
                        || client.player.getOffhandItem().getItem() instanceof BlockItem);
    }

    public static void reset() {
        nextClickAtNanos = 0L;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        reset();
        ClientChat.send(client, "RightClick " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static String hudTag() {
        return NumberText.trimmedDecimal(Math.min(MIN_CPS.get(), MAX_CPS.get()), 1)
                + "-"
                + NumberText.trimmedDecimal(Math.max(MIN_CPS.get(), MAX_CPS.get()), 1);
    }

    public static Setting[] settings() {
        return new Setting[] {
            MIN_CPS.describe("min_cps", "Min CPS", .1, (client, value) -> setCps(MIN_CPS, value)),
            MAX_CPS.describe("max_cps", "Max CPS", .1, (client, value) -> setCps(MAX_CPS, value)),
            BLOCKS_ONLY.describe(
                    "blocks_only",
                    "Blocks only",
                    (client, value) -> {
                        BLOCKS_ONLY.set(value);
                        reset();
                        return 1;
                    })
        };
    }

    private static int setCps(DoubleSetting setting, double value) {
        setting.set(value);
        reset();
        return 1;
    }

    private static DoubleSetting cps(String key, double value) {
        return new DoubleSetting.Builder().name(key).defaultValue(value).range(1, 20).build();
    }
}
