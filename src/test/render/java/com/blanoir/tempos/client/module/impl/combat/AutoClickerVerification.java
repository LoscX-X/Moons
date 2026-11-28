package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.api.ScopedResources;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.input.click.ClickCadence;
import com.blanoir.tempos.client.manager.input.click.ClickJitter;
import com.blanoir.tempos.client.manager.input.click.ClickTiming;
import com.blanoir.tempos.runtime.lifecycle.DefaultResourceScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.TreeMap;

/** Actual timing engine, event registration, profile fields and item predicates; no window. */
public final class AutoClickerVerification {
    public static void main(String[] args) throws Exception {
        for (var mode : ClickTiming.Mode.values()) replay(mode);
        interruptions();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        jitterUnits();
        Path fixtureHome = Path.of(System.getProperty("tempos.home"));
        java.nio.file.Files.createDirectories(fixtureHome);
        Settings.configure(java.nio.file.Files.createTempDirectory(fixtureHome, "config-"));
        var clicker = AutoClicker.INSTANCE;
        var settings = clicker.toDescriptor().settings();
        for (String id :
                new String[] {
                    "hold_to_click",
                    "trigger_mode",
                    "randomization",
                    "jitter",
                    "limit_items",
                    "item_whitelist",
                    "break_blocks",
                    "break_delay",
                    "break_whitelist",
                    "break_items",
                    "sag"
                })
            require(
                    settings.stream().anyMatch(s -> s.id().equals(id)),
                    "Missing AutoClicker control: " + id);
        for (var item : java.util.List.of(Items.STONE, Items.DIAMOND_SWORD))
            item.builtInRegistryHolder()
                    .bindComponents(
                            net.minecraft.core.component.DataComponentMap.builder()
                                    .set(
                                            net.minecraft.core.component.DataComponents
                                                    .MAX_STACK_SIZE,
                                            64)
                                    .build());
        var items =
                new com.blanoir.tempos.client.manager.input.click.ClickItemList(
                        "fixture.items",
                        com.blanoir.tempos.client.manager.input.click.ClickItemList.Family.SWORDS);
        require(
                items.allows(new ItemStack(Items.DIAMOND_SWORD))
                        && !items.allows(new ItemStack(Items.STONE)),
                "Sword defaults");
        JsonArray selected = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("id", "minecraft:stone");
        selected.add(item);
        items.setting("items", "Items").apply().apply(null, selected);
        require(
                items.allows(new ItemStack(Items.STONE))
                        && !items.allows(new ItemStack(Items.DIAMOND_SWORD)),
                "Stored item whitelist");
        items.setting("items", "Items").apply().apply(null, new JsonArray());
        require(!items.allows(new ItemStack(Items.STONE)), "Empty whitelist must stay empty");
        var mode =
                settings.stream()
                        .filter(s -> s.id().equals("randomization"))
                        .findFirst()
                        .orElseThrow();
        mode.apply().apply(null, new JsonPrimitive("extra+"));
        require(mode.value().get().getAsString().equals("extra+"), "Mode was not stored");
        registration(clicker);
        System.out.println(
                "TEMPOS_AUTOCLICKER_VERIFIED modes=3 frame-deadlines fractional-carry no-catchup activation input-gates controls item-whitelists scoped-listeners jitter-mouse-units");
    }

    private static void jitterUnits() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var player =
                (net.minecraft.client.player.LocalPlayer)
                        unsafe.allocateInstance(net.minecraft.client.player.LocalPlayer.class);
        for (double sensitivity : new double[] {0, .5, 1}) {
            var jitter = new ClickJitter();
            for (String name : new String[] {"yaw", "pitch", "steps", "stepCount"}) {
                var state = ClickJitter.class.getDeclaredField(name);
                state.setAccessible(true);
                state.setDouble(jitter, name.equals("yaw") || name.equals("pitch") ? 4 : 3.6);
            }
            player.setYRot(0);
            player.setXRot(89.999f);
            jitter.tick();
            jitter.frame(player, sensitivity);
            double expected = sensitivity == 0 ? .0096 : sensitivity == .5 ? .15 : .6144;
            require(
                    Math.abs(player.getYRot() - expected) < 1e-6,
                    "Jitter bypassed mouse sensitivity or used degrees as mouse counts");
            require(player.getXRot() == 90, "Jitter bypassed vanilla pitch clamp");
            float yaw = player.getYRot();
            jitter.frame(player, sensitivity);
            require(player.getYRot() == yaw, "One jitter sample was applied twice");
            jitter.tick();
            jitter.reset();
            jitter.frame(player, sensitivity);
            require(player.getYRot() == yaw, "Reset retained pending camera movement");
        }
    }

    private static void replay(ClickTiming.Mode mode) {
        var first = new ClickCadence(1729);
        var second = new ClickCadence(1729);
        var intervals = new TreeMap<Long, Integer>();
        long previous = -1;
        int clicks = 0;
        for (long now = 1_000_000_000L; now <= 31_000_000_000L; now += 8_333_333L) {
            boolean clicked = first.poll(now, true, 13, 15, mode);
            require(
                    clicked == second.poll(now, true, 13, 15, mode),
                    "Seeded timing changed between identical runs");
            if (!clicked) continue;
            require(!first.poll(now, true, 13, 15, mode), "More than one click on one frame");
            if (previous >= 0)
                intervals.merge(Math.round((now - previous) / 1_000_000D), 1, Integer::sum);
            previous = now;
            clicks++;
        }
        require(clicks > 150 && clicks < 601, "Unexpected click rate " + mode + ": " + clicks);
        require(
                intervals.size() >= 2,
                "Timing collapsed to one fixed interval: " + mode + intervals);
        System.out.println(
                "AUTOCLICKER_CADENCE_REPLAY mode="
                        + mode
                        + " submitted="
                        + clicks
                        + " frame_intervals_ms="
                        + intervals);
    }

    private static void interruptions() {
        var shortStall = new ClickCadence(42);
        shortStall.poll(0, true, 20, 20, ClickTiming.Mode.NORMAL);
        require(
                shortStall.poll(50_000_000, true, 20, 20, ClickTiming.Mode.NORMAL),
                "20 CPS activation");
        require(
                shortStall.poll(145_000_000, true, 20, 20, ClickTiming.Mode.NORMAL),
                "Short stall recovery");
        require(
                !shortStall.poll(150_000_000, true, 20, 20, ClickTiming.Mode.NORMAL),
                "Short stall created a 5ms catch-up click");
        var clock = new ClickCadence(42);
        var mode = ClickTiming.Mode.EXTRA;
        require(!clock.poll(0, true, 13, 15, mode), "Missing activation delay");
        require(!clock.poll(49_000_000, true, 13, 15, mode), "Premature activation");
        require(clock.poll(50_000_000, true, 13, 15, mode), "Activation did not start");
        require(clock.poll(5_000_000_000L, true, 13, 15, mode), "No click after long stall");
        require(!clock.poll(5_000_000_000L, true, 13, 15, mode), "Stall replay burst");
        require(!clock.poll(5_000_000_000L, false, 13, 15, mode), "Blocked input clicked");
        require(
                !clock.poll(5_000_000_000L, true, 13, 15, mode),
                "Resume bypassed activation delay");
        require(clock.poll(5_050_000_000L, true, 13, 15, mode), "Resume deadline wrong");
    }

    private static void registration(AutoClicker clicker) throws Exception {
        int frame = EventBus.FRAME.listenerCount(), tick = EventBus.TICK.listenerCount();
        // Scope ownership is exercised by the real registerEvents method.
        try (var resources = new DefaultResourceScope()) {
            ScopedResources.run(resources, clicker::registerEvents);
            require(
                    EventBus.FRAME.listenerCount() == frame + 1,
                    "Normal clicks are not registered at frame frequency");
            require(EventBus.TICK.listenerCount() == tick + 1, "Sag tick listener missing");
        }
        require(
                EventBus.FRAME.listenerCount() == frame && EventBus.TICK.listenerCount() == tick,
                "Listeners survived unload");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
