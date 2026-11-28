package com.blanoir.tempos.client.module.impl.player;

import com.blanoir.tempos.api.ScopedResources;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.input.click.ClickTiming;
import com.blanoir.tempos.runtime.lifecycle.DefaultResourceScope;
import com.google.gson.JsonPrimitive;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Path;
import java.util.TreeMap;

/** Production right-use scheduling, controls and listener ownership; no game or use packets. */
public final class RightClickVerification {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path fixtureHome = Path.of(System.getProperty("tempos.home"));
        java.nio.file.Files.createDirectories(fixtureHome);
        Settings.configure(java.nio.file.Files.createTempDirectory(fixtureHome, "config-"));
        var clicker = RightClick.INSTANCE;
        var settings = clicker.toDescriptor().settings();
        for (String id :
                new String[] {
                    "min_cps",
                    "max_cps",
                    "start_delay",
                    "randomization",
                    "jitter",
                    "item_whitelist",
                    "items",
                    "sneak_only",
                    "hold_to_click",
                    "blocks_only"
                })
            require(
                    settings.stream().anyMatch(s -> s.id().equals(id)),
                    "Missing right-click control: " + id);
        var activate = RightClick.class.getDeclaredMethod("activate", long.class);
        var record = RightClick.class.getDeclaredMethod("recordClick", long.class);
        var deadline = RightClick.class.getDeclaredField("nextClickAtNanos");
        var timing = RightClick.class.getDeclaredField("timing");
        for (var field :
                new java.lang.reflect.AccessibleObject[] {activate, record, deadline, timing})
            field.setAccessible(true);
        var modeSetting =
                settings.stream()
                        .filter(s -> s.id().equals("randomization"))
                        .findFirst()
                        .orElseThrow();
        for (String mode : new String[] {"normal", "extra", "extra+"}) {
            modeSetting.apply().apply(null, new JsonPrimitive(mode));
            timing.set(clicker, new ClickTiming(1729));
            activate.invoke(clicker, 1_000_000_000L);
            var intervals = new TreeMap<Long, Integer>();
            long previous = -1;
            int clicks = 0;
            for (long now = 1_000_000_000L; now <= 31_000_000_000L; now += 8_333_333L) {
                if (now - deadline.getLong(clicker) < 0) continue;
                record.invoke(clicker, now);
                require(
                        deadline.getLong(clicker) - now > 0,
                        "Right-click can replay more than once on one frame");
                if (previous >= 0)
                    intervals.merge(Math.round((now - previous) / 1_000_000D), 1, Integer::sum);
                previous = now;
                clicks++;
            }
            require(
                    intervals.size() > 1 && clicks > 150 && clicks < 601,
                    "Right-click cadence is fixed or out of range");
            System.out.println(
                    "RIGHTCLICK_CADENCE_REPLAY mode="
                            + mode
                            + " submitted="
                            + clicks
                            + " frame_intervals_ms="
                            + intervals);
            record.invoke(clicker, 90_000_000_000L);
            require(
                    deadline.getLong(clicker) > 90_000_000_000L,
                    "Stall left an overdue right-click deadline");
            clicker.reset();
            require(deadline.getLong(clicker) == 0, "Right-click reset kept pending work");
        }
        settings.stream()
                .filter(s -> s.id().equals("start_delay"))
                .findFirst()
                .orElseThrow()
                .apply()
                .apply(null, new JsonPrimitive(200));
        activate.invoke(clicker, 1_000_000_000L);
        require(
                deadline.getLong(clicker) == 1_250_000_000L,
                "Start Delay does not include activation warmup");
        record.invoke(clicker, 1_000_000_000L);
        require(
                deadline.getLong(clicker) >= 1_250_000_000L,
                "Manual first use shortened automatic Start Delay");
        clicker.reset();
        for (var setting : settings) {
            if (setting.id().equals("min_cps") || setting.id().equals("max_cps"))
                setting.apply().apply(null, new JsonPrimitive(20));
            if (setting.id().equals("start_delay"))
                setting.apply().apply(null, new JsonPrimitive(0));
        }
        modeSetting.apply().apply(null, new JsonPrimitive("normal"));
        activate.invoke(clicker, 0L);
        record.invoke(clicker, 50_000_000L);
        record.invoke(clicker, 145_000_000L);
        require(
                deadline.getLong(clicker) >= 195_000_000L,
                "Short stall created a right-click catch-up burst");
        registration(clicker);
        System.out.println(
                "TEMPOS_RIGHTCLICK_VERIFIED modes=3 frame-deadlines fractional-carry no-catchup manual-first-use start-delay controls scoped-listeners");
    }

    private static void registration(RightClick clicker) throws Exception {
        int frame = EventBus.FRAME.listenerCount(), use = EventBus.USE_INPUT_PRE.listenerCount();
        int tick = EventBus.TICK.listenerCount();
        try (var resources = new DefaultResourceScope()) {
            ScopedResources.run(resources, clicker::registerEvents);
            require(
                    EventBus.FRAME.listenerCount() == frame + 1,
                    "Right-click is not registered at frame frequency");
            require(
                    EventBus.USE_INPUT_PRE.listenerCount() == use + 1,
                    "Vanilla use admission gate missing");
            require(EventBus.TICK.listenerCount() == tick + 1, "Jitter tick listener missing");
        }
        require(
                EventBus.FRAME.listenerCount() == frame
                        && EventBus.USE_INPUT_PRE.listenerCount() == use
                        && EventBus.TICK.listenerCount() == tick,
                "Right-click listeners survived unload");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
