package com.blanoir.moons.client.module.impl.network;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.management.network.LagPacketPolicy;
import com.blanoir.moons.client.management.network.LagUtils;
import com.blanoir.moons.client.management.network.PacketDelayQueue;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.RandomMath;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;

/**
 * Bounded outgoing-packet delay while health is low. Outgoing
 * packets are retained in order for a small number of cycles, then silently
 * replayed through the normal Connection path.
 */
public final class LowHealthFakeLag {
    private static final double HEALTH_RATIO = 0.25D;
    private static final int MAX_CONFIG_DELAY_MS = 5000;
    private static final double MAX_CONFIG_RANGE = 16.0D;
    private static final int MAX_COOLDOWN_MS = 60_000;
    private static final int RECOIL_MS = 250;

    private static final Object LOCK = new Object();
    private static final PacketDelayQueue PACKETS = new PacketDelayQueue(512);

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("lowhealthfakelag.enabled")
                    .defaultValue(false)
                    .build();

    private static final IntSetting CYCLES_MIN =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.cycles.min")
                    .defaultValue(1)
                    .range(1, 5)
                    .build();

    private static final IntSetting CYCLES_MAX =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.cycles.max")
                    .defaultValue(5)
                    .range(1, 5)
                    .build();

    private static final IntSetting DELAY_MIN =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.delay.min")
                    .defaultValue(300)
                    .range(0, MAX_CONFIG_DELAY_MS)
                    .build();

    private static final IntSetting DELAY_MAX =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.delay.max")
                    .defaultValue(600)
                    .range(0, MAX_CONFIG_DELAY_MS)
                    .build();

    private static final DoubleSetting RANGE_MIN =
            new DoubleSetting.Builder()
                    .name("lowhealthfakelag.range.min")
                    .defaultValue(2.0D)
                    .range(0.0D, MAX_CONFIG_RANGE)
                    .build();

    private static final DoubleSetting RANGE_MAX =
            new DoubleSetting.Builder()
                    .name("lowhealthfakelag.range.max")
                    .defaultValue(5.0D)
                    .range(0.0D, MAX_CONFIG_RANGE)
                    .build();

    private static final IntSetting COOLDOWN_MS =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.cooldown.ms")
                    .defaultValue(10_000)
                    .range(0, MAX_COOLDOWN_MS)
                    .build();

    private static boolean queueing;
    private static boolean episodeStarted;
    private static int remainingCycles;
    private static long cycleStartedAtMs;
    private static long cycleDurationMs;
    private static long nextCycleAtMs;
    private static long cooldownUntilMs;

    private LowHealthFakeLag() {}

    public static void init() {
        normalizeSettings();
        EventBus.TICK.register("LowHealthFakeLag.tick", LowHealthFakeLag::tick);
    }

    private static void tick(TickEvent event) {
        Minecraft client = event.client();
        synchronized (LOCK) {
            PACKETS.observeClient(client);
            long now = LagUtils.nowMillis();
            if (!ENABLED.get()) {
                resetAllLocked();
                return;
            }
            if (!ready(client)) {
                interruptEpisodeLocked(now);
                return;
            }

            boolean belowThreshold = healthRatio(client) <= HEALTH_RATIO;
            if (!belowThreshold) {
                interruptEpisodeLocked(now);
                return;
            }

            if (!queueing && remainingCycles <= 0) {
                if (now < cooldownUntilMs || RandomFakeLag.isActive()) {
                    return;
                }
                remainingCycles = randomCycles();
                nextCycleAtMs = 0L;
            }

            if (queueing) {
                if (now - cycleStartedAtMs >= cycleDurationMs
                        || !hasEnemyInActivationRange(client)) {
                    finishCycleLocked(now);
                }
                return;
            }

            if (remainingCycles > 0 && now >= nextCycleAtMs && hasEnemyInActivationRange(client)) {
                startCycleLocked(now);
            }
        }
    }

    /** @return true when the caller must cancel the original send. */
    public static boolean handleOutgoing(Connection connection, Packet<?> packet) {
        if (LagUtils.isReplaying()) {
            return false;
        }

        synchronized (LOCK) {
            PACKETS.observe(connection, Minecraft.getInstance().level);
            if (!ENABLED.get() || !queueing) {
                return false;
            }

            Minecraft client = Minecraft.getInstance();
            long now = LagUtils.nowMillis();
            if (!ready(client)
                    || now - cycleStartedAtMs >= cycleDurationMs
                    || LagPacketPolicy.mustFlushBefore(packet)) {
                finishCycleLocked(now);
                return false;
            }

            if (!PACKETS.offer(packet)) {
                flushQueueLocked();
                return false;
            }
            return true;
        }
    }

    public static void handleIncoming(Packet<?> packet) {
        synchronized (LOCK) {
            if (LagPacketPolicy.mustDiscardOnIncoming(packet)) PACKETS.discard();
            if (!ENABLED.get() || (!queueing && PACKETS.isEmpty())) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            if (LagPacketPolicy.mustFlushOnIncoming(client, packet)) {
                finishCycleLocked(LagUtils.nowMillis());
            }
        }
    }

    public static boolean isReplaying() {
        return PACKETS.isReplaying();
    }

    public static void discardPending() {
        synchronized (LOCK) {
            PACKETS.discard();
            resetAllLocked();
        }
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return queueing || !PACKETS.isEmpty();
        }
    }

    private static void startCycleLocked(long now) {
        queueing = true;
        episodeStarted = true;
        cycleStartedAtMs = now;
        cycleDurationMs = randomDelayMs();
    }

    private static void finishCycleLocked(long now) {
        boolean completedCycle = queueing;
        flushQueueLocked();
        queueing = false;
        if (completedCycle && remainingCycles > 0) {
            remainingCycles--;
        }
        if (completedCycle && remainingCycles <= 0) {
            cooldownUntilMs = now + COOLDOWN_MS.get();
            episodeStarted = false;
        }
        nextCycleAtMs = now + RECOIL_MS;
    }

    private static void flushQueueLocked() {
        PACKETS.flushClient(Minecraft.getInstance());
    }

    private static boolean hasEnemyInActivationRange(Minecraft client) {
        for (Player target : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, target)) {
                continue;
            }
            double distance = client.player.distanceTo(target);
            if (distance >= RANGE_MIN.get() && distance <= RANGE_MAX.get()) {
                return true;
            }
        }
        return false;
    }

    private static boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client) && !client.player.isInWater();
    }

    private static double healthRatio(Minecraft client) {
        return client.player.getHealth() / Math.max(1.0D, client.player.getMaxHealth());
    }

    private static void interruptEpisodeLocked(long now) {
        flushQueueLocked();
        if (episodeStarted) {
            cooldownUntilMs = now + COOLDOWN_MS.get();
        }
        resetEpisodeLocked();
    }

    private static void resetAllLocked() {
        flushQueueLocked();
        resetEpisodeLocked();
        cooldownUntilMs = 0L;
    }

    private static void resetEpisodeLocked() {
        queueing = false;
        episodeStarted = false;
        remainingCycles = 0;
        cycleStartedAtMs = 0L;
        cycleDurationMs = 0L;
        nextCycleAtMs = 0L;
    }

    private static int randomCycles() {
        return RandomMath.betweenInclusive(CYCLES_MIN.get(), CYCLES_MAX.get());
    }

    private static long randomDelayMs() {
        return RandomMath.betweenInclusive(DELAY_MIN.get(), DELAY_MAX.get());
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "LowHealthFakeLag: "
                        + (ENABLED.get() ? "enabled" : "disabled")
                        + ", health: 25%, cycles: "
                        + CYCLES_MIN.get()
                        + "-"
                        + CYCLES_MAX.get()
                        + ", delay: "
                        + DELAY_MIN.get()
                        + "-"
                        + DELAY_MAX.get()
                        + "ms"
                        + ", range: "
                        + format(RANGE_MIN.get())
                        + "-"
                        + format(RANGE_MAX.get())
                        + ", cooldown: "
                        + format(COOLDOWN_MS.get() / 1000.0D)
                        + "s"
                        + ", recoil: 250ms, state: "
                        + (queueing ? "active" : "idle")
                        + "."
                        + " Usage: .moons lowhealthfakelag <enable|disable|count 1-5 [1-5]"
                        + "|delay 0-5000 [0-5000]|range 0-16 [0-16]|cooldown 0-60>.");
        return 1;
    }

    public static void setEnabled(Minecraft client, boolean value) {
        synchronized (LOCK) {
            ENABLED.set(value);
            resetAllLocked();
        }
        showStatus(client);
    }

    public static int setCycles(Minecraft client, int min, int max) {
        synchronized (LOCK) {
            CYCLES_MIN.set(Math.min(min, max));
            CYCLES_MAX.set(Math.max(min, max));
            resetAllLocked();
        }
        return showStatus(client);
    }

    public static int setDelay(Minecraft client, int min, int max) {
        synchronized (LOCK) {
            DELAY_MIN.set(Math.min(min, max));
            DELAY_MAX.set(Math.max(min, max));
            resetAllLocked();
        }
        return showStatus(client);
    }

    public static int setRange(Minecraft client, double min, double max) {
        synchronized (LOCK) {
            RANGE_MIN.set(Math.min(min, max));
            RANGE_MAX.set(Math.max(min, max));
            resetAllLocked();
        }
        return showStatus(client);
    }

    public static int setCooldownSeconds(Minecraft client, double seconds) {
        synchronized (LOCK) {
            COOLDOWN_MS.set((int) Math.round(seconds * 1000.0D));
            resetAllLocked();
        }
        return showStatus(client);
    }

    private static void normalizeSettings() {
        int lowCycles = Math.min(CYCLES_MIN.get(), CYCLES_MAX.get());
        int highCycles = Math.max(CYCLES_MIN.get(), CYCLES_MAX.get());
        CYCLES_MIN.set(lowCycles);
        CYCLES_MAX.set(highCycles);

        int lowDelay = Math.min(DELAY_MIN.get(), DELAY_MAX.get());
        int highDelay = Math.max(DELAY_MIN.get(), DELAY_MAX.get());
        DELAY_MIN.set(lowDelay);
        DELAY_MAX.set(highDelay);

        double lowRange = Math.min(RANGE_MIN.get(), RANGE_MAX.get());
        double highRange = Math.max(RANGE_MIN.get(), RANGE_MAX.get());
        RANGE_MIN.set(lowRange);
        RANGE_MAX.set(highRange);
    }

    private static String format(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }
}
