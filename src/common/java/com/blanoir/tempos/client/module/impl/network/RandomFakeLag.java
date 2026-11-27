package com.blanoir.tempos.client.module.impl.network;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.tick.TickEvent;
import com.blanoir.tempos.client.manager.network.DelayedValueQueue;
import com.blanoir.tempos.client.manager.network.LagPacketPolicy;
import com.blanoir.tempos.client.manager.network.PacketDelayQueue;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.SubFeature;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RandomChance;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * One-shot FakeLag rolled at the beginning of a genuine head-on sprint.
 * The probability is sampled once per encounter, never once per tick.
 */
final class RandomFakeLag extends SubFeature.Switchable<FakeLag> {
    private static final int MAX_DELAY_MS = 5000;
    private static final int MAX_COOLDOWN_MS = 60_000;
    private static final double MAX_RANGE = 16.0D;
    private static final double MIN_FACING_DOT = 0.55D;
    private static final int ENCOUNTER_RELEASE_TICKS = 3;

    private final Object lock = new Object();
    private final PacketDelayQueue packets = new PacketDelayQueue(512);

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("randomfakelag.enabled").defaultValue(false).build();

    private final DoubleSetting chance =
            new DoubleSetting.Builder()
                    .name("randomfakelag.chance")
                    .defaultValue(0.35D)
                    .range(0.0D, 1.0D)
                    .build();

    private final IntSetting delayMin =
            new IntSetting.Builder()
                    .name("randomfakelag.delay.min")
                    .defaultValue(180)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private final IntSetting delayMax =
            new IntSetting.Builder()
                    .name("randomfakelag.delay.max")
                    .defaultValue(420)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private final DoubleSetting rangeMin =
            new DoubleSetting.Builder()
                    .name("randomfakelag.range.min")
                    .defaultValue(2.0D)
                    .range(0.0D, MAX_RANGE)
                    .build();

    private final DoubleSetting rangeMax =
            new DoubleSetting.Builder()
                    .name("randomfakelag.range.max")
                    .defaultValue(5.0D)
                    .range(0.0D, MAX_RANGE)
                    .build();

    private final IntSetting cooldownMs =
            new IntSetting.Builder()
                    .name("randomfakelag.cooldown.ms")
                    .defaultValue(5000)
                    .range(0, MAX_COOLDOWN_MS)
                    .build();

    private boolean encounterActive;
    private int encounterMissTicks;
    private boolean queueing;
    private int targetId = -1;
    private long startedAtMs;
    private long durationMs;
    private long cooldownUntilMs;

    RandomFakeLag(FakeLag parent) {
        super(parent);
    }

    @Override
    public void registerEvents() {
        normalizeSettings();
        EventBus.TICK.register("RandomFakeLag.tick", this::tick);
    }

    private void tick(TickEvent event) {
        Minecraft client = event.client();
        synchronized (lock) {
            packets.observeClient(client);
            if (!enabled.get()) {
                resetAllLocked();
                return;
            }

            long now = DelayedValueQueue.nowMillis();
            if (!ClientReady.aliveGameplay(client)) {
                finishLocked(now, false);
                encounterActive = false;
                encounterMissTicks = 0;
                return;
            }

            if (parent().lowHealthQueueing()) {
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
                if (now >= cooldownUntilMs && RandomChance.chance(chance.get())) {
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
    public boolean handleOutgoing(Connection connection, Packet<?> packet) {
        if (DelayedValueQueue.isReplaying()) {
            return false;
        }

        synchronized (lock) {
            packets.observe(connection, Minecraft.getInstance().level);
            if (!enabled.get() || !queueing) {
                return false;
            }

            Minecraft client = Minecraft.getInstance();
            long now = DelayedValueQueue.nowMillis();
            if (!ClientReady.aliveGameplay(client)
                    || now - startedAtMs >= durationMs
                    || LagPacketPolicy.mustFlushBefore(packet)) {
                finishLocked(now, true);
                return false;
            }

            if (!packets.offer(packet)) {
                flushQueueLocked();
                return false;
            }
            return true;
        }
    }

    public void handleIncoming(Packet<?> packet) {
        synchronized (lock) {
            if (LagPacketPolicy.mustDiscardOnIncoming(packet)) packets.discard();
            if (!enabled.get() || (!queueing && packets.isEmpty())) {
                return;
            }
            if (LagPacketPolicy.mustFlushOnIncoming(Minecraft.getInstance(), packet)) {
                finishLocked(DelayedValueQueue.nowMillis(), true);
            }
        }
    }

    boolean isQueueing() {
        synchronized (lock) {
            return queueing || !packets.isEmpty();
        }
    }

    public boolean isReplaying() {
        return packets.isReplaying();
    }

    public void discardPending() {
        synchronized (lock) {
            packets.discard();
            resetAllLocked();
        }
    }

    private void startLocked(Player target, long now) {
        queueing = true;
        targetId = target.getId();
        startedAtMs = now;
        durationMs = randomDelayMs();
    }

    private void finishLocked(long now, boolean startCooldown) {
        boolean wasActive = queueing || !packets.isEmpty();
        flushQueueLocked();
        queueing = false;
        targetId = -1;
        startedAtMs = 0L;
        durationMs = 0L;
        if (wasActive && startCooldown) {
            cooldownUntilMs = now + cooldownMs.get();
        }
    }

    private void resetAllLocked() {
        finishLocked(DelayedValueQueue.nowMillis(), false);
        encounterActive = false;
        encounterMissTicks = 0;
        cooldownUntilMs = 0L;
    }

    private void flushQueueLocked() {
        packets.flushClient(Minecraft.getInstance());
    }

    private Player findHeadOnTarget(Minecraft client) {
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

    private boolean isHeadOnSprint(Minecraft client, Player target) {
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
        if (distance < rangeMin.get() || distance > rangeMax.get()) {
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

    private Vec3 horizontal(Vec3 value) {
        return new Vec3(value.x, 0.0D, value.z);
    }

    private Vec3 horizontalUnit(Vec3 value) {
        Vec3 horizontal = horizontal(value);
        double length = horizontal.length();
        return length < 1.0E-5D ? null : horizontal.scale(1.0D / length);
    }

    private long randomDelayMs() {
        return RandomRange.betweenInclusive(delayMin.get(), delayMax.get());
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "RandomFakeLag: "
                        + (enabled.get() ? "enabled" : "disabled")
                        + ", chance: "
                        + NumberText.compactDouble(chance.get())
                        + ", delay: "
                        + delayMin.get()
                        + "-"
                        + delayMax.get()
                        + "ms"
                        + ", range: "
                        + NumberText.compactDouble(rangeMin.get())
                        + "-"
                        + NumberText.compactDouble(rangeMax.get())
                        + ", cooldown: "
                        + NumberText.compactDouble(cooldownMs.get() / 1000.0D)
                        + "s"
                        + ", trigger: sprinting toward a mutually-facing player"
                        + ", state: "
                        + (queueing ? "active" : "idle")
                        + "."
                        + " Usage: .tempos randomfakelag <enable|disable|chance 0-1"
                        + "|delay 0-5000 [0-5000]|range 0-16 [0-16]|cooldown 0-60>.");
        return 1;
    }

    @Override
    public boolean isActive() {
        return enabled.get();
    }

    @Override
    public int setActive(Minecraft client, boolean value) {
        synchronized (lock) {
            enabled.set(value);
            resetAllLocked();
        }
        return showStatus(client);
    }

    public int setChance(Minecraft client, double value) {
        synchronized (lock) {
            chance.set(value);
            resetAllLocked();
        }
        return showStatus(client);
    }

    public int setDelay(Minecraft client, int min, int max) {
        synchronized (lock) {
            delayMin.set(Math.min(min, max));
            delayMax.set(Math.max(min, max));
            resetAllLocked();
        }
        return showStatus(client);
    }

    public int setRange(Minecraft client, double min, double max) {
        synchronized (lock) {
            rangeMin.set(Math.min(min, max));
            rangeMax.set(Math.max(min, max));
            resetAllLocked();
        }
        return showStatus(client);
    }

    public int setCooldownSeconds(Minecraft client, double seconds) {
        synchronized (lock) {
            cooldownMs.set((int) Math.round(seconds * 1000.0D));
            resetAllLocked();
        }
        return showStatus(client);
    }

    private void normalizeSettings() {
        int lowDelay = Math.min(delayMin.get(), delayMax.get());
        int highDelay = Math.max(delayMin.get(), delayMax.get());
        delayMin.set(lowDelay);
        delayMax.set(highDelay);

        double lowRange = Math.min(rangeMin.get(), rangeMax.get());
        double highRange = Math.max(rangeMin.get(), rangeMax.get());
        rangeMin.set(lowRange);
        rangeMax.set(highRange);
    }
}
