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
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;

/**
 * Bounded outgoing-packet delay while health is low. Outgoing
 * packets are retained in order for a small number of cycles, then silently
 * replayed through the normal Connection path.
 */
final class LowHealthFakeLag extends SubFeature.Switchable<FakeLag> {
    private static final double HEALTH_RATIO = 0.25D;
    private static final int MAX_CONFIG_DELAY_MS = 5000;
    private static final double MAX_CONFIG_RANGE = 16.0D;
    private static final int MAX_COOLDOWN_MS = 60_000;
    private static final int RECOIL_MS = 250;

    private final Object lock = new Object();
    private final PacketDelayQueue packets = new PacketDelayQueue(512);

    private final BooleanSetting enabled =
            new BooleanSetting.Builder()
                    .name("lowhealthfakelag.enabled")
                    .defaultValue(false)
                    .build();

    private final IntSetting cyclesMin =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.cycles.min")
                    .defaultValue(1)
                    .range(1, 5)
                    .build();

    private final IntSetting cyclesMax =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.cycles.max")
                    .defaultValue(5)
                    .range(1, 5)
                    .build();

    private final IntSetting delayMin =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.delay.min")
                    .defaultValue(300)
                    .range(0, MAX_CONFIG_DELAY_MS)
                    .build();

    private final IntSetting delayMax =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.delay.max")
                    .defaultValue(600)
                    .range(0, MAX_CONFIG_DELAY_MS)
                    .build();

    private final DoubleSetting rangeMin =
            new DoubleSetting.Builder()
                    .name("lowhealthfakelag.range.min")
                    .defaultValue(2.0D)
                    .range(0.0D, MAX_CONFIG_RANGE)
                    .build();

    private final DoubleSetting rangeMax =
            new DoubleSetting.Builder()
                    .name("lowhealthfakelag.range.max")
                    .defaultValue(5.0D)
                    .range(0.0D, MAX_CONFIG_RANGE)
                    .build();

    private final IntSetting cooldownMs =
            new IntSetting.Builder()
                    .name("lowhealthfakelag.cooldown.ms")
                    .defaultValue(10_000)
                    .range(0, MAX_COOLDOWN_MS)
                    .build();

    private boolean queueing;
    private boolean episodeStarted;
    private int remainingCycles;
    private long cycleStartedAtMs;
    private long cycleDurationMs;
    private long nextCycleAtMs;
    private long cooldownUntilMs;

    LowHealthFakeLag(FakeLag parent) {
        super(parent);
    }

    @Override
    public void registerEvents() {
        normalizeSettings();
        EventBus.TICK.register("LowHealthFakeLag.tick", this::tick);
    }

    private void tick(TickEvent event) {
        Minecraft client = event.client();
        synchronized (lock) {
            packets.observeClient(client);
            long now = DelayedValueQueue.nowMillis();
            if (!enabled.get()) {
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
                if (now < cooldownUntilMs || parent().randomQueueing()) {
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
            if (!ready(client)
                    || now - cycleStartedAtMs >= cycleDurationMs
                    || LagPacketPolicy.mustFlushBefore(packet)) {
                finishCycleLocked(now);
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

            Minecraft client = Minecraft.getInstance();
            if (LagPacketPolicy.mustFlushOnIncoming(client, packet)) {
                finishCycleLocked(DelayedValueQueue.nowMillis());
            }
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

    boolean isQueueing() {
        synchronized (lock) {
            return queueing || !packets.isEmpty();
        }
    }

    private void startCycleLocked(long now) {
        queueing = true;
        episodeStarted = true;
        cycleStartedAtMs = now;
        cycleDurationMs = randomDelayMs();
    }

    private void finishCycleLocked(long now) {
        boolean completedCycle = queueing;
        flushQueueLocked();
        queueing = false;
        if (completedCycle && remainingCycles > 0) {
            remainingCycles--;
        }
        if (completedCycle && remainingCycles <= 0) {
            cooldownUntilMs = now + cooldownMs.get();
            episodeStarted = false;
        }
        nextCycleAtMs = now + RECOIL_MS;
    }

    private void flushQueueLocked() {
        packets.flushClient(Minecraft.getInstance());
    }

    private boolean hasEnemyInActivationRange(Minecraft client) {
        for (Player target : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, target)) {
                continue;
            }
            double distance = client.player.distanceTo(target);
            if (distance >= rangeMin.get() && distance <= rangeMax.get()) {
                return true;
            }
        }
        return false;
    }

    private boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client) && !client.player.isInWater();
    }

    private double healthRatio(Minecraft client) {
        return client.player.getHealth() / Math.max(1.0D, client.player.getMaxHealth());
    }

    private void interruptEpisodeLocked(long now) {
        flushQueueLocked();
        if (episodeStarted) {
            cooldownUntilMs = now + cooldownMs.get();
        }
        resetEpisodeLocked();
    }

    private void resetAllLocked() {
        flushQueueLocked();
        resetEpisodeLocked();
        cooldownUntilMs = 0L;
    }

    private void resetEpisodeLocked() {
        queueing = false;
        episodeStarted = false;
        remainingCycles = 0;
        cycleStartedAtMs = 0L;
        cycleDurationMs = 0L;
        nextCycleAtMs = 0L;
    }

    private int randomCycles() {
        return RandomRange.betweenInclusive(cyclesMin.get(), cyclesMax.get());
    }

    private long randomDelayMs() {
        return RandomRange.betweenInclusive(delayMin.get(), delayMax.get());
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "LowHealthFakeLag: "
                        + (enabled.get() ? "enabled" : "disabled")
                        + ", health: 25%, cycles: "
                        + cyclesMin.get()
                        + "-"
                        + cyclesMax.get()
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
                        + ", recoil: 250ms, state: "
                        + (queueing ? "active" : "idle")
                        + "."
                        + " Usage: .tempos lowhealthfakelag <enable|disable|count 1-5 [1-5]"
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

    public int setCycles(Minecraft client, int min, int max) {
        synchronized (lock) {
            cyclesMin.set(Math.min(min, max));
            cyclesMax.set(Math.max(min, max));
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
        int lowCycles = Math.min(cyclesMin.get(), cyclesMax.get());
        int highCycles = Math.max(cyclesMin.get(), cyclesMax.get());
        cyclesMin.set(lowCycles);
        cyclesMax.set(highCycles);

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
