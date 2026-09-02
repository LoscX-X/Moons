package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.management.input.CombatInputController;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.BlockHitResult;

import java.util.concurrent.ThreadLocalRandom;

/** OpenExpo AutoClicker behavior adapted to the standalone input controller. */
public final class AutoClicker {
    private static final BooleanSetting ENABLED = bool("autoclicker.enabled", false);
    private static final DoubleSetting MIN_CPS = number("autoclicker.minCps", 13.0D, 1.0D, 20.0D);
    private static final DoubleSetting MAX_CPS = number("autoclicker.maxCps", 15.0D, 1.0D, 20.0D);
    private static final BooleanSetting BREAK_BLOCKS = bool("autoclicker.breakBlocks", true);
    private static final BooleanSetting SAG = bool("autoclicker.sag", false);
    private static final IntSetting SAG_BLOCK_TICKS = integer("autoclicker.sagBlockingTicks", 4, 0, 20);
    private static final IntSetting SAG_UNBLOCK_TICKS = integer("autoclicker.sagUnblockTicks", 0, 0, 20);

    private static long nextClickAtNanos;
    private static int sagBlockTicks;
    private static int sagUnblockTicks;

    private AutoClicker() { }

    public static void init() { EventBus.TICK.register("AutoClicker.tick", event -> tick(event.client())); }

    private static void tick(Minecraft client) {
        if (!isReady(client) || !ENABLED.get()
                || !CombatInputController.isPhysicallyDown(client, client.options.keyAttack)) {
            resetCycle(client);
            return;
        }
        if (BREAK_BLOCKS.get() && client.hitResult instanceof BlockHitResult) {
            resetSag(client);
            return;
        }
        boolean sagging = SAG.get()
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && CombatInputController.isPhysicallyDown(client, client.options.keyUse);
        if (sagging) {
            tickSag(client);
            return;
        }
        resetSag(client);
        long now = System.nanoTime();
        if (now < nextClickAtNanos) return;
        CombatInputController.click(client, client.options.keyAttack);
        scheduleNext(now);
    }

    private static void tickSag(Minecraft client) {
        if (sagBlockTicks > 0) {
            sagBlockTicks--;
            client.options.keyUse.setDown(true);
            return;
        }
        if (sagUnblockTicks > 0) {
            sagUnblockTicks--;
            client.options.keyUse.setDown(false);
            return;
        }
        client.options.keyUse.setDown(false);
        CombatInputController.click(client, client.options.keyAttack);
        sagBlockTicks = SAG_BLOCK_TICKS.get();
        sagUnblockTicks = SAG_UNBLOCK_TICKS.get();
        client.options.keyUse.setDown(true);
    }

    private static void scheduleNext(long now) {
        double low = Math.min(MIN_CPS.get(), MAX_CPS.get());
        double high = Math.max(MIN_CPS.get(), MAX_CPS.get());
        double cps = low == high ? low : ThreadLocalRandom.current().nextDouble(low, high);
        nextClickAtNanos = now + (long) (1_000_000_000.0D / Math.max(1.0D, cps));
    }

    private static boolean isReady(Minecraft client) {
        return client != null && client.player != null && client.level != null
                && client.gameMode != null && MinecraftClientAccess.screen(client) == null;
    }

    private static void resetCycle(Minecraft client) { nextClickAtNanos = 0L; resetSag(client); }

    private static void resetSag(Minecraft client) {
        sagBlockTicks = 0;
        sagUnblockTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyUse.setDown(CombatInputController.isPhysicallyDown(client, client.options.keyUse));
        }
    }

    public static boolean isEnabled() { return ENABLED.get(); }
    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) resetCycle(client);
        ClientChat.send(client, "AutoClicker " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }
    public static String hudTag() { return format(MIN_CPS.get()) + "-" + format(MAX_CPS.get()); }
    public static int setMinCps(Minecraft client, double value) { MIN_CPS.set(value); return 1; }
    public static int setMaxCps(Minecraft client, double value) { MAX_CPS.set(value); return 1; }
    public static int setBreakBlocks(Minecraft client, boolean value) { BREAK_BLOCKS.set(value); return 1; }
    public static int setSag(Minecraft client, boolean value) { SAG.set(value); return 1; }
    public static int setSagBlockingTicks(Minecraft client, int value) { SAG_BLOCK_TICKS.set(value); return 1; }
    public static int setSagUnblockTicks(Minecraft client, int value) { SAG_UNBLOCK_TICKS.set(value); return 1; }

    private static String format(double value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : String.format("%.1f", value);
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
}
