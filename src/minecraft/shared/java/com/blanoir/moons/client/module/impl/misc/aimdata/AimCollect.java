package com.blanoir.moons.client.module.impl.misc.aimdata;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Packet-driven observations of nearby remote players, with immutable asynchronous analysis. */
public final class AimCollect {
    private static boolean enabled;
    public static final IntSetting RANGE =
            new IntSetting.Builder()
                    .name("aimcollect.range")
                    .defaultValue(32)
                    .range(8, 128)
                    .build();
    public static final IntSetting TARGET_RANGE =
            new IntSetting.Builder()
                    .name("aimcollect.targetRange")
                    .defaultValue(8)
                    .range(2, 32)
                    .build();
    public static final IntSetting COMBAT_SECONDS =
            new IntSetting.Builder()
                    .name("aimcollect.combatSeconds")
                    .defaultValue(3)
                    .range(1, 5)
                    .build();
    private static final int MAX_PLAYERS = 64;
    private static final Map<Packet<?>, Receipt> RECEIPTS = new IdentityHashMap<>();
    private static final Map<Integer, State> STATES = new HashMap<>();
    private static volatile boolean listening;
    private static AimDatasetWriter writer;
    private static ClientLevel level;
    private static Object connection;
    private static long sequence;
    private static boolean terminalReported;
    private static boolean restartRequested;
    private static CombatCapture capture = new CombatCapture(2048);

    private AimCollect() {}

    public static void init() {
        EventBus.PACKET_RECEIVE_PRE.register(
                "AimCollect.receipt",
                EventPriority.HIGHEST,
                event -> {
                    if (!listening) return;
                    Receipt receipt = new Receipt(System.nanoTime(), System.currentTimeMillis());
                    PacketAccess.forEachPacket(
                            event.packet(),
                            packet -> {
                                if (!supported(packet)) return;
                                synchronized (RECEIPTS) {
                                    if (!listening) return;
                                    if (RECEIPTS.size() >= 4096) RECEIPTS.clear();
                                    RECEIPTS.putIfAbsent(packet, receipt);
                                }
                            });
                });
        EventBus.PACKET_RECEIVE_APPLY.register("AimCollect.apply", AimCollect::apply);
        EventBus.TICK_END.register("AimCollect.tick", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AimCollect.context", event -> shutdown());
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static Path directory() {
        String configured = Settings.getString("aimcollect.directory", "");
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return Settings.file().getParent().resolve("datasets").resolve("aim");
    }

    public static void setDirectory(Minecraft client, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute())
            throw new IllegalArgumentException("Use an absolute directory path.");
        path = path.normalize();
        shutdown();
        Settings.setString("aimcollect.directory", path.toString());
        restartRequested = enabled;
        terminalReported = false;
        ClientChat.send(
                client, "Collect directory: " + path + ". The next session uses this directory.");
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        AimCollect.enabled = enabled;
        shutdown();
        // Keep the old writer until its asynchronous drain completes.
        terminalReported = false;
        restartRequested = enabled;
        ClientChat.send(
                client,
                "AimCollect "
                        + (enabled ? "enabled: " + directory() : "saving")
                        + ". Dataset limit: 50 GB.");
        return 1;
    }

    public static int setRange(Minecraft client, int value) {
        RANGE.set(value);
        return 1;
    }

    public static int setTargetRange(Minecraft client, int value) {
        TARGET_RANGE.set(value);
        return 1;
    }

    public static int setCombatSeconds(Minecraft client, int value) {
        COMBAT_SECONDS.set(value);
        return 1;
    }

    public static String hudTag() {
        return writer == null
                ? "Waiting"
                : writer.status()
                        + " | "
                        + writer.written()
                        + " | drop "
                        + writer.dropped()
                        + " | "
                        + (writer.used() / 1_000_000)
                        + " MB";
    }

    public static String hudState() {
        var current = writer;
        if (current == null) return "Waiting";
        String status = current.status();
        if (status.startsWith("Error")) return "Error";
        if (status.startsWith("Limit")) return "Limit";
        if (status.equals("Saved")) return "Saved";
        if (current.closing()) return "Saving";
        return status.equals("Starting") ? "Starting" : "Recording";
    }

    public static void shutdown() {
        listening = false;
        if (writer != null) writer.close();
        STATES.clear();
        capture = new CombatCapture(2048);
        synchronized (RECEIPTS) {
            RECEIPTS.clear();
        }
        level = null;
        connection = null;
    }

    private static void tick(Minecraft client) {
        if (writer != null && writer.done()) {
            if (!terminalReported) {
                ClientChat.send(client, "AimCollect " + hudTag() + ". " + directory());
                terminalReported = true;
            }
            if (!writer.status().equals("Saved") && !restartRequested) {
                listening = false;
                return; // Explicit off/on is required after quota or I/O errors.
            }
            writer = null;
        }
        if (!enabled || client.level == null || client.player == null) {
            if (level != null) shutdown();
            return;
        }
        if (level != null && (level != client.level || connection != client.getConnection()))
            shutdown();
        if (writer != null && writer.closing()) return;
        if (writer == null) {
            writer = new AimDatasetWriter(directory());
            level = client.level;
            connection = client.getConnection();
            sequence = 0;
            terminalReported = false;
            restartRequested = false;
            listening = true;
        }
        // Limit retained state and seed candidates once per tick, never invent packet samples.
        List<? extends Player> nearby = nearby(client);
        STATES.keySet().removeIf(id -> nearby.stream().noneMatch(player -> player.getId() == id));
        for (Player player : nearby) state(player);
        capture.retain(
                STATES.values().stream().map(state -> state.uuid).collect(Collectors.toSet()),
                System.nanoTime());
    }

    private static List<? extends Player> nearby(Minecraft client) {
        double range = RANGE.get() + TARGET_RANGE.get();
        return client.level.players().stream()
                .filter(
                        player ->
                                player.isAlive()
                                        && !player.isSpectator()
                                        && player.distanceToSqr(client.player) <= range * range)
                .sorted(Comparator.comparingDouble(player -> player.distanceToSqr(client.player)))
                .limit(MAX_PLAYERS)
                .toList();
    }

    private static State state(Player player) {
        State state = STATES.get(player.getId());
        if (state == null || !state.uuid.equals(player.getUUID().toString())) {
            state = new State(player);
            STATES.put(player.getId(), state);
        }
        return state;
    }

    private static boolean supported(Packet<?> packet) {
        return packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundEntityPositionSyncPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundAnimatePacket
                || packet instanceof ClientboundDamageEventPacket;
    }

    private static void apply(PacketReceiveEvent.Apply event) {
        Minecraft client = Minecraft.getInstance();
        if (!listening
                || writer == null
                || writer.closing()
                || client.level != level
                || event.listener() != connection
                || client.player == null) return;
        Packet<?> packet = event.packet();
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            PacketAccess.removedEntityIds(remove).forEach(STATES::remove);
            return;
        }
        if (!supported(packet)) return;
        Receipt receipt;
        synchronized (RECEIPTS) {
            receipt = RECEIPTS.remove(packet);
        }
        long now = System.nanoTime();
        long wall = System.currentTimeMillis();
        // A swing alone is not evidence of combat. Only server-reported PvP damage arms recording.
        if (packet instanceof ClientboundDamageEventPacket damage) {
            Entity attacker = level.getEntity(damage.sourceCauseId());
            Entity victim = level.getEntity(damage.entityId());
            if (attacker instanceof Player a && victim instanceof Player v && a != v) {
                if (STATES.containsKey(a.getId()) && STATES.containsKey(v.getId()))
                    capture.combat(
                            a.getUUID().toString(),
                            a.getId(),
                            v.getUUID().toString(),
                            v.getId(),
                            now,
                            COMBAT_SECONDS.get() * 1_000_000_000L,
                            writer::offer);
            }
        }
        Entity entity;
        if (packet instanceof ClientboundMoveEntityPacket p) entity = p.getEntity(level);
        else if (packet instanceof ClientboundRotateHeadPacket p) entity = p.getEntity(level);
        else if (packet instanceof ClientboundEntityPositionSyncPacket p)
            entity = level.getEntity(p.id());
        else if (packet instanceof ClientboundTeleportEntityPacket p)
            entity = level.getEntity(p.id());
        else if (packet instanceof ClientboundAnimatePacket p) entity = level.getEntity(p.getId());
        else if (packet instanceof ClientboundDamageEventPacket p)
            entity = level.getEntity(p.sourceCauseId());
        else return;
        if (!(entity instanceof Player player) || !player.isAlive() || player.isSpectator()) return;
        if (!STATES.containsKey(player.getId())) return;
        State state = state(player);
        boolean position = false, rotation = false, head = false, discontinuity = false;
        int action = -1, damageTarget = -1;
        if (packet instanceof ClientboundMoveEntityPacket p) {
            position = p.hasPosition();
            rotation = p.hasRotation();
            // APPLY precedes vanilla's mutation; use its exact packet codec base, not render lerp.
            if (position)
                state.position = PacketAccess.decodeEntityDelta(p, player.getPositionCodec());
            if (rotation) {
                state.yaw = p.getYRot();
                state.pitch = p.getXRot();
            }
            state.ground = p.isOnGround();
        } else if (packet instanceof ClientboundRotateHeadPacket p) {
            state.head = p.getYHeadRot();
            head = true;
        } else if (packet instanceof ClientboundEntityPositionSyncPacket p) {
            state.position = PacketAccess.syncPosition(p);
            state.yaw = PacketAccess.syncYaw(p);
            state.pitch = PacketAccess.syncPitch(p);
            state.ground = p.onGround();
            position = rotation = discontinuity = true;
        } else if (packet instanceof ClientboundTeleportEntityPacket p) {
            PositionMoveRotation value =
                    PositionMoveRotation.calculateAbsolute(
                            PositionMoveRotation.of(player), p.change(), p.relatives());
            state.position = value.position();
            state.yaw = value.yRot();
            state.pitch = value.xRot();
            state.ground = p.onGround();
            position = rotation = discontinuity = true;
        } else if (packet instanceof ClientboundAnimatePacket p) action = p.getAction();
        else if (packet instanceof ClientboundDamageEventPacket p) damageTarget = p.entityId();
        if (position) state.positionNanos = now;
        if (discontinuity) {
            state.headKnown = false;
            state.headNanos = -1;
        }
        if (rotation) {
            state.rotationNanos = now;
            state.rotationKnown = true;
        }
        if (head) {
            state.headNanos = now;
            state.headKnown = true;
        }
        if (player == client.player
                || state.position.distanceToSqr(client.player.position())
                        > RANGE.get() * RANGE.get()) return;
        var observer = snapshot(client, player, state);
        List<AimGeometry.Actor> candidates = new ArrayList<>();
        for (State candidate : STATES.values()) {
            Entity target = level.getEntity(candidate.id);
            if (!(target instanceof Player other)
                    || other == player
                    || !other.isAlive()
                    || other.isSpectator()) continue;
            Vec3 targetPosition = other == client.player ? other.position() : candidate.position;
            if (targetPosition.distanceToSqr(state.position) > Math.pow(TARGET_RANGE.get() + 2, 2))
                continue;
            if (other == client.player) candidates.add(snapshot(client, other, new State(other)));
            else candidates.add(snapshot(client, other, candidate));
        }
        capture.observe(
                new AimGeometry.Sample(
                        ++sequence,
                        receipt == null ? -1 : receipt.nanos,
                        now,
                        receipt == null ? -1 : receipt.epochMillis,
                        wall,
                        level.getGameTime(),
                        0,
                        -1,
                        -1,
                        -1,
                        0,
                        packet.getClass().getSimpleName(),
                        position,
                        rotation,
                        head,
                        discontinuity,
                        action,
                        damageTarget,
                        observer,
                        candidates,
                        STATES.size() >= MAX_PLAYERS,
                        TARGET_RANGE.get()),
                writer::offer);
    }

    private static AimGeometry.Actor snapshot(Minecraft client, Player player, State state) {
        Vec3 pos = state.position;
        if (player == client.player) pos = player.position();
        var box = player.getBoundingBox().move(pos.subtract(player.position()));
        var info =
                client.getConnection() == null
                        ? null
                        : client.getConnection().getPlayerInfo(player.getUUID());
        return new AimGeometry.Actor(
                player.getId(),
                state.uuid,
                point(pos),
                point(pos.add(0, player.getEyeHeight(), 0)),
                new AimGeometry.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                state.yaw,
                state.pitch,
                state.head,
                state.rotationKnown,
                state.headKnown,
                state.positionNanos,
                state.rotationNanos,
                state.headNanos,
                state.ground,
                player.isCrouching(),
                player.isSprinting(),
                player.getPose().name(),
                player == client.player,
                info == null ? -1 : info.getLatency());
    }

    private static AimGeometry.Point point(Vec3 value) {
        return new AimGeometry.Point(value.x, value.y, value.z);
    }

    private record Receipt(long nanos, long epochMillis) {}

    private static final class State {
        final int id;
        final String uuid;
        Vec3 position;
        float yaw, pitch, head;
        boolean ground, rotationKnown, headKnown;
        long positionNanos = -1, rotationNanos = -1, headNanos = -1;

        State(Player player) {
            id = player.getId();
            uuid = player.getUUID().toString();
            position = player.getPositionCodec().getBase();
            yaw = player.getYRot();
            pitch = player.getXRot();
            head = player.getYHeadRot();
            ground = player.onGround();
        }
    }
}
