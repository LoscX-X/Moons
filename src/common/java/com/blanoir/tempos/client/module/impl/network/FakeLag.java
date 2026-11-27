package com.blanoir.tempos.client.module.impl.network;

import static com.blanoir.tempos.client.module.framework.SettingFactory.number;
import static com.blanoir.tempos.client.module.framework.SettingFactory.numeric;
import static com.blanoir.tempos.client.module.framework.SettingFactory.rangeDoubles;
import static com.blanoir.tempos.client.module.framework.SettingFactory.rangeInts;

import com.blanoir.tempos.client.access.PacketAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.tick.TickEvent;
import com.blanoir.tempos.client.manager.network.DelayedValueQueue;
import com.blanoir.tempos.client.manager.network.LagPacketPolicy;
import com.blanoir.tempos.client.manager.network.PacketDelayQueue;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RandomRange;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

/** FakeLag mode selection and constant-mode trigger; buffering is shared with Blink/lag. */
@ModuleInfo(category = ModuleCategory.NETWORK)
public final class FakeLag extends ClientFeature {
    public static final FakeLag INSTANCE = new FakeLag();

    private static final int MAX_DELAY_MS = 5000;
    private static final int MAX_RECOIL_MS = 2000;
    private static final int MAX_QUEUE_SIZE = 512;

    private final Object lock = new Object();
    private final PacketDelayQueue packets = new PacketDelayQueue(MAX_QUEUE_SIZE);

    private final BooleanSetting masterEnabled =
            new BooleanSetting.Builder().name("fakelag.enabled").defaultValue(false).build();

    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("fakelag.mode")
                    .defaultValue(Mode.CONSTANT)
                    .option(Mode.CONSTANT, "constant")
                    .option(Mode.LOW_HEALTH, "low_health")
                    .option(Mode.RANDOM, "random")
                    .build();

    private final BooleanSetting constantEnabled =
            new BooleanSetting.Builder()
                    .name("constantfakelag.enabled")
                    .defaultValue(false)
                    .build();

    private final IntSetting delayMin =
            new IntSetting.Builder()
                    .name("constantfakelag.delay.min")
                    .defaultValue(300)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private final IntSetting delayMax =
            new IntSetting.Builder()
                    .name("constantfakelag.delay.max")
                    .defaultValue(600)
                    .range(0, MAX_DELAY_MS)
                    .build();

    private final IntSetting recoilMs =
            new IntSetting.Builder()
                    .name("constantfakelag.recoil")
                    .defaultValue(250)
                    .range(0, MAX_RECOIL_MS)
                    .build();

    private boolean queueing;
    private long startedAtMs;
    private long durationMs;
    private long resumeAtMs;

    private final LowHealthFakeLag lowHealth = sub(new LowHealthFakeLag(this));

    private final RandomFakeLag random = sub(new RandomFakeLag(this));

    boolean lowHealthQueueing() {
        return lowHealth.isQueueing();
    }

    boolean randomQueueing() {
        return random.isQueueing();
    }

    private FakeLag() {
        super("fakelag", "FakeLag");
    }

    public void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "FakeLag.packetSend",
                event -> {
                    if (handleOutgoing(event.connection(), event.packet())
                            || lowHealth.handleOutgoing(event.connection(), event.packet())
                            || random.handleOutgoing(event.connection(), event.packet())) {
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
                                lowHealth.handleIncoming(packet);
                                random.handleIncoming(packet);
                            });
                });
    }

    @Override
    public void registerEvents() {
        super.registerEvents();
        normalizeSettings();
        applyModeSelection(Minecraft.getInstance());
        EventBus.TICK.register("FakeLag.tick", this::tick);
    }

    private void tick(TickEvent event) {
        Minecraft client = event.client();
        synchronized (this.lock) {
            this.packets.observeClient(client);
            long now = DelayedValueQueue.nowMillis();
            if (!constantActive() || !ready(client)) {
                stopLocked(now, false);
                return;
            }

            if (this.queueing) {
                if (now - this.startedAtMs >= this.durationMs) {
                    stopLocked(now, true);
                }
                return;
            }

            if (now >= this.resumeAtMs) {
                this.queueing = true;
                this.startedAtMs = now;
                this.durationMs = randomDelayMs();
            }
        }
    }

    /** @return true when the original send must be cancelled. */
    public boolean handleOutgoing(Connection connection, Packet<?> packet) {
        if (DelayedValueQueue.isReplaying()) {
            return false;
        }

        synchronized (this.lock) {
            this.packets.observe(connection, Minecraft.getInstance().level);
            if (!constantActive() || !this.queueing) {
                return false;
            }

            long now = DelayedValueQueue.nowMillis();
            if (!ready(Minecraft.getInstance())
                    || now - this.startedAtMs >= this.durationMs
                    || this.packets.isFull()
                    || LagPacketPolicy.mustFlushBefore(packet)) {
                stopLocked(now, true);
                return false;
            }

            return this.packets.offer(packet);
        }
    }

    public void handleIncoming(Packet<?> packet) {
        synchronized (this.lock) {
            if (LagPacketPolicy.mustDiscardOnIncoming(packet)) this.packets.discard();
            if (constantActive()
                    && (this.queueing || !this.packets.isEmpty())
                    && LagPacketPolicy.mustFlushOnIncoming(Minecraft.getInstance(), packet)) {
                stopLocked(DelayedValueQueue.nowMillis(), true);
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return this.masterEnabled.get();
    }

    public String modeName() {
        return normalizedMode();
    }

    public boolean isActive() {
        synchronized (this.lock) {
            return this.queueing || !this.packets.isEmpty();
        }
    }

    public boolean isReplaying() {
        return this.packets.isReplaying();
    }

    /** Unload discards pending packets without modifying persisted mode settings. */
    public void discardPending() {
        synchronized (this.lock) {
            this.packets.discard();
            stopLocked(DelayedValueQueue.nowMillis(), false);
        }
        lowHealth.discardPending();
        random.discardPending();
    }

    private void stopLocked(long now, boolean recoil) {
        flushLocked();
        this.queueing = false;
        this.startedAtMs = 0L;
        this.durationMs = 0L;
        this.resumeAtMs = recoil ? now + this.recoilMs.get() : now;
    }

    private void flushLocked() {
        this.packets.flushClient(Minecraft.getInstance());
    }

    private static boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client) && !client.player.isInWater();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.masterEnabled.set(value);
        applyModeSelection(client);
        return showStatus(client);
    }

    public int setMode(Minecraft client, String value) {
        this.mode.deserialize(value);
        applyModeSelection(client);
        return showStatus(client);
    }

    public int setDelay(Minecraft ignoredClient, int min, int max) {
        synchronized (this.lock) {
            this.delayMin.set(Math.min(min, max));
            this.delayMax.set(Math.max(min, max));
            stopLocked(DelayedValueQueue.nowMillis(), false);
        }
        return 1;
    }

    public int setRecoil(Minecraft ignoredClient, int value) {
        this.recoilMs.set(value);
        return 1;
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "FakeLag: "
                        + (this.masterEnabled.get() ? "enabled" : "disabled")
                        + ", mode "
                        + modeName()
                        + ", constant "
                        + this.delayMin.get()
                        + "-"
                        + this.delayMax.get()
                        + "ms, recoil "
                        + this.recoilMs.get()
                        + "ms.");
        return 1;
    }

    private boolean constantActive() {
        return this.masterEnabled.get() && this.mode.get() == Mode.CONSTANT;
    }

    private void applyModeSelection(Minecraft client) {
        boolean enabled = this.masterEnabled.get();
        String selected = this.mode.serialized();
        synchronized (this.lock) {
            this.constantEnabled.set(enabled && "constant".equals(selected));
            stopLocked(DelayedValueQueue.nowMillis(), false);
        }
        ClientChat.withoutNoticesOnCurrentThread(
                () -> {
                    lowHealth.setActive(client, enabled && "low_health".equals(selected));
                    random.setActive(client, enabled && "random".equals(selected));
                });
    }

    private String normalizedMode() {
        return this.mode.serialized();
    }

    public boolean constantMode() {
        return this.mode.get() == Mode.CONSTANT;
    }

    public boolean lowHealthMode() {
        return this.mode.get() == Mode.LOW_HEALTH;
    }

    public boolean randomMode() {
        return this.mode.get() == Mode.RANDOM;
    }

    private long randomDelayMs() {
        return RandomRange.betweenInclusive(this.delayMin.get(), this.delayMax.get());
    }

    private void normalizeSettings() {
        int low = Math.min(this.delayMin.get(), this.delayMax.get());
        int high = Math.max(this.delayMin.get(), this.delayMax.get());
        this.delayMin.set(low);
        this.delayMax.set(high);
    }

    private enum Mode {
        CONSTANT,
        LOW_HEALTH,
        RANDOM
    }

    @Override
    protected String statusTag() {
        return this.modeName();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored("mode", "Mode", this::setMode),
            delayMin.describeStoredRange("delay", "Delay (ms)", delayMax, 10, this::setDelay)
                    .visibleWhen(this::constantMode),
            recoilMs.describeStored("recoil", "Recoil (ms)", 10, this::setRecoil)
                    .visibleWhen(this::constantMode),
            rangeInts(
                            "low_health_cycles",
                            "Low health cycles",
                            "lowhealthfakelag.cycles.min",
                            "lowhealthfakelag.cycles.max",
                            1,
                            5,
                            1,
                            20,
                            1,
                            lowHealth::setCycles)
                    .visibleWhen(this::lowHealthMode),
            rangeInts(
                            "low_health_delay",
                            "Low health delay (ms)",
                            "lowhealthfakelag.delay.min",
                            "lowhealthfakelag.delay.max",
                            300,
                            600,
                            0,
                            5000,
                            10,
                            lowHealth::setDelay)
                    .visibleWhen(this::lowHealthMode),
            rangeDoubles(
                            "low_health_range",
                            "Low health enemy range",
                            "lowhealthfakelag.range.min",
                            "lowhealthfakelag.range.max",
                            2,
                            5,
                            0,
                            16,
                            .1,
                            lowHealth::setRange)
                    .visibleWhen(this::lowHealthMode),
            numeric(
                            "low_health_cooldown",
                            "Low health cooldown (s)",
                            "number",
                            () -> Settings.getInt("lowhealthfakelag.cooldown.ms", 10000) / 1000.0,
                            0,
                            60,
                            .5,
                            lowHealth::setCooldownSeconds)
                    .withDefault(10.0)
                    .visibleWhen(this::lowHealthMode),
            number(
                            "random_chance",
                            "Random chance",
                            "randomfakelag.chance",
                            .35,
                            0,
                            1,
                            .01,
                            random::setChance)
                    .visibleWhen(this::randomMode),
            rangeInts(
                            "random_delay",
                            "Random delay (ms)",
                            "randomfakelag.delay.min",
                            "randomfakelag.delay.max",
                            180,
                            420,
                            0,
                            5000,
                            10,
                            random::setDelay)
                    .visibleWhen(this::randomMode),
            rangeDoubles(
                            "random_range",
                            "Random range",
                            "randomfakelag.range.min",
                            "randomfakelag.range.max",
                            2,
                            5,
                            0,
                            16,
                            .1,
                            random::setRange)
                    .visibleWhen(this::randomMode),
            numeric(
                            "random_cooldown",
                            "Random cooldown (s)",
                            "number",
                            () -> Settings.getInt("randomfakelag.cooldown.ms", 5000) / 1000.0,
                            0,
                            60,
                            .5,
                            random::setCooldownSeconds)
                    .withDefault(5.0)
                    .visibleWhen(this::randomMode)
        };
    }
}
