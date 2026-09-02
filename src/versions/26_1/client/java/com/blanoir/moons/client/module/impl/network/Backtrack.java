package com.blanoir.moons.client.module.impl.network;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.module.impl.network.backtrack.BacktrackRenderer;
import com.blanoir.moons.client.module.impl.network.backtrack.BacktrackWireframePlayer;
import com.blanoir.moons.client.management.network.TrackedEntityPosition;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LiquidBounce-style Backtrack.
 *
 * <p>Direct port of
 * {@code net.ccbluex.liquidbounce.features.module.modules.combat.backtrack.ModuleBacktrack},
 * including the incoming packet blink queue, target modes, delay/chance/hurt-time
 * handling and the Box/Model/Wireframe/None ESP modes. Moons has no Velocity module,
 * so the LiquidBounce {@code VelocityReduce} coordination checks are constant no-ops.
 */
public final class Backtrack {
    private static final String RESTORE_REAL_LOCATION_ESP_MIGRATION =
            "backtrack.migration.restoreRealLocationWireframe";
    private static final double MIN_SUPPORTED_RANGE = 0.0D;
    private static final double MAX_SUPPORTED_RANGE = 10.0D;

    private static final int MIN_SUPPORTED_DELAY = 0;
    private static final int MAX_SUPPORTED_DELAY = 1000;

    private static final int MIN_SUPPORTED_NEXT_DELAY = 0;
    private static final int MAX_SUPPORTED_NEXT_DELAY = 2000;

    private static final int MAX_SUPPORTED_TRACKING_BUFFER = 2000;
    private static final int MAX_SUPPORTED_LAST_ATTACK_TIME = 5000;
    private static final int INTENT_APPROACH_RELEASE_TICKS = 3;
    private static final double INTENT_APPROACH_MIN_DISTANCE_SQUARED = 1.0E-4D;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("backtrack.enabled")
                    .defaultValue(false)
                    .build();

    private static final DoubleSetting RANGE_MIN =
            new DoubleSetting.Builder()
                    .name("backtrack.range.min")
                    .defaultValue(1.0D)
                    .build();

    private static final DoubleSetting RANGE_MAX =
            new DoubleSetting.Builder()
                    .name("backtrack.range.max")
                    .defaultValue(3.0D)
                    .build();

    private static final IntSetting DELAY_MIN =
            new IntSetting.Builder()
                    .name("backtrack.delay.min")
                    .defaultValue(100)
                    .build();

    private static final IntSetting DELAY_MAX =
            new IntSetting.Builder()
                    .name("backtrack.delay.max")
                    .defaultValue(150)
                    .build();

    private static final IntSetting NEXT_BACKTRACK_DELAY_MIN =
            new IntSetting.Builder()
                    .name("backtrack.nextBacktrackDelay.min")
                    .defaultValue(0)
                    .build();

    private static final IntSetting NEXT_BACKTRACK_DELAY_MAX =
            new IntSetting.Builder()
                    .name("backtrack.nextBacktrackDelay.max")
                    .defaultValue(10)
                    .build();

    private static final IntSetting TRACKING_BUFFER =
            new IntSetting.Builder()
                    .name("backtrack.trackingBuffer")
                    .defaultValue(500)
                    .build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("backtrack.chance")
                    .defaultValue(50.0D)
                    .build();

    private static volatile boolean chancePassed = rollChance();

    private static final BooleanSetting PAUSE_ON_HURT_TIME =
            new BooleanSetting.Builder()
                    .name("backtrack.pauseOnHurtTime.enabled")
                    .defaultValue(false)
                    .build();

    private static final IntSetting PAUSE_ON_HURT_TIME_VALUE =
            new IntSetting.Builder()
                    .name("backtrack.pauseOnHurtTime.hurtTime")
                    .defaultValue(3)
                    .build();

    private static final ModeSetting<TargetMode> TARGET_MODE =
            new ModeSetting.Builder<TargetMode>()
                    .name("backtrack.targetMode")
                    .defaultValue(TargetMode.ATTACK)
                    .option(TargetMode.ATTACK, "attack").option(TargetMode.RANGE, "range")
                    .option(TargetMode.INTENT, "intent")
                    .build();

    private static final IntSetting LAST_ATTACK_TIME =
            new IntSetting.Builder()
                    .name("backtrack.lastAttackTimeToWork")
                    .defaultValue(1000)
                    .build();

    private static final IntSetting ARM_WINDOW_TICKS =
            new IntSetting.Builder()
                    .name("backtrack.armWindowTicks")
                    .defaultValue(2)
                    .build();

    private static final IntSetting MAX_PACKETS_PER_TICK =
            new IntSetting.Builder()
                    .name("backtrack.maxPacketsPerTick")
                    .defaultValue(2)
                    .build();

    private static final IntSetting MAX_QUEUE_SIZE =
            new IntSetting.Builder()
                    .name("backtrack.maxQueueSize")
                    .defaultValue(256)
                    .build();

    private static final IntSetting JITTER_MS =
            new IntSetting.Builder()
                    .name("backtrack.jitter")
                    .defaultValue(15)
                    .build();

    private static final DoubleSetting SPEED_FACTOR =
            new DoubleSetting.Builder()
                    .name("backtrack.speedFactor")
                    .defaultValue(8.0D)
                    .build();

    private static final DoubleSetting PING_RATIO =
            new DoubleSetting.Builder()
                    .name("backtrack.pingRatio")
                    .defaultValue(0.0D)
                    .build();

    private static final BooleanSetting ACTION_BAR =
            new BooleanSetting.Builder()
                    .name("backtrack.actionbar")
                    .defaultValue(false)
                    .build();

    private static final ModeSetting<EspMode> ESP_MODE =
            new ModeSetting.Builder<EspMode>()
                    .name("backtrack.esp")
                    .defaultValue(EspMode.WIREFRAME)
                    .option(EspMode.BOX, "box").option(EspMode.MODEL, "model")
                    .option(EspMode.WIREFRAME, "wireframe", "wire_frame")
                    .option(EspMode.NONE, "none", "off")
                    .build();

    private static final StringSetting BOX_COLOR =
            new StringSetting.Builder()
                    .name("backtrack.esp.box.color")
                    .defaultValue("ffffffff")
                    .build();

    private static final StringSetting BOX_OUTLINE_COLOR =
            new StringSetting.Builder()
                    .name("backtrack.esp.box.outlineColor")
                    .defaultValue("ff000000")
                    .build();

    private static final StringSetting MODEL_OUTLINE_COLOR =
            new StringSetting.Builder()
                    .name("backtrack.esp.model.outlineColor")
                    .defaultValue("ffffffff")
                    .build();

    private static final IntSetting MODEL_LIGHT_PERCENT =
            new IntSetting.Builder()
                    .name("backtrack.esp.model.lightPercent")
                    .defaultValue(100)
                    .build();

    private static final StringSetting WIREFRAME_COLOR =
            new StringSetting.Builder()
                    .name("backtrack.esp.wireframe.color")
                    .defaultValue("ffffffff")
                    .build();

    private static final StringSetting WIREFRAME_OUTLINE_COLOR =
            new StringSetting.Builder()
                    .name("backtrack.esp.wireframe.outlineColor")
                    .defaultValue("ff000000")
                    .build();

    private static final ConcurrentLinkedQueue<PacketSnapshot> PACKET_QUEUE =
            new ConcurrentLinkedQueue<>();
    private static final Object PACKET_QUEUE_LOCK = new Object();
    private static long lastQueuedReleaseAt = 0L;
    private static final TrackedEntityPosition POSITION = new TrackedEntityPosition();
    private static final BacktrackWireframePlayer WIREFRAME = new BacktrackWireframePlayer();

    private static long chronometerLastUpdate = 0L;
    private static long trackingBufferChronometerLastUpdate = 0L;
    private static long attackChronometerLastUpdate = 0L;

    private static Vec3 renderPosition = null;

    private static volatile boolean shouldPause = false;
    private static volatile Entity target = null;
    private static volatile int sampledBaseDelay =
            randomInt(DELAY_MIN.get(), DELAY_MAX.get());
    private static volatile int currentDelay = sampledBaseDelay;

    private static final ArrayDeque<PositionSample> TARGET_POSITION_SAMPLES = new ArrayDeque<>();
    private static final int SPEED_SAMPLE_TICKS = 5;

    private static volatile boolean armed = false;
    private static int gameTick = 0;
    private static int attackCooldownTicks = 10;
    private static int armAtTick = -1;
    private static int disarmAtTick = -1;
    private static int intentApproachReleaseUntilTick = -1;
    private static double lastHitDistance = 0.0D;
    private static double lastRealDistance = 0.0D;

    private Backtrack() {
    }

    public static void init() {
        restoreRealLocationEspDefault();
        EventBus.TICK.register("Backtrack.tick", event -> {
            Minecraft client = event.client();
            tick(client);
        });
        EventBus.FRAME.register("Backtrack.frame", Backtrack::frame);

        EventBus.WORLD_RENDER.register("Backtrack.worldRender", Backtrack::renderEsp);
    }

    private static void restoreRealLocationEspDefault() {
        if (Settings.getBoolean(RESTORE_REAL_LOCATION_ESP_MIGRATION, false)) return;
        if (ESP_MODE.get() == EspMode.BOX) ESP_MODE.set(EspMode.WIREFRAME);
        Settings.setBoolean(RESTORE_REAL_LOCATION_ESP_MIGRATION, true);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /**
     * Decides whether an incoming packet should be queued. Returns {@code true} when
     * the packet must not reach the vanilla packet handler yet.
     */
    @SuppressWarnings("unused")
    public static boolean handleIncomingPacket(Packet<?> packet, PacketListener listener) {
        if (!ENABLED.get()) {
            return false;
        }

        // Track the target's real (server-side) position on every incoming packet,
        // regardless of whether it ends up queued. The ESP renders from this
        // position while the client-side entity stays on the delayed one.
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client == null ? null : client.level;
        LocalPlayer player = client == null ? null : client.player;

        Entity currentTarget = target;
        if (observeTargetAndReleaseIfNeeded(
                packet, level, player, currentTarget)) {
            return false;
        }

        // Flush the queue when the world around us changes drastically
        if (requiresTrackingReset(packet)) {
            clear(true, false, true);
            return false;
        }

        if (!shouldQueueTargetMovement(packet, currentTarget, level)) {
            return false;
        }
        return enqueueOrRelease(packet);
    }

    private static boolean observeTargetAndReleaseIfNeeded(
            Packet<?> packet,
            ClientLevel level,
            LocalPlayer player,
            Entity currentTarget
    ) {
        if (currentTarget == null || level == null || player == null) {
            return false;
        }
        Vec3 previousRealPosition = POSITION.base();
        Vec3 pos = POSITION.handlePacket(packet, level, currentTarget);

        // Intent must never freeze an enemy that is actually closing on us.
        // The rendered entity can be several packets behind, so compare two
        // consecutive server positions rather than trusting its client AABB.
        if (pos != null
                && isIntentMode()
                && isApproachingPlayer(
                currentTarget, player, previousRealPosition, pos)) {
            intentApproachReleaseUntilTick = gameTick
                    + INTENT_APPROACH_RELEASE_TICKS;
            flushIncoming();
            armed = false;
            return true;
        }

        // Is the target's actual position closer than its tracked position?
        if (pos != null
                && squareBoxedDistanceTo(currentTarget, player, pos)
                < squaredBoxedDistanceTo(currentTarget, player)) {
            // Process all packets. We want to be able to hit the enemy, not the opposite.
            flushIncoming();
            return true;
        }
        return false;
    }

    private static boolean requiresTrackingReset(Packet<?> packet) {
        return packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundDisconnectPacket
                || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket
                || (packet instanceof ClientboundSetHealthPacket healthPacket
                    && healthPacket.getHealth() <= 0.0F);
    }

    private static boolean shouldQueueTargetMovement(
            Packet<?> packet, Entity currentTarget, ClientLevel level) {
        // Only queue while the attack window is armed. This keeps the queue empty
        // outside of the few ticks before an expected hit (precision backtrack).
        final boolean shouldCancel = armed && shouldCancelPackets();
        final boolean hasQueuedIncoming = hasQueuedIncoming();

        if (!hasQueuedIncoming && !shouldCancel) {
            return false;
        }

        // Delay only packets that move the tracked enemy. Ping requests, velocity,
        // effects and other entities keep flowing untouched; queueing them would
        // make the server see out-of-order responses (Grim TransactionOrder) and
        // delayed physics (Grim Simulation).
        return currentTarget != null
                && isTargetPositionPacket(packet, currentTarget, level);
    }

    private static boolean enqueueOrRelease(Packet<?> packet) {
        long timestamp = System.currentTimeMillis();
        if (!enqueueSnapshot(packet, timestamp)) {
            flushIncoming();
            armed = false;
            return false;
        }
        return true;
    }

    /**
     * Enqueues movement packets with a monotonic deadline. Independent jitter on
     * adjacent packets used to let a newer position overtake an older one, then
     * apply the stale position afterwards, which looked like a short rewind.
     */
    private static boolean enqueueSnapshot(Packet<?> packet, long timestamp) {
        synchronized (PACKET_QUEUE_LOCK) {
            if (PACKET_QUEUE.size() >= MAX_QUEUE_SIZE.get()) {
                return false;
            }
            long candidate = Math.max(timestamp,
                    timestamp + currentDelay
                            + randomInt(-JITTER_MS.get(), JITTER_MS.get()));
            long releaseAt = PACKET_QUEUE.isEmpty()
                    ? candidate
                    : Math.max(candidate, lastQueuedReleaseAt + 1L);
            lastQueuedReleaseAt = releaseAt;
            PACKET_QUEUE.add(new PacketSnapshot(packet, timestamp, releaseAt));
            return true;
        }
    }

    private static boolean isTargetPositionPacket(Packet<?> packet, Entity target, ClientLevel level) {
        if (packet instanceof ClientboundMoveEntityPacket movePacket) {
            return level != null && movePacket.getEntity(level) == target;
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleportPacket) {
            return teleportPacket.id() == target.getId();
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket syncPacket) {
            return syncPacket.id() == target.getId();
        }
        return false;
    }

    /**
     * Called when the player attacks an entity (MultiPlayerGameMode#attack).
     */
    public static void onAttack(Entity entity) {
        if (!ENABLED.get()) {
            return;
        }

        if (!isIntentMode()) {
            attackChronometerLastUpdate = System.currentTimeMillis();
            chancePassed = rollChance();

            // 命中后立刻 flush：结束本次回溯窗口，实体马上回到最新位置
            flushIncoming();
            armed = false;

            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client == null ? null : client.player;
            if (player != null) {
                attackCooldownTicks = Math.max(1, (int) player.getCurrentItemAttackStrengthDelay());
            }

            // 下次攻击前 armWindowTicks 个 tick 开始扣包，到预期攻击 tick 结束
            armAtTick = gameTick + Math.max(0, attackCooldownTicks - ARM_WINDOW_TICKS.get());
            disarmAtTick = gameTick + attackCooldownTicks;
        }

        if (entity == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player != null) {
            lastHitDistance = boxedDistanceTo(entity, player);
            lastRealDistance = realDistanceTo(entity, player);
        }

        if (!isAttackMode()) {
            return;
        }

        processTarget(entity);
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get()) {
            return;
        }

        if (!inGame(client)) {
            clear(false, true, false);
            armed = false;
            return;
        }

        gameTick++;
        advanceTrackingWindow();
        updateTargetForCurrentMode(client);
        releaseOrFlushQueuedPackets();
        updateActionBarIfNeeded(client);
    }

    private static void advanceTrackingWindow() {
        trackTargetSpeed();
        updateArmState();
        updateDynamicDelay();
    }

    private static void updateTargetForCurrentMode(Minecraft client) {
        // When the enemy runs too far away, holding its old position is useless:
        // release the whole queue at once so the client shows its real position.
        Entity currentTarget = target;
        if (currentTarget != null && currentTarget.isAlive() && client.player != null) {
            double maxRange = Math.max(RANGE_MIN.get(), RANGE_MAX.get());
            if (realDistanceTo(currentTarget, client.player) > maxRange) {
                clear();
            }
        }

        if (isRangeMode()) {
            Entity enemy = findEnemy(RANGE_MIN.get(), RANGE_MAX.get());

            if (enemy == null) {
                clear();
                armed = false;
            } else {
                processTarget(enemy);
            }
        } else if (isIntentMode()) {
            Entity enemy = findAimedEnemy();

            if (enemy == null) {
                clear();
                armed = false;
            } else {
                processTarget(enemy);
            }
        }
    }

    private static void releaseOrFlushQueuedPackets() {
        final boolean hasQueuedIncoming = hasQueuedIncoming();

        if (armed && shouldCancelPackets()) {
            // 平滑放行：每 tick 最多放行 maxPacketsPerTick 个到期的包
            releaseDuePackets();
        } else if (hasQueuedIncoming) {
            flushIncoming();
            clear();
        }
    }

    private static void updateActionBarIfNeeded(Minecraft client) {
        if (ACTION_BAR.get() && gameTick % 4 == 0) {
            updateActionBar(client);
        }
    }

    private static void updateActionBar(Minecraft client) {
        String state;
        if (armed && hasQueuedIncoming()) {
            state = "lagging";
        } else if (armed) {
            state = "armed";
        } else {
            state = "standby";
        }

        String targetName = target == null ? "-" : target.getName().getString();
        ClientChat.actionBar(
                client,
                "Backtrack " + state
                        + " | q:" + PACKET_QUEUE.size()
                        + " | d:" + currentDelay + "ms"
                        + " | t:" + targetName
        );
    }

    /** HUD tag: delay range plus the last attack's hit/real distances. */
    public static String hudStats() {
        String text = formatIntRange(DELAY_MIN.get(), DELAY_MAX.get()) + "ms";
        if (lastHitDistance > 0.0D && lastRealDistance > 0.0D) {
            text += " hit:" + format2(lastHitDistance) + " real:" + format2(lastRealDistance);
        }
        return text;
    }

    private static void processTarget(Entity enemy) {
        shouldPause = enemy instanceof LivingEntity living
                && living.hurtTime >= PAUSE_ON_HURT_TIME_VALUE.get();

        if (!shouldBacktrack(enemy)) {
            return;
        }

        // Reset on enemy change
        if (enemy != target) {
            clear(true, false, false);

            // Intent mode rolls the chance per target instead of per attack
            if (isIntentMode()) {
                chancePassed = rollChance();
            }

            // Instantly set new position, so it does not look like the box was created with delay
            POSITION.setBaseFrom(enemy);
            renderPosition = null;
            sampleDelayWindow();
        }

        target = enemy;
    }

    private static boolean shouldBacktrack(Entity enemy) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) {
            return false;
        }

        double distance = boxedDistanceTo(enemy, player);
        double minRange = Math.min(RANGE_MIN.get(), RANGE_MAX.get());
        double maxRange = Math.max(RANGE_MIN.get(), RANGE_MAX.get());
        boolean inRange = distance >= minRange && distance <= maxRange;

        if (inRange) {
            trackingBufferChronometerLastUpdate = System.currentTimeMillis();
        }

        boolean buffered = distance >= minRange
                && !hasElapsed(trackingBufferChronometerLastUpdate, TRACKING_BUFFER.get());
        return (inRange || buffered)
                && shouldBeAttacked(enemy)
                && player.tickCount > 10
                && chancePassed
                && hasElapsed(chronometerLastUpdate, 0)
                && !shouldPause()
                && (isIntentMode() || !hasElapsed(attackChronometerLastUpdate, LAST_ATTACK_TIME.get()))
                && !isVelocityReduceBlocking();
    }

    public static boolean isLagging() {
        return ENABLED.get() && hasQueuedIncoming();
    }

    private static boolean shouldPause() {
        return PAUSE_ON_HURT_TIME.get() && shouldPause;
    }

    private static boolean shouldCancelPackets() {
        Entity currentTarget = target;
        return currentTarget != null && currentTarget.isAlive() && shouldBacktrack(currentTarget);
    }

    private static boolean hasQueuedIncoming() {
        return !PACKET_QUEUE.isEmpty();
    }

    private static void flushIncoming() {
        while (true) {
            PacketSnapshot snapshot;
            synchronized (PACKET_QUEUE_LOCK) {
                snapshot = PACKET_QUEUE.poll();
                if (snapshot == null) {
                    lastQueuedReleaseAt = 0L;
                    return;
                }
            }
            handleSnapshot(snapshot);
        }
    }

    private static void discardIncoming() {
        synchronized (PACKET_QUEUE_LOCK) {
            PACKET_QUEUE.clear();
            lastQueuedReleaseAt = 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleSnapshot(PacketSnapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener listener = client == null ? null : client.getConnection();
        if (listener == null) {
            return;
        }

        try {
            ((Packet<ClientGamePacketListener>) snapshot.packet()).handle(listener);
        } catch (Exception ignored) {
        }
    }

    private static void clear() {
        clear(true, false, true);
    }

    private static void clear(boolean handlePackets, boolean clearOnly, boolean resetChronometer) {
        if (handlePackets && !clearOnly) {
            flushIncoming();
        } else if (clearOnly) {
            discardIncoming();
        }

        if (target != null && resetChronometer) {
            waitForAtLeast(NEXT_BACKTRACK_DELAY_MIN.get(), NEXT_BACKTRACK_DELAY_MAX.get());
        }

        target = null;
        POSITION.base(Vec3.ZERO);
        renderPosition = null;
        armed = false;
        intentApproachReleaseUntilTick = -1;
        TARGET_POSITION_SAMPLES.clear();
    }

    private static boolean isApproachingPlayer(
            Entity currentTarget,
            Entity player,
            Vec3 previousPosition,
            Vec3 currentPosition
    ) {
        if (previousPosition == null || currentPosition == null
                || previousPosition.distanceToSqr(currentPosition)
                < INTENT_APPROACH_MIN_DISTANCE_SQUARED) {
            return false;
        }
        double previousDistance = squareBoxedDistanceTo(
                currentTarget, player, previousPosition);
        double currentDistance = squareBoxedDistanceTo(
                currentTarget, player, currentPosition);
        return currentDistance + INTENT_APPROACH_MIN_DISTANCE_SQUARED
                < previousDistance;
    }

    /**
     * Arms the packet queue only during the short window before the next expected
     * attack. Before the first attack the window stays open while a target exists.
     */
    private static void updateArmState() {
        Entity currentTarget = target;
        if (currentTarget == null || !currentTarget.isAlive()) {
            armed = false;
            return;
        }

        boolean newArmed;
        if (isIntentMode()) {
            // Re-arm only after the server trajectory has stopped closing on us.
            newArmed = gameTick > intentApproachReleaseUntilTick;
        } else if (armAtTick < 0) {
            // No attack has calibrated the window yet (e.g. Range mode): stay armed
            // while a target exists.
            newArmed = true;
        } else {
            // Precise window: only the few ticks before the expected next attack.
            newArmed = gameTick >= armAtTick && gameTick <= disarmAtTick;
        }
        if (newArmed && !armed) {
            sampleDelayWindow();
        }
        armed = newArmed;
    }

    /**
     * Releases packets that reached their release time, at most {@link #MAX_PACKETS_PER_TICK}
     * per tick, so the queue drains with a natural network-like pace.
     */
    private static void releaseDuePackets() {
        long now = System.currentTimeMillis();
        int released = 0;

        while (released < MAX_PACKETS_PER_TICK.get()) {
            PacketSnapshot snapshot;
            synchronized (PACKET_QUEUE_LOCK) {
                snapshot = PACKET_QUEUE.peek();
                if (snapshot == null) {
                    lastQueuedReleaseAt = 0L;
                    return;
                }
                // Deadlines are monotonic, so a later packet can never be due
                // before the head. This also guarantees movement packet order.
                if (now < snapshot.releaseAt()) {
                    return;
                }
                PACKET_QUEUE.poll();
                if (PACKET_QUEUE.isEmpty()) {
                    lastQueuedReleaseAt = 0L;
                }
            }
            handleSnapshot(snapshot);
            released++;
        }
    }

    private static void trackTargetSpeed() {
        Entity currentTarget = target;
        if (currentTarget == null) {
            TARGET_POSITION_SAMPLES.clear();
            return;
        }

        TARGET_POSITION_SAMPLES.addLast(new PositionSample(gameTick, currentTarget.position()));
        while (TARGET_POSITION_SAMPLES.size() > SPEED_SAMPLE_TICKS) {
            TARGET_POSITION_SAMPLES.removeFirst();
        }
    }

    private static double targetSpeedBlocksPerSecond() {
        if (TARGET_POSITION_SAMPLES.size() < 2) {
            return 0.0D;
        }

        PositionSample first = TARGET_POSITION_SAMPLES.getFirst();
        PositionSample last = TARGET_POSITION_SAMPLES.getLast();
        long tickDiff = last.tick() - first.tick();
        if (tickDiff <= 0) {
            return 0.0D;
        }

        double distance = first.pos().distanceTo(last.pos());
        return distance / (tickDiff * 0.05D);
    }

    /**
     * Base delay randomized from the configured range, increased by the target's
     * movement speed, optionally capped by the player's ping, plus per-packet jitter
     * applied when the packet is queued.
     */
    private static int dynamicDelay(int baseDelay) {
        double speed = targetSpeedBlocksPerSecond();

        int maxAllowed = DELAY_MAX.get();
        Integer ping = currentPing();
        if (PING_RATIO.get() > 0.0D && ping != null && ping > 0) {
            maxAllowed = Math.max(DELAY_MIN.get(),
                    Math.min(DELAY_MAX.get(), (int) Math.round(ping * PING_RATIO.get())));
        }

        double value = baseDelay + speed * SPEED_FACTOR.get();
        return (int) Math.round(Mth.clamp(value, DELAY_MIN.get(), maxAllowed));
    }

    /** Samples randomness once for one armed window instead of on every empty tick. */
    private static void sampleDelayWindow() {
        sampledBaseDelay = randomInt(DELAY_MIN.get(), DELAY_MAX.get());
        currentDelay = dynamicDelay(sampledBaseDelay);
    }

    /** Keeps speed/ping adaptation responsive without abrupt delay jumps. */
    private static void updateDynamicDelay() {
        if (target == null) {
            return;
        }
        int desired = dynamicDelay(sampledBaseDelay);
        int difference = desired - currentDelay;
        currentDelay += Mth.clamp(difference, -4, 4);
    }

    private static Integer currentPing() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getConnection() == null || client.player == null) {
            return null;
        }

        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? null : info.getLatency();
    }

    private static Entity findEnemy(double minRange, double maxRange) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        ClientLevel level = client == null ? null : client.level;
        if (player == null || level == null) {
            return null;
        }

        double minRangeSquared = minRange * minRange;
        double maxRangeSquared = maxRange * maxRange;

        Entity best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : level.entitiesForRendering()) {
            if (!shouldBeAttacked(entity)) {
                continue;
            }

            double distanceSquared = squaredBoxedDistanceTo(entity, player);
            if (distanceSquared >= minRangeSquared
                    && distanceSquared <= maxRangeSquared
                    && distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = entity;
            }
        }

        return best;
    }

    /**
     * Finds the entity currently under the crosshair (intent mode). The pick ray is
     * cast from the player's eye position along the view vector, and the closest
     * intersecting hostile target within range is returned.
     */
    private static Entity findAimedEnemy() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        ClientLevel level = client == null ? null : client.level;
        if (player == null || level == null) {
            return null;
        }

        double minDistance = Math.min(RANGE_MIN.get(), RANGE_MAX.get());
        double maxDistance = Math.max(RANGE_MIN.get(), RANGE_MAX.get());
        double minDistanceSquared = minDistance * minDistance;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);

        Entity best = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : level.entitiesForRendering()) {
            if (!shouldBeAttacked(entity)) {
                continue;
            }

            double boxDistanceSquared = squaredBoxedDistanceTo(entity, player);
            if (boxDistanceSquared < minDistanceSquared
                    || boxDistanceSquared > maxDistance * maxDistance) {
                continue;
            }

            Vec3 hit = rayBoxIntersection(eye, look, entityBox(entity).inflate(0.15D), maxDistance);
            if (hit == null) {
                continue;
            }

            double hitDistanceSquared = hit.distanceToSqr(eye);
            if (hitDistanceSquared < bestDistanceSquared) {
                bestDistanceSquared = hitDistanceSquared;
                best = entity;
            }
        }

        return best;
    }

    private static Vec3 rayBoxIntersection(Vec3 origin, Vec3 direction, AABB box, double maxDistance) {
        double tMin = 0.0D;
        double tMax = maxDistance;

        for (int axis = 0; axis < 3; axis++) {
            double originAxis = axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z;
            double directionAxis = axis == 0 ? direction.x : axis == 1 ? direction.y : direction.z;
            double minAxis = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
            double maxAxis = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;

            if (Math.abs(directionAxis) < 1.0E-6D) {
                if (originAxis < minAxis || originAxis > maxAxis) {
                    return null;
                }
                continue;
            }

            double entry = (minAxis - originAxis) / directionAxis;
            double exit = (maxAxis - originAxis) / directionAxis;
            if (entry > exit) {
                double swap = entry;
                entry = exit;
                exit = swap;
            }

            tMin = Math.max(tMin, entry);
            tMax = Math.min(tMax, exit);
            if (tMin > tMax) {
                return null;
            }
        }

        return origin.add(direction.scale(Math.max(0.0D, tMin)));
    }

    /**
     * LiquidBounce default combat target filter: players, hostiles, angerable mobs
     * and water creatures, including invisible entities, excluding dead/sleeping ones.
     */
    private static boolean shouldBeAttacked(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player == null || entity == player || entity.hasPassenger(player)) {
            return false;
        }

        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }

        if (living instanceof Player targetPlayer) {
            return targetPlayer != player && !targetPlayer.isSleeping();
        }

        if (living instanceof WaterAnimal) {
            return true;
        }

        if (living instanceof Monster || living instanceof Enemy) {
            return true;
        }

        return living instanceof NeutralMob;
    }

    private static double boxedDistanceTo(Entity entity, Entity other) {
        return Math.sqrt(squaredBoxedDistanceTo(entity, other));
    }

    /**
     * Distance to the target's real (server-side) position tracked in
     * {@link #POSITION}, falling back to the rendered position when no real
     * position has been observed yet.
     */
    private static double realDistanceTo(Entity entity, Entity other) {
        Vec3 base = POSITION.base();
        if (base == null || (base.x == 0.0D && base.y == 0.0D && base.z == 0.0D)) {
            return boxedDistanceTo(entity, other);
        }
        return Math.sqrt(squareBoxedDistanceTo(entity, other, base));
    }

    private static double squaredBoxedDistanceTo(Entity entity, Entity other) {
        return entityBox(entity).distanceToSqr(other.getEyePosition());
    }

    private static double squareBoxedDistanceTo(Entity entity, Entity other, Vec3 offsetPos) {
        Vec3 offset = offsetPos.subtract(entity.position());
        return entityBox(entity).move(offset).distanceToSqr(other.getEyePosition());
    }

    private static AABB entityBox(Entity entity) {
        return entity.getBoundingBox().inflate(entity.getPickRadius());
    }

    private static boolean inGame(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    private static boolean isAttackMode() {
        return TARGET_MODE.get() == TargetMode.ATTACK;
    }

    private static boolean isRangeMode() {
        return TARGET_MODE.get() == TargetMode.RANGE;
    }

    private static boolean isIntentMode() {
        return TARGET_MODE.get() == TargetMode.INTENT;
    }

    private static boolean isVelocityReduceBlocking() {
        // LiquidBounce: VelocityReduce.backtrackBlocked (not present in Moons).
        return false;
    }

    private static boolean rollChance() {
        float chance = (float) CHANCE.get();
        if (chance <= 0.0F) {
            return false;
        }

        if (chance >= 100.0F) {
            return true;
        }

        return RandomMath.chancePercent(chance);
    }

    private static boolean hasElapsed(long lastUpdate, long ms) {
        return lastUpdate + ms < System.currentTimeMillis();
    }

    private static void waitForAtLeast(int minDelayMs, int maxDelayMs) {
        chronometerLastUpdate = Math.max(
                chronometerLastUpdate,
                System.currentTimeMillis() + randomInt(minDelayMs, maxDelayMs)
        );
    }

    private static int randomInt(int min, int max) {
        if (min >= max) {
            return min;
        }

        return RandomMath.betweenInclusive(min, max);
    }

    /** Updates visual-only positions exactly once per rendered frame. */
    private static void frame(FrameEvent event) {
        Minecraft client = event.client();
        if (!ENABLED.get() || !inGame(client)) {
            renderPosition = null;
            return;
        }

        double deltaSeconds = Mth.clamp(
                event.deltaSeconds(), 1.0D / 500.0D, 1.0D / 20.0D);
        Vec3 base = POSITION.base();
        if (base == null || isZeroPosition(base)) {
            renderPosition = null;
        } else {
            renderPosition = approachFramePosition(
                    renderPosition, base, deltaSeconds, 28.0D);
        }

    }

    private static Vec3 approachFramePosition(
            Vec3 current,
            Vec3 targetPosition,
            double deltaSeconds,
            double responseSpeed
    ) {
        if (current == null) {
            return targetPosition;
        }
        double response = 1.0D - Math.exp(-responseSpeed * deltaSeconds);
        return new Vec3(
                current.x + (targetPosition.x - current.x) * response,
                current.y + (targetPosition.y - current.y) * response,
                current.z + (targetPosition.z - current.z) * response);
    }

    private static boolean isZeroPosition(Vec3 position) {
        return position.x == 0.0D && position.y == 0.0D && position.z == 0.0D;
    }

    private static EspData getEspData() {
        Entity entity = target;
        if (entity == null) {
            return null;
        }

        Vec3 base = POSITION.base();
        if (base == null || (base.x == 0.0D && base.y == 0.0D && base.z == 0.0D)) {
            return null;
        }

        Vec3 position = renderPosition == null ? base : renderPosition;
        return new EspData(entity, position, entity.getYRot(), entity.getXRot());
    }

    /** Standalone equivalent of Fabric's COLLECT_SUBMITS callback. */
    public static void renderModel(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector
    ) {
        if (!ENABLED.get() || !isModelEsp()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        EspData data = getEspData();
        if (data == null) {
            return;
        }

        Entity entity = data.entity();
        @SuppressWarnings("rawtypes")
        EntityRenderer renderer = client.getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 0.0F);

        int outlineColor = parseColor(MODEL_OUTLINE_COLOR.get());
        if (alpha(outlineColor) > 0) {
            state.outlineColor = outlineColor;
        }

        state.x = data.pos().x;
        state.y = data.pos().y;
        state.z = data.pos().z;

        CameraRenderState camera = levelRenderState.cameraRenderState;
        state.distanceToCameraSq = camera.pos.distanceToSqr(state.x, state.y, state.z);
        state.lightCoords = scaleLightCoords(
                state.lightCoords, MODEL_LIGHT_PERCENT.get() * 0.01f);

        if (state instanceof LivingEntityRenderState livingState) {
            float bodyYaw = data.yRot();
            livingState.bodyRot = bodyYaw;
            livingState.yRot = Mth.wrapDegrees(bodyYaw - livingState.bodyRot);
            livingState.xRot = data.xRot();
        }

        client.getEntityRenderDispatcher().submit(
                state,
                camera,
                state.x - camera.pos.x,
                state.y - camera.pos.y,
                state.z - camera.pos.z,
                poseStack,
                submitNodeCollector
        );
    }

    private static void renderEsp(WorldRenderEvent context) {
        if (!ENABLED.get() || !isBoxEsp() && !isWireframeEsp() && !isModelEsp()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        EspData data = getEspData();
        if (data == null) {
            return;
        }

        PoseStack matrices = context.poseStack();
        Vec3 cameraPos = MinecraftClientAccess.camera(client).position();

        matrices.pushPose();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (isBoxEsp()) {
            renderBoxEsp(matrices, data);
        } else if (isWireframeEsp()) {
            renderWireframeEsp(matrices, data);
        }

        matrices.popPose();
    }

    private static void renderBoxEsp(PoseStack matrices, EspData data) {
        Entity entity = data.entity();
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        double halfWidth = dimensions.width() / 2.0D;

        AABB box = new AABB(
                -halfWidth,
                0.0D,
                -halfWidth,
                halfWidth,
                dimensions.height(),
                halfWidth
        ).inflate(0.05D).move(data.pos());

        BacktrackRenderer.renderBox(matrices, box,
                parseColor(BOX_COLOR.get()), parseColor(BOX_OUTLINE_COLOR.get()), "backtrack box");
    }

    private static void renderWireframeEsp(PoseStack matrices, EspData data) {
        Entity entity = data.entity();

        matrices.translate(data.pos().x, data.pos().y, data.pos().z);

        WIREFRAME.setRotation(data.xRot(), data.yRot());
        WIREFRAME.setPose(entity.getPose());
        WIREFRAME.setSwimAmount(
                entity instanceof LivingEntity living ? living.getSwimAmount(0.0F) : 0.0F
        );
        WIREFRAME.render(matrices, parseColor(WIREFRAME_COLOR.get()), parseColor(WIREFRAME_OUTLINE_COLOR.get()));
    }

    private static int scaleLightCoords(int lightCoords, float scale) {
        int block = (int) Math.max(0, Math.min(15, Math.round(LightCoordsUtil.block(lightCoords) * scale)));
        int sky = (int) Math.max(0, Math.min(15, Math.round(LightCoordsUtil.sky(lightCoords) * scale)));
        return LightCoordsUtil.pack(block, sky);
    }

    private static boolean isBoxEsp() {
        return ESP_MODE.get() == EspMode.BOX;
    }

    private static boolean isModelEsp() {
        return ESP_MODE.get() == EspMode.MODEL;
    }

    private static boolean isWireframeEsp() {
        return ESP_MODE.get() == EspMode.WIREFRAME;
    }

    private static int alpha(int argb) {
        return argb >>> 24 & 0xFF;
    }

    private static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Backtrack: " + statusText()
                        + ", range: " + formatRange(RANGE_MIN.get(), RANGE_MAX.get())
                        + ", delay: " + formatIntRange(DELAY_MIN.get(), DELAY_MAX.get()) + " ms"
                        + ", nextBacktrackDelay: " + formatIntRange(NEXT_BACKTRACK_DELAY_MIN.get(), NEXT_BACKTRACK_DELAY_MAX.get()) + " ms"
                        + ", trackingBuffer: " + TRACKING_BUFFER.get() + " ms"
                        + ", chance: " + format(CHANCE.get()) + "%"
                        + ", targetMode: " + TARGET_MODE.serialized()
                        + ", pauseOnHurtTime: " + (PAUSE_ON_HURT_TIME.get() ? "enabled" : "disabled")
                        + " (" + PAUSE_ON_HURT_TIME_VALUE.get() + ")"
                        + ", lastAttackTimeToWork: " + LAST_ATTACK_TIME.get() + " ms"
                        + ", armWindowTicks: " + ARM_WINDOW_TICKS.get()
                        + ", maxPacketsPerTick: " + MAX_PACKETS_PER_TICK.get()
                        + ", jitter: " + JITTER_MS.get() + " ms"
                        + ", speedFactor: " + format(SPEED_FACTOR.get())
                        + ", pingRatio: " + format(PING_RATIO.get())
                        + ", actionbar: " + (ACTION_BAR.get() ? "enabled" : "disabled")
                        + ", esp: " + ESP_MODE.serialized()
                        + ". Usage: .moons backtrack <enable|disable|range x-x|delay x-x|nextbacktrackdelay x-x"
                        + "|trackingbuffer x|chance x|targetmode attack|range|intent|hurt|lastattacktime x"
                        + "|armticks 1-5|maxpackets 1-10|jitter 0-50|speedfactor 0-30|pingratio 0-3|actionbar enable|disable"
                        + "|esp box|model|wireframe|none|color rrggbb|aarrggbb|outlinecolor rrggbb|aarrggbb|lightpercent x>"
        );
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);

        clear(false, false, true);
        if (!ENABLED.get()) {
            clear(true, false, true);
        }

        ClientChat.send(client, "Backtrack " + statusText() + ".");
        return 1;
    }

    public static int setRange(Minecraft client, String rawRange) {
        double[] parsed = parseRange(rawRange, MIN_SUPPORTED_RANGE, MAX_SUPPORTED_RANGE);
        if (parsed == null) {
            ClientChat.send(client, "Invalid Backtrack range. Use .moons backtrack range x-x, each between 0 and 10.");
            return 0;
        }

        RANGE_MIN.set(parsed[0]);
        RANGE_MAX.set(parsed[1]);
        ClientChat.send(client, "Backtrack range set to " + formatRange(RANGE_MIN.get(), RANGE_MAX.get()) + ".");
        return 1;
    }

    public static int setDelay(Minecraft client, String rawRange) {
        double[] parsed = parseRange(rawRange, MIN_SUPPORTED_DELAY, MAX_SUPPORTED_DELAY);
        if (parsed == null) {
            ClientChat.send(client, "Invalid Backtrack delay. Use .moons backtrack delay x-x, each between 0 and 1000 ms.");
            return 0;
        }

        DELAY_MIN.set((int) parsed[0]);
        DELAY_MAX.set((int) parsed[1]);
        sampleDelayWindow();
        ClientChat.send(client, "Backtrack delay set to " + formatIntRange(DELAY_MIN.get(), DELAY_MAX.get()) + " ms.");
        return 1;
    }

    public static int setNextBacktrackDelay(Minecraft client, String rawRange) {
        double[] parsed = parseRange(rawRange, MIN_SUPPORTED_NEXT_DELAY, MAX_SUPPORTED_NEXT_DELAY);
        if (parsed == null) {
            ClientChat.send(client, "Invalid Backtrack nextBacktrackDelay. Use .moons backtrack nextbacktrackdelay x-x, each between 0 and 2000 ms.");
            return 0;
        }

        NEXT_BACKTRACK_DELAY_MIN.set((int) parsed[0]);
        NEXT_BACKTRACK_DELAY_MAX.set((int) parsed[1]);
        ClientChat.send(client, "Backtrack nextBacktrackDelay set to "
                + formatIntRange(NEXT_BACKTRACK_DELAY_MIN.get(), NEXT_BACKTRACK_DELAY_MAX.get()) + " ms.");
        return 1;
    }

    public static int setTrackingBuffer(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 0, MAX_SUPPORTED_TRACKING_BUFFER);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack trackingBuffer. Use .moons backtrack trackingbuffer 0-2000 ms.");
            return 0;
        }

        TRACKING_BUFFER.set(value);
        ClientChat.send(client, "Backtrack trackingBuffer set to " + TRACKING_BUFFER.get() + " ms.");
        return 1;
    }

    public static int setChance(Minecraft client, String rawValue) {
        Float value = parseFloat(rawValue, 0.0F, 100.0F);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack chance. Use .moons backtrack chance 0-100.");
            return 0;
        }

        CHANCE.set(value);
        ClientChat.send(client, "Backtrack chance set to " + format(CHANCE.get()) + "%.");
        return 1;
    }

    public static int setTargetMode(Minecraft client, String value) {
        TARGET_MODE.deserialize(value);
        ClientChat.send(client, "Backtrack targetMode set to " + TARGET_MODE.serialized() + ".");
        return 1;
    }

    private static int showHurtStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Backtrack pauseOnHurtTime: " + (PAUSE_ON_HURT_TIME.get() ? "enabled" : "disabled")
                        + ", hurtTime: " + PAUSE_ON_HURT_TIME_VALUE.get()
                        + ". Usage: .moons backtrack hurt <enable|disable|time 0-10>"
        );
        return 1;
    }

    public static int setHurtEnabled(Minecraft client, boolean value) {
        PAUSE_ON_HURT_TIME.set(value);
        ClientChat.send(client, "Backtrack pauseOnHurtTime " + (PAUSE_ON_HURT_TIME.get() ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setHurtTime(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 0, 10);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack hurtTime. Use .moons backtrack hurt time 0-10.");
            return 0;
        }

        PAUSE_ON_HURT_TIME_VALUE.set(value);
        ClientChat.send(client, "Backtrack hurtTime set to " + PAUSE_ON_HURT_TIME_VALUE.get() + ".");
        return 1;
    }

    public static int setLastAttackTime(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 0, MAX_SUPPORTED_LAST_ATTACK_TIME);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack lastAttackTimeToWork. Use .moons backtrack lastattacktime 0-5000 ms.");
            return 0;
        }

        LAST_ATTACK_TIME.set(value);
        ClientChat.send(client, "Backtrack lastAttackTimeToWork set to " + LAST_ATTACK_TIME.get() + " ms.");
        return 1;
    }

    public static int setArmWindowTicks(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 1, 5);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack armWindowTicks. Use .moons backtrack armticks 1-5.");
            return 0;
        }

        ARM_WINDOW_TICKS.set(value);
        ClientChat.send(client, "Backtrack armWindowTicks set to " + ARM_WINDOW_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setMaxPacketsPerTick(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 1, 10);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack maxPacketsPerTick. Use .moons backtrack maxpackets 1-10.");
            return 0;
        }

        MAX_PACKETS_PER_TICK.set(value);
        ClientChat.send(client, "Backtrack maxPacketsPerTick set to " + MAX_PACKETS_PER_TICK.get() + " packets/tick.");
        return 1;
    }

    public static int setMaxQueueSize(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 32, 1024);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack maxQueueSize. Use 32-1024 packets.");
            return 0;
        }

        MAX_QUEUE_SIZE.set(value);
        if (PACKET_QUEUE.size() > MAX_QUEUE_SIZE.get()) {
            flushIncoming();
        }
        ClientChat.send(client, "Backtrack maxQueueSize set to " + MAX_QUEUE_SIZE.get() + " packets.");
        return 1;
    }

    public static int setJitter(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 0, 50);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack jitter. Use .moons backtrack jitter 0-50 ms.");
            return 0;
        }

        JITTER_MS.set(value);
        ClientChat.send(client, "Backtrack jitter set to " + JITTER_MS.get() + " ms.");
        return 1;
    }

    public static int setSpeedFactor(Minecraft client, String rawValue) {
        Double value = parseDouble(rawValue, 0.0D, 30.0D);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack speedFactor. Use .moons backtrack speedfactor 0-30.");
            return 0;
        }

        SPEED_FACTOR.set(value);
        ClientChat.send(client, "Backtrack speedFactor set to " + format(SPEED_FACTOR.get()) + ".");
        return 1;
    }

    public static int setPingRatio(Minecraft client, String rawValue) {
        Double value = parseDouble(rawValue, 0.0D, 3.0D);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack pingRatio. Use .moons backtrack pingratio 0-3 (0 disables ping calibration).");
            return 0;
        }

        PING_RATIO.set(value);
        ClientChat.send(client, "Backtrack pingRatio set to " + format(PING_RATIO.get()) + ".");
        return 1;
    }

    private static int showActionBarStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Backtrack actionbar: " + (ACTION_BAR.get() ? "enabled" : "disabled")
                        + ". Usage: .moons backtrack actionbar <enable|disable>"
        );
        return 1;
    }

    public static int setActionBarEnabled(Minecraft client, boolean value) {
        ACTION_BAR.set(value);
        ClientChat.send(client, "Backtrack actionbar " + (ACTION_BAR.get() ? "enabled" : "disabled") + ".");
        return 1;
    }

    private static int showEspStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Backtrack esp: " + ESP_MODE.serialized()
                        + ". Usage: .moons backtrack esp <box|model|wireframe|none>"
        );
        return 1;
    }

    public static int setEsp(Minecraft client, String value) {
        ESP_MODE.deserialize(value);
        ClientChat.send(client, "Backtrack esp set to " + ESP_MODE.serialized() + ".");
        return 1;
    }

    public static int setColor(Minecraft client, String rawColor, boolean outline) {
        int color = parseColor(rawColor);
        if (color == INVALID_COLOR) {
            ClientChat.send(client, "Invalid color. Use .moons backtrack " + (outline ? "outlinecolor" : "color")
                    + " rrggbb or aarrggbb (hex, # optional).");
            return 0;
        }
        String normalized = formatColor(color);

        if (isBoxEsp()) {
            if (outline) {
                BOX_OUTLINE_COLOR.set(normalized);
            } else {
                BOX_COLOR.set(normalized);
            }
        } else if (isModelEsp() && outline) {
            MODEL_OUTLINE_COLOR.set(normalized);
        } else if (isWireframeEsp()) {
            if (outline) {
                WIREFRAME_OUTLINE_COLOR.set(normalized);
            } else {
                WIREFRAME_COLOR.set(normalized);
            }
        } else {
            ClientChat.send(client, "Backtrack color is only available for the box/wireframe/model esp modes.");
            return 0;
        }

        ClientChat.send(client, "Backtrack " + (outline ? "outlineColor" : "color") + " set to " + normalized + ".");
        return 1;
    }

    public static int setLightPercent(Minecraft client, String rawValue) {
        Integer value = parseInt(rawValue, 0, 100);
        if (value == null) {
            ClientChat.send(client, "Invalid Backtrack lightPercent. Use .moons backtrack lightpercent 0-100.");
            return 0;
        }

        MODEL_LIGHT_PERCENT.set(value);
        ClientChat.send(client, "Backtrack lightPercent set to " + MODEL_LIGHT_PERCENT.get() + "%.");
        return 1;
    }

    private static double[] parseRange(String rawRange, double minSupported, double maxSupported) {
        String normalized = rawRange.trim().toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("-", -1);
        if (parts.length != 1 && parts.length != 2) {
            return null;
        }

        try {
            double parsedMin = Double.parseDouble(parts[0]);
            double parsedMax = parts.length == 1 ? parsedMin : Double.parseDouble(parts[1]);

            if (parsedMin > parsedMax) {
                double swap = parsedMin;
                parsedMin = parsedMax;
                parsedMax = swap;
            }

            if (!Double.isFinite(parsedMin)
                    || !Double.isFinite(parsedMax)
                    || parsedMin < minSupported
                    || parsedMax > maxSupported) {
                return null;
            }

            return new double[]{parsedMin, parsedMax};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseInt(String rawValue, int min, int max) {
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value < min || value > max) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Float parseFloat(String rawValue, float min, float max) {
        try {
            float value = Float.parseFloat(rawValue.trim());
            if (!Float.isFinite(value) || value < min || value > max) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Double parseDouble(String rawValue, double min, double max) {
        try {
            double value = Double.parseDouble(rawValue.trim());
            if (!Double.isFinite(value) || value < min || value > max) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static final int INVALID_COLOR = Integer.MIN_VALUE;

    private static int parseColor(String rawColor) {
        String normalized = rawColor.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }

        try {
            if (normalized.length() == 6) {
                // RRGGBB -> opaque (stored as AARRGGBB)
                return (int) (0xFF000000L | Long.parseLong(normalized, 16));
            }
            if (normalized.length() == 8) {
                // AARRGGBB (alpha first, matching the stored config format)
                return (int) Long.parseLong(normalized, 16);
            }
        } catch (NumberFormatException ignored) {
        }
        return INVALID_COLOR;
    }

    private static String formatColor(int argb) {
        return String.format(Locale.ROOT, "%08x", argb & 0xFFFFFFFFL);
    }

    private static String formatRange(double min, double max) {
        return format(min) + "-" + format(max);
    }

    private static String formatIntRange(int min, int max) {
        return Integer.toString(min) + "-" + Integer.toString(max);
    }

    private static String format(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    private static String format2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static List<String> targetModeOptions() { return TARGET_MODE.optionIds(); }
    public static List<String> espModeOptions() { return ESP_MODE.optionIds(); }
    public static boolean espEnabled() { return ESP_MODE.get() != EspMode.NONE; }
    public static boolean modelEspSelected() { return ESP_MODE.get() == EspMode.MODEL; }
    public static String espColor() {
        return switch (ESP_MODE.get()) {
            case BOX -> BOX_COLOR.get();
            case WIREFRAME -> WIREFRAME_COLOR.get();
            case MODEL -> MODEL_OUTLINE_COLOR.get();
            case NONE -> "00000000";
        };
    }
    public static String espOutlineColor() {
        return switch (ESP_MODE.get()) {
            case BOX -> BOX_OUTLINE_COLOR.get();
            case MODEL -> MODEL_OUTLINE_COLOR.get();
            case WIREFRAME -> WIREFRAME_OUTLINE_COLOR.get();
            case NONE -> "00000000";
        };
    }

    private enum TargetMode { ATTACK, RANGE, INTENT }
    private enum EspMode { BOX, MODEL, WIREFRAME, NONE }

    private record PacketSnapshot(Packet<?> packet, long timestamp, long releaseAt) {
    }

    private record PositionSample(long tick, Vec3 pos) {
    }

    private record EspData(Entity entity, Vec3 pos, float yRot, float xRot) {
    }
}
