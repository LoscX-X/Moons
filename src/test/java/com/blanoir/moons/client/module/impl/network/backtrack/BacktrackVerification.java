package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.config.Settings;

import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Regressions for bounded combat history, ordered replay and connection startup. */
public final class BacktrackVerification {
    public static void main(String[] args) throws Exception {
        Settings.configure(Files.createTempDirectory("moons-backtrack-verify-"));
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        verifyConfiguration();
        verifyLoginPassesBeforePlayerExists();
        verifyQueue();
        verifyWindow();
        verifyVisuals();
        System.out.println("BACKTRACK_VERIFIED");
    }

    private static void verifyConfiguration() {
        BacktrackConfig defaults = new BacktrackConfig();
        require(
                !defaults.enabled() && defaults.delayMillis() == 200 && defaults.maxRange() == 6,
                "Fresh configuration uses a 200 ms window and six-block limit");
        Settings.setInt("backtrack.delay.min", 100);
        Settings.setInt("backtrack.delay.max", 150);
        Settings.setDouble("backtrack.range.max", 5.5);
        Settings.setString("backtrack.targetMode", "range");
        Settings.setDouble("backtrack.chance", 0);
        Settings.setInt("backtrack.maxQueueSize", 0);
        BacktrackConfig saved = new BacktrackConfig();
        require(
                saved.delayMillis() == 150 && saved.maxRange() == 5.5,
                "Saved upper bounds become the single user-facing values");
        require(
                saved.setDelay(null, "175") == 1 && saved.delayMillis() == 175,
                "Single delay can be changed");
        require(
                Settings.getString("backtrack.targetMode", "missing").equals("missing")
                        && Settings.getString("backtrack.chance", "missing").equals("missing")
                        && Settings.getString("backtrack.maxQueueSize", "missing")
                                .equals("missing"),
                "Editing tuning removes obsolete controls from saved configuration");
        require(
                saved.setDelay(null, "100-200") == 0 && saved.delayMillis() == 175,
                "A removed range syntax cannot silently truncate a delay");
        require(
                saved.setDelay(null, "NaN") == 0 && saved.setDelay(null, "1001") == 0,
                "Invalid delay is rejected without changing it");
        require(
                saved.setRange(null, "NaN") == 0
                        && saved.setRange(null, "Infinity") == 0
                        && saved.setRange(null, "-1") == 0
                        && saved.maxRange() == 5.5,
                "Invalid distances cannot disable range protection");
        require(
                saved.setEsp(null, "wireframe") == 1
                        && saved.espMode() == BacktrackConfig.EspMode.WIREFRAME
                        && saved.setEsp(null, "invalid") == 0
                        && saved.espMode() == BacktrackConfig.EspMode.WIREFRAME,
                "Invalid ESP does not replace the current choice");
        Settings.setDouble("backtrack.range.max", Double.NaN);
        require(new BacktrackConfig().maxRange() == 6, "Malformed saved range falls back safely");
        saved.setRange(null, "6");
        saved.setDelay(null, "200");
        saved.setEsp(null, "box");
    }

    private static void verifyQueue() {
        BacktrackPacketQueue<Integer> queue = new BacktrackPacketQueue<>();
        List<Integer> replayed = new ArrayList<>();
        for (int i = 0; i < 256; i++) require(queue.offer(i, 1000, 100), "Burst fits the queue");
        require(!queue.offer(256, 1000, 100), "Overflow leaves the incoming packet to the caller");
        queue.releaseDue(1099, replayed::add);
        require(
                replayed.isEmpty() && queue.age(1099) == 99,
                "No early replay; HUD reports actual age");
        queue.releaseDue(1100, replayed::add);
        require(
                replayed.size() == 256 && queue.isEmpty(),
                "The whole burst expires without pacing");
        for (int i = 0; i < 256; i++)
            require(replayed.get(i) == i, "Packets replay exactly in order");
        queue.releaseAll(replayed::add);
        require(replayed.size() == 256, "Flush never replays a packet twice");

        replayed.clear();
        queue.offer(1, 1000, 150);
        queue.offer(2, 1020, 100);
        queue.offer(3, 1030, 100);
        queue.releaseDue(1130, replayed::add);
        require(replayed.isEmpty(), "Shorter delay cannot overtake older movement");
        queue.releaseDue(1150, replayed::add);
        require(replayed.equals(List.of(1, 2, 3)), "Monotonic deadlines retain movement order");
        replayed.clear();
        queue.offer(4, 1200, 100);
        queue.releaseDue(1300, replayed::add);
        require(replayed.equals(List.of(4)), "An expired burst adds no latency to later movement");
        queue.offer(5, 1400, 100);
        queue.releaseAll(replayed::add);
        require(
                replayed.equals(List.of(4, 5)) && queue.isEmpty(),
                "Target switches flush pending history");
        queue.offer(6, 1500, 100);
        queue.clear();
        queue.offer(7, 1500, 0);
        queue.releaseDue(1500, replayed::add);
        require(
                replayed.equals(List.of(4, 5, 7)),
                "Context discard drops stale packets and deadlines");
    }

    private static void verifyWindow() {
        BacktrackWindow window = new BacktrackWindow();
        require(window.expired(1000, 0), "Tracking needs an actual attack");
        window.attack(1000, 0);
        window.seed(Vec3.ZERO, 0);
        require(window.ready(1499, 9), "Attack remains active inside both limits");
        require(window.expired(1500, 9), "Wall time bounds tracking between ticks");
        require(window.expired(1499, 10), "Tick lifetime also bounds tracking");
        window.attack(1400, 8);
        require(window.ready(1500, 10), "Repeated attacks extend intent");
        require(
                window.observe(Vec3.ZERO, new Vec3(0.2, 0, 0), 10, 4, 5, 3)
                        == BacktrackWindow.Decision.HOLD,
                "Retreating server movement can preserve useful hit history");
        require(
                window.observe(new Vec3(0.2, 0, 0), new Vec3(0.1, 0, 0), 11, 5, 4, 3)
                        == BacktrackWindow.Decision.RELEASE,
                "Approaching movement releases even if it is still beyond the visible player");
        window.attack(1550, 11);
        require(
                !window.ready(1600, 12) && window.ready(1700, 14),
                "A new attack on the same player does not cancel approach grace");

        BacktrackPacketQueue<Integer> queue = new BacktrackPacketQueue<>();
        queue.offer(1, 1550, 200);
        window.attack(1700, 14);
        List<Integer> replayed = new ArrayList<>();
        queue.releaseDue(1750, replayed::add);
        require(replayed.equals(List.of(1)), "Repeated attacks never postpone queued deadlines");
        require(
                window.observe(new Vec3(0.1, 0, 0), new Vec3(6, 0, 0), 14, 4, 36, 3)
                        == BacktrackWindow.Decision.RESET,
                "A teleport rejects stale history");
        window.reset();
        require(window.expired(1700, 14), "Reset requires a new attack");
        window.attack(2000, 20);
        window.seed(Vec3.ZERO, 20);
        require(
                window.observe(Vec3.ZERO, new Vec3(3, 0, 0), 21, 0, 9, 0)
                        == BacktrackWindow.Decision.HOLD,
                "One plausible move may be tracked");
        require(
                window.observe(new Vec3(3, 0, 0), new Vec3(6, 0, 0), 22, 9, 36, 0)
                        == BacktrackWindow.Decision.RESET,
                "Several fast moves also reject a large jump");
        window.reset();
        window.attack(3000, 30);
        window.seed(new Vec3(100, 0, 0), 30);
        require(
                window.observe(new Vec3(100, 0, 0), new Vec3(100.2, 0, 0), 31, 4, 5, 3)
                        == BacktrackWindow.Decision.HOLD,
                "Target switch does not inherit old positions");
        require(
                !BacktrackWindow.useful(4, 4) && !BacktrackWindow.useful(3, 4),
                "Equal or closer real positions provide no useful history");
        require(
                window.observe(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), 31, 4, 5, 3)
                        == BacktrackWindow.Decision.RESET,
                "Non-finite motion cannot enter history");
    }

    private static void verifyVisuals() {
        require(
                BacktrackVisual.fill(0xffffffff) == 0x14ffffff,
                "A saved opaque color must not obscure the enemy");
        require(
                BacktrackVisual.outline(0xff56cfe1) == 0xbe56cfe1,
                "Outline retains its hue with bounded opacity");
        require(
                BacktrackVisual.fill(0x0056cfe1) == 0x0056cfe1,
                "An explicitly disabled fill remains transparent");
    }

    private static void verifyLoginPassesBeforePlayerExists() throws Exception {
        BacktrackConfig config = new BacktrackConfig();
        config.setEnabled(true);
        BacktrackRuntime runtime = new BacktrackRuntime(config);
        var listener =
                (net.minecraft.network.protocol.game.ClientGamePacketListener)
                        java.lang.reflect.Proxy.newProxyInstance(
                                BacktrackVerification.class.getClassLoader(),
                                new Class<?>[] {
                                    net.minecraft.network.protocol.game.ClientGamePacketListener
                                            .class
                                },
                                (proxy, method, arguments) -> {
                                    throw new AssertionError(
                                            "Login listener must remain untouched");
                                });
        // Construct the selected version's login record; no LocalPlayer exists yet.
        var components =
                net.minecraft.network.protocol.game.ClientboundLoginPacket.class
                        .getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] =
                    types[i] == boolean.class
                            ? Boolean.FALSE
                            : types[i] == int.class
                                    ? Integer.valueOf(0)
                                    : types[i] == java.util.Set.class ? java.util.Set.of() : null;
        }
        var login =
                net.minecraft.network.protocol.game.ClientboundLoginPacket.class
                        .getDeclaredConstructor(types)
                        .newInstance(values);
        require(
                !runtime.handleIncomingPacket(login, listener),
                "Login reaches vanilla before player/connection initialization");
        require(
                !runtime.handleIncomingPacket(
                        new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(1),
                        listener),
                "Keepalive bypasses tracking and never waits behind target movement");
        require(
                !runtime.handleIncomingPacket(
                        new net.minecraft.network.protocol.game.ClientboundSetHealthPacket(
                                20, 20, 5),
                        listener),
                "Nonlethal health updates also bypass movement history");
        runtime.release();
        runtime.discard();
        config.setEnabled(false);
        require(
                !runtime.isLagging() && runtime.hudStats().equals("Ready"),
                "Disable/context reset work without a game or target");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
