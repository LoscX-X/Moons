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
import net.minecraft.world.phys.Vec3;

/**
 * One-shot FakeLag rolled at the beginning of a genuine head-on sprint.
 * The probability is sampled once per encounter, never once per tick.
 */
public final class RandomFakeLag {
    private static final int MAX_DELAY_MS = 5000;
    private static final int MAX_COOLDOWN_MS = 60_000;
    private static final double MAX_RANGE = 16.0D;
    private static final double MIN_FACING_DOT = 0.55D;
    private static final int ENCOUNTER_RELEASE_TICKS = 3;

    private static final Object LOCK = new Object();
    private static final PacketDelayQueue PACKETS = new PacketDelayQueue(512);

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("randomfakelag.enabled").defaultValue(false).build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("randomfakelag.chance")
                    .defaultValue(0.35D)
                    .range(0.0D, 1.0D)
                    .build();

    private static final IntSetting DELAY_MIN =
            new IntSetting.Builder()
                    .name("randomfakelag.delay.min")
                    .defaultValue(180)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private static final IntSetting DELAY_MAX =
            new IntSetting.Builder()
                    .name("randomfakelag.delay.max")
                    .defaultValue(420)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private static final DoubleSetting RANGE_MIN =
            new DoubleSetting.Builder()
                    .name("randomfakelag.range.min")
                    .defaultValue(2.0D)
                    .range(0.0D, MAX_RANGE)
                    .build();

    private static final DoubleSetting RANGE_MAX =
            new DoubleSetting.Builder()
                    .name("randomfakelag.range.max")
                    .defaultValue(5.0D)
                    .range(0.0D, MAX_RANGE)
                    .build();

    private static final IntSetting COOLDOWN_MS =
            new IntSetting.Builder()
                    .name("randomfakelag.cooldown.ms")
                    .defaultValue(5000)
                    .range(0, MAX_COOLDOWN_MS)
                    .build();

    private static boolean encounterActive;
    private static int encounterMissTicks;
    private static boolean queueing;
    private static int targetId = -1;
    private static long startedAtMs;
    private static long durationMs;
    private static long cooldownUntilMs;

    private RandomFakeLag() {}

    public static void init() {
        normalizeSettings();
        EventBus.TICK.register("RandomFakeLag.tick", RandomFakeLag::tick);
    }

    private static void tick(TickEvent event) {
        Minecraft client = event.client();
        synchronized (LOCK) {
            PACKETS.observeClient(client);
            if (!ENABLED.get()) {
                resetAllLocked();
                return;
            }

            long now = LagUtils.nowMillis();
            if (!ready(client)) {
                finishLocked(now, false);
                encounterActive = false;
                encounterMissTicks = 0;
                return;
            }

            if (LowHealthFakeLag.isActive()) {
                finishLocked(now, false);
                encounterActive = findHeadOnTarget(client) != null;
                encounterMissTicks = 0;
                return;
            }

            if (queueing) {
                Player target =
                        client.level.getEntity(targetId) instanceof Player player ? player : null;
                if (now - startedAtMs >= durationMs || !isHeadOnSprint(client, target)) {
                    finishLocked(now, true);
                }
                return;
            }

            Player target = findHeadOnTarget(client);
            boolean headOnNow = target != null;
            if (headOnNow && !encounterActive) {
                encounterActive = true;
                encounterMissTicks = 0;
                if (now >= cooldownUntilMs && RandomMath.chance(CHANCE.get())) {
                    startLocked(target, now);
                }
            } else if (headOnNow) {
                encounterMissTicks = 0;
            } else if (++encounterMissTicks >= ENCOUNTER_RELEASE_TICKS) {
                encounterActive = false;
                encounterMissTicks = 0;
            }
        }
    }

    /** @return true when the original send must be cancelled. */
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
                    || now - startedAtMs >= durationMs
                    || LagPacketPolicy.mustFlushBefore(packet)) {
                finishLocked(now, true);
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
            if (LagPacketPolicy.mustFlushOnIncoming(Minecraft.getInstance(), packet)) {
                finishLocked(LagUtils.nowMillis(), true);
            }
        }
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return queueing || !PACKETS.isEmpty();
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

    private static void startLocked(Player target, long now) {
        queueing = true;
        targetId = target.getId();
        startedAtMs = now;
        durationMs = randomDelayMs();
    }

    private static void finishLocked(long now, boolean startCooldown) {
        boolean wasActive = queueing || !PACKETS.isEmpty();
        flushQueueLocked();
        queueing = false;
        targetId = -1;
        startedAtMs = 0L;
        durationMs = 0L;
        if (wasActive && startCooldown) {
            cooldownUntilMs = now + COOLDOWN_MS.get();
        }
    }

    private static void resetAllLocked() {
        finishLocked(LagUtils.nowMillis(), false);
        encounterActive = false;
        encounterMissTicks = 0;
        cooldownUntilMs = 0L;
    }

    private static void flushQueueLocked() {
        PACKETS.flushClient(Minecraft.getInstance());
    }

    private static Player findHeadOnTarget(Minecraft client) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player target : client.level.players()) {
            if (!isHeadOnSprint(client, target)) {
                continue;
            }
            double distance = client.player.distanceTo(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        return best;
    }

    private static boolean isHeadOnSprint(Minecraft client, Player target) {
        if (!Targeting.isValidTargetPlayer(client, target)
                || !client.player.isSprinting()
                || !client.player.hasLineOfSight(target)) {
            return false;
        }

        Vec3 offset = target.position().subtract(client.player.position());
        if (Math.abs(offset.y) > 1.8D) {
            return false;
        }
        Vec3 direction = horizontalUnit(offset);
        if (direction == null) {
            return false;
        }

        double distance = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (distance < RANGE_MIN.get() || distance > RANGE_MAX.get()) {
            return false;
        }

        Vec3 playerLook = horizontalUnit(client.player.getLookAngle());
        Vec3 targetLook = horizontalUnit(target.getLookAngle());
        if (playerLook == null
                || targetLook == null
                || playerLook.dot(direction) < MIN_FACING_DOT
                || targetLook.dot(direction.scale(-1.0D)) < MIN_FACING_DOT) {
            return false;
        }

        return true;
    }

    private static Vec3 horizontal(Vec3 value) {
        return new Vec3(value.x, 0.0D, value.z);
    }

    private static Vec3 horizontalUnit(Vec3 value) {
        Vec3 horizontal = horizontal(value);
        double length = horizontal.length();
        return length < 1.0E-5D ? null : horizontal.scale(1.0D / length);
    }

    private static boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client);
    }

    private static long randomDelayMs() {
        return RandomMath.betweenInclusive(DELAY_MIN.get(), DELAY_MAX.get());
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "RandomFakeLag: "
                        + (ENABLED.get() ? "enabled" : "disabled")
                        + ", chance: "
                        + format(CHANCE.get())
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
                        + ", trigger: sprinting toward a mutually-facing player"
                        + ", state: "
                        + (queueing ? "active" : "idle")
                        + "."
                        + " Usage: .moons randomfakelag <enable|disable|chance 0-1"
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

    public static int setChance(Minecraft client, double value) {
        synchronized (LOCK) {
            CHANCE.set(value);
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
