package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.combat.PickResultEvent;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.event.render.EntityRenderStateEvent;
import com.blanoir.moons.client.manager.network.DelayedValueQueue;
import com.blanoir.moons.client.manager.targeting.Targeting;
import com.blanoir.moons.client.module.impl.combat.misplace.MisplaceLatencyModel;
import com.blanoir.moons.client.module.impl.combat.misplace.MisplaceMotion;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

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
    private static WorldClient level;
    private static EntityPlayer player;
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
                && client.getNetHandler() != null
                && !client.thePlayer.isSpectator()
                && !client.thePlayer.isRiding()
                && !client.isGamePaused()
                && client.getRenderViewEntity() == client.thePlayer
                && !Backtrack.isEnabled()
                && !FakeLag.isEnabled();
    }

    private static boolean context(Minecraft client) {
        if (!ready(client)) {
            reset();
            return false;
        }
        if (level != client.theWorld || player != client.thePlayer) {
            reset();
            level = client.theWorld;
            player = client.thePlayer;
        }
        return true;
    }

    private static boolean eligible(Minecraft client, Entity entity) {
        return entity instanceof EntityPlayer target
                && target.getDistanceSqToEntity(client.thePlayer) <= 144
                && !target.isRiding()
                && !target.isPlayerSleeping()
                && Targeting.isEnemyPlayer(client, target);
    }

    private static void tick(Minecraft client) {
        if (!context(client)) return;
        long now = DelayedValueQueue.nowMillis();
        TRACKS.values()
                .removeIf(
                        track ->
                                level.getEntityByID(track.entity.getEntityId()) != track.entity
                                        || !eligible(client, track.entity));
        for (EntityPlayer target : level.playerEntities) {
            if (TRACKS.size() >= 64) break;
            if (eligible(client, target)) track(target, now);
        }
        update(client);
    }

    private static void sent(PacketSendEvent.Post event) {
        if (!(event.packet() instanceof C03PacketPlayer move)) return;
        Minecraft client = Minecraft.getMinecraft();
        // Normal movement is sent on the client thread. Queued/artificial lag is paused.
        if (!client.isCallingFromMinecraftThread()
                || !context(client)
                || event.connection() != client.getNetHandler().getNetworkManager()) return;
        if (move.isMoving()) {
            sentPosition = new Vec3(move.getPositionX(), move.getPositionY(), move.getPositionZ());
        }
        // Rotation-only packets preserve the last transmitted coordinate.
        if (sentPosition != null) sentAt = DelayedValueQueue.nowMillis();
    }

    private static Track track(EntityPlayer entity, long now) {
        Track existing = TRACKS.get(entity.getEntityId());
        if (existing != null && existing.entity == entity) return existing;
        if (existing == null && TRACKS.size() >= 64) return null;
        Track created = new Track(entity);
        created.motion.reset(
                new Vec3(
                        entity.serverPosX / 32.0,
                        entity.serverPosY / 32.0,
                        entity.serverPosZ / 32.0),
                now);
        TRACKS.put(entity.getEntityId(), created);
        return created;
    }

    private static void receive(PacketReceiveEvent.Apply event) {
        Minecraft client = Minecraft.getMinecraft();
        if (!context(client) || event.listener() != client.getNetHandler()) return;
        var packet = event.packet();
        long now = DelayedValueQueue.nowMillis();
        if (packet instanceof S08PacketPlayerPosLook) {
            reset();
            return;
        }
        if (packet instanceof S13PacketDestroyEntities remove) {
            for (int id : remove.getEntityIDs()) TRACKS.remove(id);
        } else if (packet instanceof S18PacketEntityTeleport teleport) {
            if (level.getEntityByID(teleport.getEntityId()) instanceof EntityPlayer target
                    && eligible(client, target)) {
                Track track = track(target, now);
                if (track != null) {
                    Vec3 at =
                            new Vec3(
                                    teleport.getX() / 32.0,
                                    teleport.getY() / 32.0,
                                    teleport.getZ() / 32.0);
                    track.motion.discontinuity(at, now);
                    track.offset = VecMath.ZERO;
                }
            }
        } else if (packet instanceof S19PacketEntityStatus damage) {
            if (KNOCKBACK.get()
                    && damage.getOpCode() == 2
                    && damage.getEntity(level) instanceof EntityPlayer target
                    && eligible(client, target)) {
                Track track = track(target, now);
                if (track != null) track.motion.impact(now, ping(client, target), 50, JITTER.get());
            }
        } else {
            Entity entity = null;
            Vec3 position = null;
            boolean discontinuity = false;
            if (packet instanceof S14PacketEntity move
                    && (move instanceof S14PacketEntity.S15PacketEntityRelMove
                            || move instanceof S14PacketEntity.S17PacketEntityLookMove)) {
                entity = move.getEntity(level);
                // APPLY precedes vanilla: deltas are signed bytes in 1/32-block units.
                if (entity != null)
                    position =
                            new Vec3(
                                    (entity.serverPosX + move.func_149062_c()) / 32.0,
                                    (entity.serverPosY + move.func_149061_d()) / 32.0,
                                    (entity.serverPosZ + move.func_149064_e()) / 32.0);
            }
            if (entity instanceof EntityPlayer target && eligible(client, target)) {
                Track track = track(target, now);
                if (track == null) return;
                if (discontinuity) {
                    track.motion.discontinuity(position, now);
                    track.offset = VecMath.ZERO;
                } else track.motion.observe(position, now);
            }
        }
    }

    private static void update(Minecraft client) {
        if (!context(client)) return;
        double nearest = Double.POSITIVE_INFINITY;
        modelStatus = "Warmup";
        for (Track track : TRACKS.values()) {
            track.offset = VecMath.ZERO;
            if (!eligible(client, track.entity)) continue;
            refreshTrack(client, track);
            var estimate = track.motion.estimate();
            double distance = track.entity.getDistanceSqToEntity(player);
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
        var original = new CombatGeometry.Shape(entity.getEntityBoundingBox(), VecMath.ZERO);
        if (!context(client) || !eligible(client, entity)) return original;
        Track track = track((EntityPlayer) entity, DelayedValueQueue.nowMillis());
        if (track == null) return original;
        var shifted = refreshTrack(client, track);
        return shifted.shifted() ? shifted : original;
    }

    private static CombatGeometry.Shape refreshTrack(Minecraft client, Track track) {
        float partial = MinecraftClientAccess.framePartialTick(client);
        Vec3 observer = player.getPositionEyes(partial);
        Vec3 visible = VecMath.position(track.entity, partial);
        AxisAlignedBB visibleBox =
                VecMath.move(
                        track.entity.getEntityBoundingBox(),
                        visible.subtract(VecMath.position(track.entity)));
        Vec3 source =
                sentPosition == null ? null : sentPosition.addVector(0, player.getEyeHeight(), 0);
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
                        DelayedValueQueue.nowMillis());
        double amount =
                track.motion.advance(
                        query, DISTANCE.get(), ADAPTIVE.get(), KNOCKBACK.get(), SMOOTHING.get());
        Vec3 offset = MisplaceMotion.offset(observer, visible, amount);
        // Warmup, stale/uncertain motion and disabled pull all produce zero displacement.
        // Collision enumeration cannot change that result and becomes costly in crowds.
        track.offset =
                VecMath.lengthSqr(offset) == 0
                        ? VecMath.ZERO
                        : level.getCollidingBoundingBoxes(
                                                track.entity, VecMath.move(visibleBox, offset))
                                        .isEmpty()
                                ? offset
                                : VecMath.ZERO;
        return new CombatGeometry.Shape(VecMath.move(visibleBox, track.offset), track.offset);
    }

    private static void render(EntityRenderStateEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (!ready(client) || level != client.theWorld || player != client.thePlayer) return;
        Track track = TRACKS.get(event.entity().getEntityId());
        if (track == null || track.entity != event.entity() || !eligible(client, event.entity()))
            return;
        var state = event.state();
        state.x += track.offset.xCoord;
        state.z += track.offset.zCoord;
        state.distanceToCameraSq =
                client.getRenderViewEntity().getDistanceSq(state.x, state.y, state.z);
    }

    private static void pick(PickResultEvent event) {
        Minecraft client = event.client();
        if (!context(client) || TRACKS.isEmpty()) return;
        // Pick is the display/interaction update boundary. Repeating the same full-player
        // update from FRAME adds collision queries after extraction on newer versions.
        update(client);
        boolean shifted =
                TRACKS.values().stream()
                        .anyMatch(track -> VecMath.lengthSqr(track.offset) > 1.0E-9);
        if (!shifted) return;
        float partial = MinecraftClientAccess.framePartialTick(client);
        Vec3 start = player.getPositionEyes(partial);
        double range = CombatReach.vanillaEntityInteractionRange(player);
        Vec3 end = start.add(VecMath.scale(player.getLook(partial), range));
        MovingObjectPosition best =
                player.rayTrace(client.playerController.getBlockReachDistance(), partial);
        double nearest =
                Math.min(
                        range * range,
                        best == null || best.typeOfHit == MovingObjectPosition.MovingObjectType.MISS
                                ? Double.MAX_VALUE
                                : start.squareDistanceTo(best.hitVec));
        AxisAlignedBB search =
                VecMath.inflate(
                        new AxisAlignedBB(
                                Math.min(start.xCoord, end.xCoord),
                                Math.min(start.yCoord, end.yCoord),
                                Math.min(start.zCoord, end.zCoord),
                                Math.max(start.xCoord, end.xCoord),
                                Math.max(start.yCoord, end.yCoord),
                                Math.max(start.zCoord, end.zCoord)),
                        DISTANCE.get() + 1);
        for (Entity entity :
                level.getEntitiesInAABBexcluding(
                        player,
                        search,
                        entity ->
                                entity.canBeCollidedWith()
                                        && !(entity
                                                        instanceof
                                                        net.minecraft.entity.player.EntityPlayer
                                                                spectator
                                                && spectator.isSpectator()))) {
            if (entity == player.ridingEntity || entity.ridingEntity == player) continue;
            Track track = TRACKS.get(entity.getEntityId());
            Vec3 offset = track != null && track.entity == entity ? track.offset : VecMath.ZERO;
            AxisAlignedBB box =
                    VecMath.move(
                            VecMath.move(
                                    VecMath.inflate(
                                            entity.getEntityBoundingBox(),
                                            entity.getCollisionBorderSize()),
                                    VecMath.position(entity, partial)
                                            .subtract(VecMath.position(entity))),
                            offset);
            Vec3 hit = MisplaceMotion.intersection(box, start, end);
            if (hit == null || start.squareDistanceTo(hit) >= nearest) continue;
            // Test both the displayed contact and its original position against world cover.
            if (!RaytraceUtils.canRayTraceTo(client, start, hit)
                    || !RaytraceUtils.canRayTraceTo(client, start, hit.subtract(offset))) continue;
            nearest = start.squareDistanceTo(hit);
            best = new MovingObjectPosition(entity, hit);
        }
        event.result(best);
        client.pointedEntity =
                best != null && best.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                        ? best.entityHit
                        : null;
    }

    private static int ping(Minecraft client, EntityPlayer entity) {
        var info = client.getNetHandler().getPlayerInfo(entity.getUniqueID());
        return info == null ? -1 : Math.clamp(info.getResponseTime(), 0, 2000);
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
        final EntityPlayer entity;
        final MisplaceMotion motion = new MisplaceMotion();
        Vec3 offset = VecMath.ZERO;

        Track(EntityPlayer entity) {
            this.entity = entity;
        }
    }
}
