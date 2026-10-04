package com.blanoir.moons.client.module.impl.misc.aimdata;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.Vec3;

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
    private static WorldClient level;
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
        if (!enabled || client.theWorld == null || client.thePlayer == null) {
            if (level != null) shutdown();
            return;
        }
        if (level != null && (level != client.theWorld || connection != client.getNetHandler()))
            shutdown();
        if (writer != null && writer.closing()) return;
        if (writer == null) {
            writer = new AimDatasetWriter(directory());
            level = client.theWorld;
            connection = client.getNetHandler();
            sequence = 0;
            terminalReported = false;
            restartRequested = false;
            listening = true;
        }
        // Limit retained state and seed candidates once per tick, never invent packet samples.
        List<? extends EntityPlayer> nearby = nearby(client);
        STATES.keySet()
                .removeIf(id -> nearby.stream().noneMatch(player -> player.getEntityId() == id));
        for (EntityPlayer player : nearby) state(player);
        capture.retain(
                STATES.values().stream().map(state -> state.uuid).collect(Collectors.toSet()),
                System.nanoTime());
    }

    private static List<? extends EntityPlayer> nearby(Minecraft client) {
        double range = RANGE.get() + TARGET_RANGE.get();
        return client.theWorld.playerEntities.stream()
                .filter(
                        player ->
                                player.isEntityAlive()
                                        && !player.isSpectator()
                                        && player.getDistanceSqToEntity(client.thePlayer)
                                                <= range * range)
                .sorted(
                        Comparator.comparingDouble(
                                player -> player.getDistanceSqToEntity(client.thePlayer)))
                .limit(MAX_PLAYERS)
                .toList();
    }

    private static State state(EntityPlayer player) {
        State state = STATES.get(player.getEntityId());
        if (state == null || !state.uuid.equals(player.getUniqueID().toString())) {
            state = new State(player);
            STATES.put(player.getEntityId(), state);
        }
        return state;
    }

    private static boolean supported(Packet<?> packet) {
        return packet instanceof S14PacketEntity
                || packet instanceof S19PacketEntityHeadLook
                || packet instanceof S18PacketEntityTeleport
                || packet instanceof S18PacketEntityTeleport
                || packet instanceof S0BPacketAnimation
                || packet instanceof S19PacketEntityStatus;
    }

    private static void apply(PacketReceiveEvent.Apply event) {
        Minecraft client = Minecraft.getMinecraft();
        if (!listening
                || writer == null
                || writer.closing()
                || client.theWorld != level
                || event.listener() != connection
                || client.thePlayer == null) return;
        Packet<?> packet = event.packet();
        if (packet instanceof S13PacketDestroyEntities remove) {
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
        // 1.8 has no damage-source packet. A hurt status only opens a nearby combat window;
        // it never labels a particular player as the confirmed attacker in the dataset.
        if (packet instanceof S19PacketEntityStatus damage && damage.getOpCode() == 2) {
            Entity victim = damage.getEntity(level);
            if (victim instanceof EntityPlayer v && STATES.containsKey(v.getEntityId())) {
                for (EntityPlayer nearby : level.playerEntities)
                    if (nearby != v
                            && STATES.containsKey(nearby.getEntityId())
                            && nearby.getDistanceSqToEntity(v) <= 16
                            && nearby.isSwingInProgress) {
                        capture.combat(
                                nearby.getUniqueID().toString(),
                                nearby.getEntityId(),
                                v.getUniqueID().toString(),
                                v.getEntityId(),
                                now,
                                COMBAT_SECONDS.get() * 1_000_000_000L,
                                writer::offer);
                    }
            }
            return;
        }
        Entity entity;
        if (packet instanceof S14PacketEntity p) entity = p.getEntity(level);
        else if (packet instanceof S19PacketEntityHeadLook p) entity = p.getEntity(level);
        else if (packet instanceof S18PacketEntityTeleport p)
            entity = level.getEntityByID(p.getEntityId());
        else if (packet instanceof S0BPacketAnimation p)
            entity = level.getEntityByID(p.getEntityID());
        else return;
        if (!(entity instanceof EntityPlayer player)
                || !player.isEntityAlive()
                || player.isSpectator()) return;
        if (!STATES.containsKey(player.getEntityId())) return;
        State state = state(player);
        boolean position = false, rotation = false, head = false, discontinuity = false;
        int action = -1, damageTarget = -1;
        if (packet instanceof S14PacketEntity p) {
            position =
                    p instanceof S14PacketEntity.S15PacketEntityRelMove
                            || p instanceof S14PacketEntity.S17PacketEntityLookMove;
            rotation = p.func_149060_h();
            // APPLY precedes vanilla's mutation; use its exact packet codec base, not render lerp.
            if (position) state.position = PacketAccess.decodeEntityDelta(p, player);
            if (rotation) {
                state.yaw = p.func_149066_f() * 360F / 256F;
                state.pitch = p.func_149063_g() * 360F / 256F;
            }
            state.ground = p.getOnGround();
        } else if (packet instanceof S19PacketEntityHeadLook p) {
            state.head = p.getYaw() * 360F / 256F;
            head = true;
        } else if (packet instanceof S18PacketEntityTeleport p) {
            state.position = PacketAccess.syncPosition(p);
            state.yaw = PacketAccess.syncYaw(p);
            state.pitch = PacketAccess.syncPitch(p);
            state.ground = p.getOnGround();
            position = rotation = discontinuity = true;

        } else if (packet instanceof S0BPacketAnimation p) action = p.getAnimationType();
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
        if (player == client.thePlayer
                || state.position.squareDistanceTo(VecMath.position(client.thePlayer))
                        > RANGE.get() * RANGE.get()) return;
        var observer = snapshot(client, player, state);
        List<AimGeometry.Actor> candidates = new ArrayList<>();
        for (State candidate : STATES.values()) {
            Entity target = level.getEntityByID(candidate.id);
            if (!(target instanceof EntityPlayer other)
                    || other == player
                    || !other.isEntityAlive()
                    || other.isSpectator()) continue;
            Vec3 targetPosition =
                    other == client.thePlayer ? VecMath.position(other) : candidate.position;
            if (targetPosition.squareDistanceTo(state.position)
                    > Math.pow(TARGET_RANGE.get() + 2, 2)) continue;
            if (other == client.thePlayer)
                candidates.add(snapshot(client, other, new State(other)));
            else candidates.add(snapshot(client, other, candidate));
        }
        capture.observe(
                new AimGeometry.Sample(
                        ++sequence,
                        receipt == null ? -1 : receipt.nanos,
                        now,
                        receipt == null ? -1 : receipt.epochMillis,
                        wall,
                        level.getTotalWorldTime(),
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

    private static AimGeometry.Actor snapshot(Minecraft client, EntityPlayer player, State state) {
        Vec3 pos = state.position;
        if (player == client.thePlayer) pos = VecMath.position(player);
        var box =
                VecMath.move(player.getEntityBoundingBox(), pos.subtract(VecMath.position(player)));
        var info =
                client.getNetHandler() == null
                        ? null
                        : client.getNetHandler().getPlayerInfo(player.getUniqueID());
        return new AimGeometry.Actor(
                player.getEntityId(),
                state.uuid,
                point(pos),
                point(pos.addVector(0, player.getEyeHeight(), 0)),
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
                player.isSneaking(),
                player.isSprinting(),
                player.isSneaking()
                        ? "CROUCHING"
                        : player.isPlayerSleeping() ? "SLEEPING" : "STANDING",
                player == client.thePlayer,
                info == null ? -1 : info.getResponseTime());
    }

    private static AimGeometry.Point point(Vec3 value) {
        return new AimGeometry.Point(value.xCoord, value.yCoord, value.zCoord);
    }

    private record Receipt(long nanos, long epochMillis) {}

    private static final class State {
        final int id;
        final String uuid;
        Vec3 position;
        float yaw, pitch, head;
        boolean ground, rotationKnown, headKnown;
        long positionNanos = -1, rotationNanos = -1, headNanos = -1;

        State(EntityPlayer player) {
            id = player.getEntityId();
            uuid = player.getUniqueID().toString();
            position =
                    new Vec3(
                            player.serverPosX / 32D,
                            player.serverPosY / 32D,
                            player.serverPosZ / 32D);
            yaw = player.rotationYaw;
            pitch = player.rotationPitch;
            head = player.rotationYawHead;
            ground = player.onGround;
        }
    }
}
