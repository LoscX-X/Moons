package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.access.PacketAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.combat.PickResultEvent;
import com.blanoir.tempos.client.event.network.PacketReceiveEvent;
import com.blanoir.tempos.client.event.network.PacketSendEvent;
import com.blanoir.tempos.client.event.render.EntityRenderStateEvent;
import com.blanoir.tempos.client.manager.network.DelayedValueQueue;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.combat.misplace.MisplaceLatencyModel;
import com.blanoir.tempos.client.module.impl.combat.misplace.MisplaceMotion;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.combat.CombatShape;
import com.blanoir.tempos.client.utils.raytrace.LineOfSight;

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
import java.util.function.BooleanSupplier;

/** Local model/pick displacement. Entity positions, packet codecs and physics stay authoritative. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Adjusts the local position used to display and select a target.")
public final class Misplace extends ClientFeature {
    public static final Misplace INSTANCE = new Misplace();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("misplace.enabled").defaultValue(false).build();
    private final BooleanSetting adaptive =
            new BooleanSetting.Builder().name("misplace.adaptive").defaultValue(true).build();
    private final BooleanSetting knockback =
            new BooleanSetting.Builder().name("misplace.knockback").defaultValue(true).build();
    private final DoubleSetting distance =
            new DoubleSetting.Builder()
                    .name("misplace.distance")
                    .defaultValue(.4)
                    .range(0, 1.5)
                    .build();
    private final IntSetting prediction =
            new IntSetting.Builder()
                    .name("misplace.predictionMs")
                    .defaultValue(400)
                    .range(0, 1000)
                    .build();
    private final IntSetting jitter =
            new IntSetting.Builder()
                    .name("misplace.jitterMs")
                    .defaultValue(15)
                    .range(0, 100)
                    .build();
    private final IntSetting smoothing =
            new IntSetting.Builder()
                    .name("misplace.smoothingMs")
                    .defaultValue(60)
                    .range(0, 200)
                    .build();
    private final Map<Integer, Track> tracks = new HashMap<>();
    private ClientLevel level;
    private Player player;
    private Vec3 sentPosition;
    private long sentAt;
    private String modelStatus = "Warmup";

    private BooleanSupplier networkDelay = () -> false;

    public void setNetworkDelayCheck(BooleanSupplier check) {
        networkDelay = check;
    }

    private Misplace() {
        super("misplace", "Misplace [Beta]");
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("Misplace.context", event -> reset());
        EventBus.TICK_END.register("Misplace.motion", event -> tick(event.client()));
        EventBus.PACKET_RECEIVE_APPLY.register("Misplace.packet", this::receive);
        EventBus.PACKET_SEND_POST.register("Misplace.source", this::sent);
        EventBus.ENTITY_RENDER_STATE.register("Misplace.model", this::render);
        EventBus.PICK_RESULT.register("Misplace.pick", this::pick);
    }

    private boolean ready(Minecraft client) {
        return this.enabled.get()
                && ClientReady.aliveGameplay(client)
                && client.getConnection() != null
                && !client.player.isSpectator()
                && !client.player.isPassenger()
                && !client.isPaused()
                && client.getCameraEntity() == client.player
                && !networkDelay.getAsBoolean();
    }

    private boolean context(Minecraft client) {
        if (!ready(client)) {
            reset();
            return false;
        }
        if (this.level != client.level || this.player != client.player) {
            reset();
            this.level = client.level;
            this.player = client.player;
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

    private void tick(Minecraft client) {
        if (!context(client)) return;
        long now = DelayedValueQueue.nowMillis();
        this.tracks
                .values()
                .removeIf(
                        track ->
                                this.level.getEntity(track.entity.getId()) != track.entity
                                        || !eligible(client, track.entity));
        for (Player target : this.level.players()) {
            if (this.tracks.size() >= 64) break;
            if (eligible(client, target)) track(target, now);
        }
        update(client);
    }

    private void sent(PacketSendEvent.Post event) {
        if (!(event.packet() instanceof ServerboundMovePlayerPacket move)) return;
        Minecraft client = Minecraft.getInstance();
        // Normal movement is sent on the client thread. Queued/artificial lag is paused.
        if (!client.isSameThread()
                || !context(client)
                || event.connection() != client.getConnection().getConnection()) return;
        if (move.hasPosition()) {
            this.sentPosition = new Vec3(move.getX(0), move.getY(0), move.getZ(0));
        }
        // Rotation-only packets preserve the last transmitted coordinate.
        if (this.sentPosition != null) this.sentAt = DelayedValueQueue.nowMillis();
    }

    private Track track(Player entity, long now) {
        Track existing = this.tracks.get(entity.getId());
        if (existing != null && existing.entity == entity) return existing;
        if (existing == null && this.tracks.size() >= 64) return null;
        Track created = new Track(entity);
        created.motion.reset(entity.getPositionCodec().getBase(), now);
        this.tracks.put(entity.getId(), created);
        return created;
    }

    private void receive(PacketReceiveEvent.Apply event) {
        Minecraft client = Minecraft.getInstance();
        if (!context(client) || event.listener() != client.getConnection()) return;
        var packet = event.packet();
        long now = DelayedValueQueue.nowMillis();
        if (packet instanceof ClientboundPlayerPositionPacket) {
            reset();
            return;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int id : PacketAccess.removedEntityIds(remove)) this.tracks.remove(id);
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (this.level.getEntity(teleport.id()) instanceof Player target
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
            if (this.knockback.get()
                    && this.level.getEntity(damage.entityId()) instanceof Player target
                    && eligible(client, target)) {
                Track track = track(target, now);
                if (track != null)
                    track.motion.impact(now, ping(client, target), 50, this.jitter.get());
            }
        } else {
            Entity entity = null;
            Vec3 position = null;
            boolean discontinuity = false;
            if (packet instanceof ClientboundMoveEntityPacket move && move.hasPosition()) {
                entity = move.getEntity(this.level);
                if (entity != null)
                    position = PacketAccess.decodeEntityDelta(move, entity.getPositionCodec());
            } else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
                entity = this.level.getEntity(sync.id());
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

    private void update(Minecraft client) {
        if (!context(client)) return;
        double nearest = Double.POSITIVE_INFINITY;
        this.modelStatus = "Warmup";
        for (Track track : this.tracks.values()) {
            track.offset = Vec3.ZERO;
            if (!eligible(client, track.entity)) continue;
            refreshTrack(client, track);
            var estimate = track.motion.estimate();
            double distance = track.entity.distanceToSqr(this.player);
            if (estimate != null && distance < nearest) {
                nearest = distance;
                this.modelStatus =
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
    public CombatShape attackShape(Minecraft client, Entity entity) {
        var original = new CombatShape(entity.getBoundingBox(), Vec3.ZERO);
        if (!context(client) || !eligible(client, entity)) return original;
        Track track = track((Player) entity, DelayedValueQueue.nowMillis());
        if (track == null) return original;
        var shifted = refreshTrack(client, track);
        return shifted.shifted() ? shifted : original;
    }

    private CombatShape refreshTrack(Minecraft client, Track track) {
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 observer = this.player.getEyePosition(partial);
        Vec3 visible = track.entity.getPosition(partial);
        AABB visibleBox =
                track.entity.getBoundingBox().move(visible.subtract(track.entity.position()));
        Vec3 source =
                this.sentPosition == null
                        ? null
                        : this.sentPosition.add(0, this.player.getEyeHeight(), 0);
        var network =
                new MisplaceLatencyModel.Network(
                        ping(client, this.player),
                        ping(client, track.entity),
                        50,
                        this.jitter.get(),
                        this.prediction.get());
        var query =
                new MisplaceLatencyModel.Query(
                        source,
                        this.sentAt,
                        observer,
                        visible,
                        visibleBox,
                        network,
                        CombatReach.vanillaEntityInteractionRange(this.player),
                        DelayedValueQueue.nowMillis());
        double amount =
                track.motion.advance(
                        query,
                        this.distance.get(),
                        this.adaptive.get(),
                        this.knockback.get(),
                        this.smoothing.get());
        Vec3 offset = MisplaceMotion.offset(observer, visible, amount);
        // Warmup, stale/uncertain motion and disabled pull all produce zero displacement.
        // Collision enumeration cannot change that result and becomes costly in crowds.
        track.offset =
                offset.lengthSqr() == 0
                        ? Vec3.ZERO
                        : this.level.noCollision(track.entity, visibleBox.move(offset))
                                ? offset
                                : Vec3.ZERO;
        return new CombatShape(visibleBox.move(track.offset), track.offset);
    }

    private void render(EntityRenderStateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!ready(client) || this.level != client.level || this.player != client.player) return;
        Track track = this.tracks.get(event.entity().getId());
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

    private void pick(PickResultEvent event) {
        Minecraft client = event.client();
        if (!context(client) || this.tracks.isEmpty()) return;
        // Pick is the display/interaction update boundary. Repeating the same full-player
        // update from FRAME adds collision queries after extraction on newer versions.
        update(client);
        boolean shifted =
                this.tracks.values().stream().anyMatch(track -> track.offset.lengthSqr() > 1.0E-9);
        if (!shifted) return;
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 start = this.player.getEyePosition(partial);
        double range = CombatReach.vanillaEntityInteractionRange(this.player);
        Vec3 end = start.add(this.player.getViewVector(partial).scale(range));
        HitResult best =
                this.player.pick(
                        this.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE),
                        partial,
                        false);
        double nearest =
                Math.min(
                        range * range,
                        best.getType() == HitResult.Type.MISS
                                ? Double.MAX_VALUE
                                : start.distanceToSqr(best.getLocation()));
        AABB search = new AABB(start, end).inflate(this.distance.get() + 1);
        for (Entity entity :
                this.level.getEntities(this.player, search, EntitySelector.CAN_BE_PICKED)) {
            if (entity.getRootVehicle() == this.player.getRootVehicle()) continue;
            Track track = this.tracks.get(entity.getId());
            Vec3 offset = track != null && track.entity == entity ? track.offset : Vec3.ZERO;
            AABB box =
                    entity.getBoundingBox()
                            .inflate(entity.getPickRadius())
                            .move(entity.getPosition(partial).subtract(entity.position()))
                            .move(offset);
            Vec3 hit = MisplaceMotion.intersection(box, start, end);
            if (hit == null || start.distanceToSqr(hit) >= nearest) continue;
            // Test both the displayed contact and its original position against world cover.
            if (!LineOfSight.canRayTraceTo(client, start, hit)
                    || !LineOfSight.canRayTraceTo(client, start, hit.subtract(offset))) continue;
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

    private void reset() {
        this.tracks.clear();
        this.level = null;
        this.player = null;
        this.sentPosition = null;
        this.sentAt = 0;
        this.modelStatus = "Warmup";
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public String statusTag() {
        if (networkDelay.getAsBoolean()) return "Paused";
        return this.adaptive.get() ? this.modelStatus : "Fixed";
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        reset();
        this.enabled.set(value);
        ClientChat.send(client, "Misplace " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setAdaptive(Minecraft client, boolean value) {
        this.adaptive.set(value);
        reset();
        return 1;
    }

    public int setKnockback(Minecraft client, boolean value) {
        this.knockback.set(value);
        reset();
        return 1;
    }

    public int setDistance(Minecraft client, double value) {
        this.distance.set(value);
        reset();
        return 1;
    }

    public int setPrediction(Minecraft client, int value) {
        this.prediction.set(value);
        reset();
        return 1;
    }

    public int setSmoothing(Minecraft client, int value) {
        this.smoothing.set(value);
        return 1;
    }

    public int setJitter(Minecraft client, int value) {
        this.jitter.set(value);
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

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            adaptive.describeStored("adaptive", "Dynamic offset", this::setAdaptive),
            distance.describeStored("distance", "Maximum offset", .05, this::setDistance),
            prediction.describeStored(
                    "prediction", "Prediction limit (ms)", 10, this::setPrediction),
            jitter.describeStored("jitter", "Timing uncertainty (ms)", 5, this::setJitter),
            knockback.describeStored("knockback", "Track knockback timing", this::setKnockback),
            smoothing.describeStored("smoothing", "Smoothing (ms)", 10, this::setSmoothing)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor()
                .withHudTag(
                        this::statusTag,
                        "Warmup",
                        "In range*",
                        "Out of range*",
                        "KB pending",
                        "Delay limit",
                        "Uncertain",
                        "Paused",
                        "Fixed");
    }
}
