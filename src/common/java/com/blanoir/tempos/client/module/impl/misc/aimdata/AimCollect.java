package com.blanoir.tempos.client.module.impl.misc.aimdata;

import com.blanoir.tempos.client.access.PacketAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.network.PacketReceiveEvent;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

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
@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description = "Records observations of nearby players for aim analysis.")
public final class AimCollect extends ClientFeature {
    public static final AimCollect INSTANCE = new AimCollect();

    private boolean enabled;
    public final IntSetting range =
            new IntSetting.Builder()
                    .name("aimcollect.range")
                    .defaultValue(32)
                    .range(8, 128)
                    .build();
    public final IntSetting targetRange =
            new IntSetting.Builder()
                    .name("aimcollect.targetRange")
                    .defaultValue(8)
                    .range(2, 32)
                    .build();
    public final IntSetting combatSeconds =
            new IntSetting.Builder()
                    .name("aimcollect.combatSeconds")
                    .defaultValue(3)
                    .range(1, 5)
                    .build();
    private static final int MAX_PLAYERS = 64;
    private final Map<Packet<?>, Receipt> receipts = new IdentityHashMap<>();
    private final Map<Integer, State> states = new HashMap<>();
    private volatile boolean listening;
    private AimDatasetWriter writer;
    private ClientLevel level;
    private Object connection;
    private long sequence;
    private boolean terminalReported;
    private boolean restartRequested;
    private CombatCapture capture = new CombatCapture(2048);

    private AimCollect() {
        super("aimcollect", "AimCollect");
    }

    @Override
    public void registerEvents() {
        EventBus.PACKET_RECEIVE_PRE.register(
                "AimCollect.receipt",
                EventPriority.HIGHEST,
                event -> {
                    if (!this.listening) return;
                    Receipt receipt = new Receipt(System.nanoTime(), System.currentTimeMillis());
                    PacketAccess.forEachPacket(
                            event.packet(),
                            packet -> {
                                if (!supported(packet)) return;
                                synchronized (this.receipts) {
                                    if (!this.listening) return;
                                    if (this.receipts.size() >= 4096) this.receipts.clear();
                                    this.receipts.putIfAbsent(packet, receipt);
                                }
                            });
                });
        EventBus.PACKET_RECEIVE_APPLY.register("AimCollect.apply", this::apply);
        EventBus.TICK_END.register("AimCollect.tick", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AimCollect.context", event -> shutdown());
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    public static Path directory() {
        String configured = Settings.getString("aimcollect.directory", "");
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return Settings.file().getParent().resolve("datasets").resolve("aim");
    }

    public void setDirectory(Minecraft client, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute())
            throw new IllegalArgumentException("Use an absolute directory path.");
        path = path.normalize();
        shutdown();
        Settings.setString("aimcollect.directory", path.toString());
        this.restartRequested = this.enabled;
        this.terminalReported = false;
        ClientChat.send(
                client, "Collect directory: " + path + ". The next session uses this directory.");
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.enabled = enabled;
        shutdown();
        // Keep the old writer until its asynchronous drain completes.
        this.terminalReported = false;
        this.restartRequested = enabled;
        ClientChat.send(
                client,
                "AimCollect "
                        + (enabled ? "enabled: " + directory() : "saving")
                        + ". Dataset limit: 50 GB.");
        return 1;
    }

    public int setRange(Minecraft client, int value) {
        this.range.set(value);
        return 1;
    }

    public int setTargetRange(Minecraft client, int value) {
        this.targetRange.set(value);
        return 1;
    }

    public int setCombatSeconds(Minecraft client, int value) {
        this.combatSeconds.set(value);
        return 1;
    }

    public String hudTag() {
        return this.writer == null
                ? "Waiting"
                : this.writer.status()
                        + " | "
                        + this.writer.written()
                        + " | drop "
                        + this.writer.dropped()
                        + " | "
                        + (this.writer.used() / 1_000_000)
                        + " MB";
    }

    public String hudState() {
        var current = this.writer;
        if (current == null) return "Waiting";
        String status = current.status();
        if (status.startsWith("Error")) return "Error";
        if (status.startsWith("Limit")) return "Limit";
        if (status.equals("Saved")) return "Saved";
        if (current.closing()) return "Saving";
        return status.equals("Starting") ? "Starting" : "Recording";
    }

    public void shutdown() {
        this.listening = false;
        if (this.writer != null) this.writer.close();
        this.states.clear();
        this.capture = new CombatCapture(2048);
        synchronized (this.receipts) {
            this.receipts.clear();
        }
        this.level = null;
        this.connection = null;
    }

    private void tick(Minecraft client) {
        if (this.writer != null && this.writer.done()) {
            if (!this.terminalReported) {
                ClientChat.send(client, "AimCollect " + hudTag() + ". " + directory());
                this.terminalReported = true;
            }
            if (!this.writer.status().equals("Saved") && !this.restartRequested) {
                this.listening = false;
                return; // Explicit off/on is required after quota or I/O errors.
            }
            this.writer = null;
        }
        if (!this.enabled || client.level == null || client.player == null) {
            if (this.level != null) shutdown();
            return;
        }
        if (this.level != null
                && (this.level != client.level || this.connection != client.getConnection()))
            shutdown();
        if (this.writer != null && this.writer.closing()) return;
        if (this.writer == null) {
            this.writer = new AimDatasetWriter(directory());
            this.level = client.level;
            this.connection = client.getConnection();
            this.sequence = 0;
            this.terminalReported = false;
            this.restartRequested = false;
            this.listening = true;
        }
        // Limit retained state and seed candidates once per tick, never invent packet samples.
        List<? extends Player> nearby = nearby(client);
        this.states
                .keySet()
                .removeIf(id -> nearby.stream().noneMatch(player -> player.getId() == id));
        for (Player player : nearby) state(player);
        this.capture.retain(
                this.states.values().stream().map(state -> state.uuid).collect(Collectors.toSet()),
                System.nanoTime());
    }

    private List<? extends Player> nearby(Minecraft client) {
        double range = this.range.get() + this.targetRange.get();
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

    private State state(Player player) {
        State state = this.states.get(player.getId());
        if (state == null || !state.uuid.equals(player.getUUID().toString())) {
            state = new State(player);
            this.states.put(player.getId(), state);
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

    private void apply(PacketReceiveEvent.Apply event) {
        Minecraft client = Minecraft.getInstance();
        if (!this.listening
                || this.writer == null
                || this.writer.closing()
                || client.level != this.level
                || event.listener() != this.connection
                || client.player == null) return;
        Packet<?> packet = event.packet();
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            PacketAccess.removedEntityIds(remove).forEach(this.states::remove);
            return;
        }
        if (!supported(packet)) return;
        Receipt receipt;
        synchronized (this.receipts) {
            receipt = this.receipts.remove(packet);
        }
        long now = System.nanoTime();
        long wall = System.currentTimeMillis();
        // A swing alone is not evidence of combat. Only server-reported PvP damage arms recording.
        if (packet instanceof ClientboundDamageEventPacket damage) {
            Entity attacker = this.level.getEntity(damage.sourceCauseId());
            Entity victim = this.level.getEntity(damage.entityId());
            if (attacker instanceof Player a && victim instanceof Player v && a != v) {
                if (this.states.containsKey(a.getId()) && this.states.containsKey(v.getId()))
                    this.capture.combat(
                            a.getUUID().toString(),
                            a.getId(),
                            v.getUUID().toString(),
                            v.getId(),
                            now,
                            this.combatSeconds.get() * 1_000_000_000L,
                            this.writer::offer);
            }
        }
        Entity entity;
        if (packet instanceof ClientboundMoveEntityPacket p) entity = p.getEntity(this.level);
        else if (packet instanceof ClientboundRotateHeadPacket p) entity = p.getEntity(this.level);
        else if (packet instanceof ClientboundEntityPositionSyncPacket p)
            entity = this.level.getEntity(p.id());
        else if (packet instanceof ClientboundTeleportEntityPacket p)
            entity = this.level.getEntity(p.id());
        else if (packet instanceof ClientboundAnimatePacket p)
            entity = this.level.getEntity(p.getId());
        else if (packet instanceof ClientboundDamageEventPacket p)
            entity = this.level.getEntity(p.sourceCauseId());
        else return;
        if (!(entity instanceof Player player) || !player.isAlive() || player.isSpectator()) return;
        if (!this.states.containsKey(player.getId())) return;
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
                        > this.range.get() * this.range.get()) return;
        var observer = snapshot(client, player, state);
        List<AimDataGeometry.Actor> candidates = new ArrayList<>();
        for (State candidate : this.states.values()) {
            Entity target = this.level.getEntity(candidate.id);
            if (!(target instanceof Player other)
                    || other == player
                    || !other.isAlive()
                    || other.isSpectator()) continue;
            Vec3 targetPosition = other == client.player ? other.position() : candidate.position;
            if (targetPosition.distanceToSqr(state.position)
                    > Math.pow(this.targetRange.get() + 2, 2)) continue;
            if (other == client.player) candidates.add(snapshot(client, other, new State(other)));
            else candidates.add(snapshot(client, other, candidate));
        }
        this.capture.observe(
                new AimDataGeometry.Sample(
                        ++this.sequence,
                        receipt == null ? -1 : receipt.nanos,
                        now,
                        receipt == null ? -1 : receipt.epochMillis,
                        wall,
                        this.level.getGameTime(),
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
                        this.states.size() >= MAX_PLAYERS,
                        this.targetRange.get()),
                this.writer::offer);
    }

    private static AimDataGeometry.Actor snapshot(Minecraft client, Player player, State state) {
        Vec3 pos = state.position;
        if (player == client.player) pos = player.position();
        var box = player.getBoundingBox().move(pos.subtract(player.position()));
        var info =
                client.getConnection() == null
                        ? null
                        : client.getConnection().getPlayerInfo(player.getUUID());
        return new AimDataGeometry.Actor(
                player.getId(),
                state.uuid,
                point(pos),
                point(pos.add(0, player.getEyeHeight(), 0)),
                new AimDataGeometry.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
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

    private static AimDataGeometry.Point point(Vec3 value) {
        return new AimDataGeometry.Point(value.x, value.y, value.z);
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

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            this.range.describe("range", "Observer range", 8, this::setRange),
            this.targetRange.describe("target_range", "Target range", 1, this::setTargetRange),
            this.combatSeconds.describe(
                    "combat_seconds", "Combat timeout (s)", 1, this::setCombatSeconds)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor()
                .withHudTag(
                        this::hudState,
                        "Waiting",
                        "Starting",
                        "Recording",
                        "Saving",
                        "Saved",
                        "Limit",
                        "Error");
    }
}
