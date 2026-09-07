package com.blanoir.moons.client.module.impl.network;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.management.network.PacketDelayQueue;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.RandomMath;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import java.util.List;

/**
 * Standalone constant outgoing FakeLag. Unlike the health and encounter modes,
 * this mode runs continuously and has its own queue, timing and configuration.
 */
public final class FakeLag {
    private static final int MAX_DELAY_MS = 5000;
    private static final int MAX_RECOIL_MS = 2000;
    private static final int MAX_QUEUE_SIZE = 512;

    private static final Object LOCK = new Object();
    private static final PacketDelayQueue PACKETS = new PacketDelayQueue(MAX_QUEUE_SIZE);

    private static final BooleanSetting MASTER_ENABLED =
            new BooleanSetting.Builder().name("fakelag.enabled").defaultValue(false).build();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("fakelag.mode")
                    .defaultValue(Mode.CONSTANT)
                    .option(Mode.CONSTANT, "constant")
                    .option(Mode.LOW_HEALTH, "low_health")
                    .option(Mode.RANDOM, "random")
                    .build();

    private static final BooleanSetting CONSTANT_ENABLED =
            new BooleanSetting.Builder()
                    .name("constantfakelag.enabled")
                    .defaultValue(false)
                    .build();

    private static final IntSetting DELAY_MIN =
            new IntSetting.Builder()
                    .name("constantfakelag.delay.min")
                    .defaultValue(300)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private static final IntSetting DELAY_MAX =
            new IntSetting.Builder()
                    .name("constantfakelag.delay.max")
                    .defaultValue(600)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private static final IntSetting RECOIL_MS =
            new IntSetting.Builder()
                    .name("constantfakelag.recoil")
                    .defaultValue(250)
                    .range(0, MAX_RECOIL_MS)
                    .build();

    private static boolean queueing;
    private static long startedAtMs;
    private static long durationMs;
    private static long resumeAtMs;

    private FakeLag() {}

    public static void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "FakeLag.packetSend",
                event -> {
                    if (handleOutgoing(event.connection(), event.packet())
                            || LowHealthFakeLag.handleOutgoing(event.connection(), event.packet())
                            || RandomFakeLag.handleOutgoing(event.connection(), event.packet())) {
                        event.cancel();
                    }
                });
        EventBus.PACKET_RECEIVE_PRE.register(
                "FakeLag.packetReceive",
                event -> {
                    // Expanded children re-enter PRE individually. Preserve per-child mode order.
                    if (event.bundleExpansionRequested()) return;
                    PacketAccess.forEachPacket(
                            event.packet(),
                            packet -> {
                                handleIncoming(packet);
                                LowHealthFakeLag.handleIncoming(packet);
                                RandomFakeLag.handleIncoming(packet);
                            });
                });
    }

    public static void init() {
        normalizeSettings();
        applyModeSelection(Minecraft.getInstance());
        EventBus.TICK.register("FakeLag.tick", FakeLag::tick);
    }

    private static void tick(TickEvent event) {
        Minecraft client = event.client();
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (!constantActive() || !ready(client)) {
                stopLocked(now, false);
                return;
            }

            if (queueing) {
                if (now - startedAtMs >= durationMs) {
                    stopLocked(now, true);
                }
                return;
            }

            if (now >= resumeAtMs) {
                queueing = true;
                startedAtMs = now;
                durationMs = randomDelayMs();
            }
        }
    }

    /** @return true when the original send must be cancelled. */
    public static boolean handleOutgoing(Connection connection, Packet<?> packet) {
        if (isAnyFakeLagReplaying()) {
            return false;
        }

        synchronized (LOCK) {
            PACKETS.observe(connection);
            if (!constantActive() || !queueing) {
                return false;
            }

            long now = System.currentTimeMillis();
            if (!ready(Minecraft.getInstance())
                    || now - startedAtMs >= durationMs
                    || PACKETS.isFull()
                    || FakeLagPacketPolicy.mustFlushBefore(packet)) {
                stopLocked(now, true);
                return false;
            }

            PACKETS.offer(packet);
            return true;
        }
    }

    public static void handleIncoming(Packet<?> packet) {
        synchronized (LOCK) {
            if (constantActive()
                    && (queueing || !PACKETS.isEmpty())
                    && FakeLagPacketPolicy.mustFlushOnIncoming(Minecraft.getInstance(), packet)) {
                stopLocked(System.currentTimeMillis(), true);
            }
        }
    }

    public static boolean isEnabled() {
        return MASTER_ENABLED.get();
    }

    public static String modeName() {
        return normalizedMode();
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return queueing || !PACKETS.isEmpty();
        }
    }

    public static boolean isReplaying() {
        return PACKETS.isReplaying();
    }

    private static boolean isAnyFakeLagReplaying() {
        return isReplaying() || LowHealthFakeLag.isReplaying() || RandomFakeLag.isReplaying();
    }

    private static void stopLocked(long now, boolean recoil) {
        flushLocked();
        queueing = false;
        startedAtMs = 0L;
        durationMs = 0L;
        resumeAtMs = recoil ? now + RECOIL_MS.get() : now;
    }

    private static void flushLocked() {
        PACKETS.flush();
    }

    private static boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client) && !client.player.isInWater();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        MASTER_ENABLED.set(value);
        applyModeSelection(client);
        return showStatus(client);
    }

    public static int setMode(Minecraft client, String value) {
        MODE.deserialize(value);
        applyModeSelection(client);
        return showStatus(client);
    }

    public static int setDelay(Minecraft ignoredClient, int min, int max) {
        synchronized (LOCK) {
            DELAY_MIN.set(Math.min(min, max));
            DELAY_MAX.set(Math.max(min, max));
            stopLocked(System.currentTimeMillis(), false);
        }
        return 1;
    }

    public static int setRecoil(Minecraft ignoredClient, int value) {
        RECOIL_MS.set(value);
        return 1;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "FakeLag: "
                        + (MASTER_ENABLED.get() ? "enabled" : "disabled")
                        + ", mode "
                        + modeName()
                        + ", constant "
                        + DELAY_MIN.get()
                        + "-"
                        + DELAY_MAX.get()
                        + "ms, recoil "
                        + RECOIL_MS.get()
                        + "ms.");
        return 1;
    }

    private static boolean constantActive() {
        return MASTER_ENABLED.get() && MODE.get() == Mode.CONSTANT;
    }

    private static void applyModeSelection(Minecraft client) {
        boolean enabled = MASTER_ENABLED.get();
        String selected = MODE.serialized();
        synchronized (LOCK) {
            CONSTANT_ENABLED.set(enabled && "constant".equals(selected));
            stopLocked(System.currentTimeMillis(), false);
        }
        ClientChat.withoutNoticesOnCurrentThread(
                () -> {
                    LowHealthFakeLag.setEnabled(client, enabled && "low_health".equals(selected));
                    RandomFakeLag.setEnabled(client, enabled && "random".equals(selected));
                });
    }

    private static String normalizedMode() {
        return MODE.serialized();
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static boolean constantMode() {
        return MODE.get() == Mode.CONSTANT;
    }

    public static boolean lowHealthMode() {
        return MODE.get() == Mode.LOW_HEALTH;
    }

    public static boolean randomMode() {
        return MODE.get() == Mode.RANDOM;
    }

    private static long randomDelayMs() {
        return RandomMath.betweenInclusive(DELAY_MIN.get(), DELAY_MAX.get());
    }

    private static void normalizeSettings() {
        int low = Math.min(DELAY_MIN.get(), DELAY_MAX.get());
        int high = Math.max(DELAY_MIN.get(), DELAY_MAX.get());
        DELAY_MIN.set(low);
        DELAY_MAX.set(high);
    }

    private enum Mode {
        CONSTANT,
        LOW_HEALTH,
        RANDOM
    }
}
