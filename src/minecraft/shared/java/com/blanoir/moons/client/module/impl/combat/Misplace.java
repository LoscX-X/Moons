package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.combat.PickResultEvent;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.event.render.EntityRenderStateEvent;
import com.blanoir.moons.client.management.network.LagUtils;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.impl.combat.misplace.MisplaceLatencyModel;
import com.blanoir.moons.client.module.impl.combat.misplace.MisplaceMotion;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Local model/pick displacement. Entity positions, packet codecs and physics stay authoritative. */
public final class Misplace {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("misplace.enabled").defaultValue(false).build();
    private static final BooleanSetting ADAPTIVE =
            new BooleanSetting.Builder().name("misplace.adaptive").defaultValue(true).build();
    private static final BooleanSetting KNOCKBACK =
            new BooleanSetting.Builder().name("misplace.knockback").defaultValue(true).build();
    private static final DoubleSetting DISTANCE =
            new DoubleSetting.Builder()
                    .name("misplace.distance")
                    .defaultValue(.4)
                    .range(0, 1.5)
                    .build();
    private static final IntSetting PREDICTION =
            new IntSetting.Builder()
                    .name("misplace.predictionMs")
                    .defaultValue(400)
                    .range(0, 1000)
                    .build();
    private static final IntSetting JITTER =
            new IntSetting.Builder()
                    .name("misplace.jitterMs")
                    .defaultValue(15)
                    .range(0, 100)
                    .build();
    private static final IntSetting SMOOTHING =
            new IntSetting.Builder()
                    .name("misplace.smoothingMs")
                    .defaultValue(60)
                    .range(0, 200)
                    .build();
    private static final Map<Integer, Track> TRACKS = new HashMap<>();
    private static ClientLevel level;
    private static Player player;
    private static Vec3 sentPosition;
    private static long sentAt;
    private static String modelStatus = "Warmup";

    private Misplace() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("Misplace.context", event -> reset());
        EventBus.TICK_END.register("Misplace.motion", event -> tick(event.client()));
        EventBus.PACKET_RECEIVE_APPLY.register("Misplace.packet", Misplace::receive);
        EventBus.PACKET_SEND_POST.register("Misplace.source", Misplace::sent);
        EventBus.ENTITY_RENDER_STATE.register("Misplace.model", Misplace::render);
        EventBus.PICK_RESULT.register("Misplace.pick", Misplace::pick);
    }

    private static boolean ready(Minecraft client) {
        return ENABLED.get()
                && ClientReady.aliveGameplay(client)
                && client.getConnection() != null
                && !client.player.isSpectator()
                && !client.player.isPassenger()
                && !client.isPaused()
                && client.getCameraEntity() == client.player
                && !Backtrack.isEnabled()
                && !FakeLag.isEnabled();
    }

    private static boolean context(Minecraft client) {
        if (!ready(client)) {
            reset();
            return false;
        }
        if (level != client.level || player != client.player) {
            reset();
            level = client.level;
            player = client.player;
        }
        return true;
    }

    private static boolean eligible(Minecraft client, Entity entity) {
        return entity instanceof Player target
                && target.distanceToSqr(client.player) <= 144
                && !target.isPassenger()
                && !target.isSleeping()
                && Targeting.isEnemyPlayer(client, target);
    }

    private static void tick(Minecraft client) {
        if (!context(client)) return;
        long now = LagUtils.nowMillis();
        TRACKS.values()
                .removeIf(
                        track ->
                                level.getEntity(track.entity.getId()) != track.entity
                                        || !eligible(client, track.entity));
        for (Player target : level.players()) {
            if (TRACKS.size() >= 64) break;
            if (eligible(client, target)) track(target, now);
        }
        update(client);
    }

    private static void sent(PacketSendEvent.Post event) {
        if (!(event.packet() instanceof ServerboundMovePlayerPacket move)) return;
        Minecraft client = Minecraft.getInstance();
        // Normal movement is sent on the client thread. Queued/artificial lag is paused.
        if (!client.isSameThread()
                || !context(client)
                || event.connection() != client.getConnection().getConnection()) return;
        if (move.hasPosition()) {
            sentPosition = new Vec3(move.getX(0), move.getY(0), move.getZ(0));
        }
        // Rotation-only packets preserve the last transmitted coordinate.
        if (sentPosition != null) sentAt = LagUtils.nowMillis();
    }

    private static Track track(Player entity, long now) {
        Track existing = TRACKS.get(entity.getId());
        if (existing != null && existing.entity == entity) return existing;
        if (existing == null && TRACKS.size() >= 64) return null;
        Track created = new Track(entity);
        created.motion.reset(entity.getPositionCodec().getBase(), now);
        TRACKS.put(entity.getId(), created);
        return created;
    }

    private static void receive(PacketReceiveEvent.Apply event) {
        Minecraft client = Minecraft.getInstance();
        if (!context(client) || event.listener() != client.getConnection()) return;
        var packet = event.packet();
        long now = LagUtils.nowMillis();
        if (packet instanceof ClientboundPlayerPositionPacket) {
            reset();
            return;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int id : PacketAccess.removedEntityIds(remove)) TRACKS.remove(id);
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (level.getEntity(teleport.id()) instanceof Player target
                    && eligible(client, target)) {
                Track track = track(target, now);
                if (track != null) {
                    Vec3 at =
                            PositionMoveRotation.calculateAbsolute(
                                            PositionMoveRotation.of(target),
                                            teleport.change(),
                                            teleport.relatives())
                                    .position();
                    track.motion.discontinuity(at, now);
                    track.offset = Vec3.ZERO;
                }
            }
        } else if (packet instanceof ClientboundDamageEventPacket damage) {
            if (KNOCKBACK.get()
                    && level.getEntity(damage.entityId()) instanceof Player target
                    && eligible(client, target)) {
                Track track = track(target, now);
                if (track != null) track.motion.impact(now, ping(client, target), 50, JITTER.get());
            }
        } else {
            Entity entity = null;
            Vec3 position = null;
            boolean discontinuity = false;
            if (packet instanceof ClientboundMoveEntityPacket move && move.hasPosition()) {
                entity = move.getEntity(level);
                if (entity != null)
                    position = PacketAccess.decodeEntityDelta(move, entity.getPositionCodec());
            } else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
                entity = level.getEntity(sync.id());
                position = PacketAccess.syncPosition(sync);
                discontinuity = true;
            }
            if (entity instanceof Player target && eligible(client, target)) {
                Track track = track(target, now);
                if (track == null) return;
                if (discontinuity) {
                    track.motion.discontinuity(position, now);
                    track.offset = Vec3.ZERO;
                } else track.motion.observe(position, now);
            }
        }
    }

    private static void update(Minecraft client) {
        if (!context(client)) return;
        double nearest = Double.POSITIVE_INFINITY;
        modelStatus = "Warmup";
        for (Track track : TRACKS.values()) {
            track.offset = Vec3.ZERO;
            if (!eligible(client, track.entity)) continue;
            refreshTrack(client, track);
            var estimate = track.motion.estimate();
            double distance = track.entity.distanceToSqr(player);
            if (estimate != null && distance < nearest) {
                nearest = distance;
                modelStatus =
                        switch (estimate.verdict()) {
                            case IN_RANGE -> "In range*";
                            case OUT_OF_RANGE -> "Out of range*";
                            case KNOCKBACK_TRANSITION -> "KB pending";
                            case HORIZON_EXCEEDED -> "Delay limit";
                            case UNCERTAIN -> "Uncertain";
                            default -> "Warmup";
                        };
            }
        }
    }

    /** Reuses the exact display translation without changing the entity's packet/physics state. */
    public static CombatGeometry.Shape attackShape(Minecraft client, Entity entity) {
        var original = new CombatGeometry.Shape(entity.getBoundingBox(), Vec3.ZERO);
        if (!context(client) || !eligible(client, entity)) return original;
        Track track = track((Player) entity, LagUtils.nowMillis());
        if (track == null) return original;
        var shifted = refreshTrack(client, track);
        return shifted.shifted() ? shifted : original;
    }

    private static CombatGeometry.Shape refreshTrack(Minecraft client, Track track) {
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 observer = player.getEyePosition(partial);
        Vec3 visible = track.entity.getPosition(partial);
        AABB visibleBox =
                track.entity.getBoundingBox().move(visible.subtract(track.entity.position()));
        Vec3 source = sentPosition == null ? null : sentPosition.add(0, player.getEyeHeight(), 0);
        var network =
                new MisplaceLatencyModel.Network(
                        ping(client, player),
                        ping(client, track.entity),
                        50,
                        JITTER.get(),
                        PREDICTION.get());
        var query =
                new MisplaceLatencyModel.Query(
                        source,
                        sentAt,
                        observer,
                        visible,
                        visibleBox,
                        network,
                        CombatReach.vanillaEntityInteractionRange(player),
                        LagUtils.nowMillis());
        double amount =
                track.motion.advance(
                        query, DISTANCE.get(), ADAPTIVE.get(), KNOCKBACK.get(), SMOOTHING.get());
        Vec3 offset = MisplaceMotion.offset(observer, visible, amount);
        // Warmup, stale/uncertain motion and disabled pull all produce zero displacement.
        // Collision enumeration cannot change that result and becomes costly in crowds.
        track.offset =
                offset.lengthSqr() == 0
                        ? Vec3.ZERO
                        : level.noCollision(track.entity, visibleBox.move(offset))
                                ? offset
                                : Vec3.ZERO;
        return new CombatGeometry.Shape(visibleBox.move(track.offset), track.offset);
    }

    private static void render(EntityRenderStateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!ready(client) || level != client.level || player != client.player) return;
        Track track = TRACKS.get(event.entity().getId());
        if (track == null || track.entity != event.entity() || !eligible(client, event.entity()))
            return;
        var state = event.state();
        state.x += track.offset.x;
        state.z += track.offset.z;
        state.distanceToCameraSq =
                MinecraftClientAccess.camera(client)
                        .position()
                        .distanceToSqr(state.x, state.y, state.z);
    }

    private static void pick(PickResultEvent event) {
        Minecraft client = event.client();
        if (!context(client) || TRACKS.isEmpty()) return;
        // Pick is the display/interaction update boundary. Repeating the same full-player
        // update from FRAME adds collision queries after extraction on newer versions.
        update(client);
        boolean shifted =
                TRACKS.values().stream().anyMatch(track -> track.offset.lengthSqr() > 1.0E-9);
        if (!shifted) return;
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 start = player.getEyePosition(partial);
        double range = CombatReach.vanillaEntityInteractionRange(player);
        Vec3 end = start.add(player.getViewVector(partial).scale(range));
        HitResult best =
                player.pick(
                        player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE),
                        partial,
                        false);
        double nearest =
                Math.min(
                        range * range,
                        best.getType() == HitResult.Type.MISS
                                ? Double.MAX_VALUE
                                : start.distanceToSqr(best.getLocation()));
        AABB search = new AABB(start, end).inflate(DISTANCE.get() + 1);
        for (Entity entity : level.getEntities(player, search, EntitySelector.CAN_BE_PICKED)) {
            if (entity.getRootVehicle() == player.getRootVehicle()) continue;
            Track track = TRACKS.get(entity.getId());
            Vec3 offset = track != null && track.entity == entity ? track.offset : Vec3.ZERO;
            AABB box =
                    entity.getBoundingBox()
                            .inflate(entity.getPickRadius())
                            .move(entity.getPosition(partial).subtract(entity.position()))
                            .move(offset);
            Vec3 hit = MisplaceMotion.intersection(box, start, end);
            if (hit == null || start.distanceToSqr(hit) >= nearest) continue;
            // Test both the displayed contact and its original position against world cover.
            if (!RaytraceUtils.canRayTraceTo(client, start, hit)
                    || !RaytraceUtils.canRayTraceTo(client, start, hit.subtract(offset))) continue;
            nearest = start.distanceToSqr(hit);
            best = new EntityHitResult(entity, hit);
        }
        event.result(best);
        client.crosshairPickEntity = best instanceof EntityHitResult hit ? hit.getEntity() : null;
    }

    private static int ping(Minecraft client, Player entity) {
        var info = client.getConnection().getPlayerInfo(entity.getUUID());
        return info == null ? -1 : Math.clamp(info.getLatency(), 0, 2000);
    }

    private static void reset() {
        TRACKS.clear();
        level = null;
        player = null;
        sentPosition = null;
        sentAt = 0;
        modelStatus = "Warmup";
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusTag() {
        if (Backtrack.isEnabled() || FakeLag.isEnabled()) return "Paused";
        return ADAPTIVE.get() ? modelStatus : "Fixed";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        reset();
        ENABLED.set(value);
        ClientChat.send(client, "Misplace " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setAdaptive(Minecraft client, boolean value) {
        ADAPTIVE.set(value);
        reset();
        return 1;
    }

    public static int setKnockback(Minecraft client, boolean value) {
        KNOCKBACK.set(value);
        reset();
        return 1;
    }

    public static int setDistance(Minecraft client, double value) {
        DISTANCE.set(value);
        reset();
        return 1;
    }

    public static int setPrediction(Minecraft client, int value) {
        PREDICTION.set(value);
        reset();
        return 1;
    }

    public static int setSmoothing(Minecraft client, int value) {
        SMOOTHING.set(value);
        return 1;
    }

    public static int setJitter(Minecraft client, int value) {
        JITTER.set(value);
        reset();
        return 1;
    }

    private static final class Track {
        final Player entity;
        final MisplaceMotion motion = new MisplaceMotion();
        Vec3 offset = Vec3.ZERO;

        Track(Player entity) {
            this.entity = entity;
        }
    }
}
