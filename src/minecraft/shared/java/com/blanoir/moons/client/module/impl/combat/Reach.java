package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.network.EntityLag;
import com.blanoir.moons.client.management.network.PacketBlink;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Manual extended-ray attacks. Advanced mode stores the exact attack/swing
 * packet objects and lags only the target's incoming movement packets. The
 * logical entity remains at the attack position while rendering follows the
 * decoded server position. The saved packet pair is released after normal
 * movement brings the player within vanilla range of the lagged entity box.
 */
public final class Reach {
    private static final double RANGE_SAFETY_MARGIN = 0.05D;
    private static final PacketBlink ATTACK_BLINK = new PacketBlink(2);
    private static final EntityLag TARGET_LAG = new EntityLag(256);

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("reach.enabled").defaultValue(false).build();

    private static final StringSetting MODE =
            new StringSetting.Builder().name("reach.mode").defaultValue("advanced").build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("reach.range")
                    .defaultValue(3.1D)
                    .range(3.0D, 6.0D)
                    .build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("reach.normal.chance")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static final IntSetting TIMEOUT =
            new IntSetting.Builder()
                    .name("reach.advanced.timeout")
                    .defaultValue(20)
                    .range(1, 40)
                    .build();

    private static boolean initialized;
    private static boolean expanding = true;
    private static int remainingTicks;
    private static double displayedRealDistance = -1.0D;
    private static double displayedCurrentDistance = -1.0D;
    private static int distanceDisplayTicks;

    private Reach() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_BUNDLE.register(
                "Reach.bundleExpansion",
                event -> {
                    if (requiresBundleExpansion()) event.requestExpansion();
                });
        EventBus.PACKET_RECEIVE_PRE.register(
                "Reach.packetReceive",
                event -> {
                    if (!event.bundleExpansionRequested()
                            && handleIncomingPacket(event.packet(), event.listener())) {
                        event.cancel();
                    }
                });
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.TICK.register(
                "Reach.sampleExpansionChance",
                event -> {
                    expanding = RandomMath.chancePercent(CHANCE.get());
                });
        // Tick end is after LocalPlayer has sent this tick's movement. Releasing
        // here preserves movement -> old attack packet ordering on the wire.
        EventBus.TICK_END.register("Reach.tick", event -> tick(event.client()));
    }

    /**
     * Called at the head of Minecraft.startAttack. Returning true consumes the
     * vanilla action because this module either sent or stored its packet pair.
     */
    public static boolean handleManualAttack(Minecraft client) {
        if (!ClientReady.gameplay(client) || !ENABLED.get()) {
            clearPending();
            return false;
        }
        // Normal mode owns only the pick/ray distances. The vanilla attack
        // path consumes that extended hit result without packet substitution.
        if (!advancedMode()) {
            clearPending();
            return false;
        }

        Entity target = manualRayTarget(client);
        if (target == null) {
            clearPending();
            return false;
        }

        boolean vanillaHit =
                client.hitResult instanceof EntityHitResult hit && hit.getEntity() == target;
        if (vanillaHit && withinSafeRange(client, target)) {
            clearPending();
            return false;
        }

        Packet<?> attack = new ServerboundAttackPacket(target.getId());
        Packet<?> swing = PacketAccess.swingPacket(InteractionHand.MAIN_HAND);
        store(client, target, attack, swing);
        return true;
    }

    /**
     * On enabled chance ticks, Normal mode uses Range for entity picking and
     * Range + 0.5 for the ray/block query that bounds that pick.
     */
    public static void applyNormalPick(Minecraft client) {
        if (!ClientReady.gameplay(client) || !ENABLED.get() || advancedMode() || !expanding) {
            return;
        }
        double entityRange = RANGE.get();
        Vec3 start = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0F);
        EntityHitResult extendedHit =
                RaytraceUtils.findEntity(
                        client,
                        start,
                        start.add(look.scale(entityRange + 0.5D)),
                        entityRange,
                        false,
                        EntitySelector.CAN_BE_PICKED);
        if (extendedHit != null) {
            client.hitResult = extendedHit;
            client.crosshairPickEntity = extendedHit.getEntity();
        }
    }

    /** True while Advanced needs bundle sub-packets exposed to its lag filter. */
    public static boolean requiresBundleExpansion() {
        return ENABLED.get() && advancedMode() && TARGET_LAG.active();
    }

    /**
     * Lags movement for only the pending target. The packet is still decoded
     * into EntityLag's real position so rendering follows the server without advancing
     * the logical entity used by the attack-range test.
     */
    public static boolean handleIncomingPacket(Packet<?> packet, PacketListener listener) {
        if (!requiresBundleExpansion()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client == null ? null : client.level;
        Entity target = TARGET_LAG.target(level);
        return TARGET_LAG.capture(packet, level, target);
    }

    /** Moves only the extracted render state; the target entity remains lagged. */
    public static void applyAdvancedRender(Entity entity, EntityRenderState state) {
        if (!ENABLED.get()
                || !advancedMode()
                || entity == null
                || state == null
                || !TARGET_LAG.matches(entity)) return;
        Vec3 renderPosition = TARGET_LAG.renderPosition();
        if (renderPosition == null) {
            return;
        }
        state.x = renderPosition.x;
        state.y = renderPosition.y;
        state.z = renderPosition.z;
    }

    private static void tick(Minecraft client) {
        if (!TARGET_LAG.active()) {
            if (distanceDisplayTicks > 0) {
                distanceDisplayTicks--;
            }
            return;
        }
        if (!ClientReady.gameplay(client) || !ENABLED.get() || !advancedMode()) {
            clearPending();
            return;
        }
        if (--remainingTicks <= 0) {
            clearPending();
            return;
        }

        Entity target = TARGET_LAG.target(client.level);
        if (!validTarget(client, target)) {
            clearPending();
            return;
        }
        updateDisplayedDistances(client, target);
        if (!withinPendingRange(client) || client.player.getAttackStrengthScale(0.5F) <= 0.9F) {
            return;
        }

        List<Packet<?>> attacks = ATTACK_BLINK.drain();
        if (attacks.size() != 2) {
            clearPending();
            return;
        }
        sendBlink(client, attacks);
        clearPending();
    }

    private static Entity manualRayTarget(Minecraft client) {
        if (client.hitResult instanceof EntityHitResult hit
                && validTarget(client, hit.getEntity())) {
            return hit.getEntity();
        }
        return Targeting.findTargetOnRay(
                client,
                client.player.getEyePosition(),
                client.player.getViewVector(1.0F),
                RANGE.get(),
                entity -> validTarget(client, entity),
                false);
    }

    private static boolean validTarget(Minecraft client, Entity entity) {
        if (!(entity instanceof LivingEntity living)
                || living == client.player
                || !living.isAlive()
                || !living.isAttackable()
                || living.isSpectator()) {
            return false;
        }
        return !(living instanceof Player) || Targeting.isEnemyPlayer(client, living);
    }

    private static boolean withinSafeRange(Minecraft client, Entity target) {
        double range = safeInteractionRange(client);
        return range > 0.0D && EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    private static boolean withinPendingRange(Minecraft client) {
        double range = safeInteractionRange(client);
        AABB laggedBox = TARGET_LAG.laggedBox();
        return range > 0.0D
                && laggedBox != null
                && EntityDistance.squaredToBox(client.player.getEyePosition(), laggedBox)
                        <= range * range;
    }

    private static double safeInteractionRange(Minecraft client) {
        return Math.max(
                0.0D,
                CombatReach.vanillaEntityInteractionRange(client.player) - RANGE_SAFETY_MARGIN);
    }

    private static void store(Minecraft client, Entity target, Packet<?> attack, Packet<?> swing) {
        clearPending();
        TARGET_LAG.begin(target);
        ATTACK_BLINK.offer(attack);
        ATTACK_BLINK.offer(swing);
        remainingTicks = TIMEOUT.get();
        updateDisplayedDistances(client, target);
    }

    /**
     * Real is measured to the decoded server AABB. Current is measured to the
     * lagged logical AABB used by Advanced's release test.
     */
    private static void updateDisplayedDistances(Minecraft client, Entity target) {
        var currentPlayer = client == null ? null : client.player;
        AABB realBox = TARGET_LAG.realBox();
        AABB laggedBox = TARGET_LAG.laggedBox();
        if (client == null
                || currentPlayer == null
                || target == null
                || realBox == null
                || laggedBox == null) {
            return;
        }
        displayedRealDistance =
                Math.sqrt(EntityDistance.squaredToBox(currentPlayer.getEyePosition(), realBox));
        displayedCurrentDistance =
                Math.sqrt(EntityDistance.squaredToBox(currentPlayer.getEyePosition(), laggedBox));
        distanceDisplayTicks = 40;
    }

    private static void sendBlink(Minecraft client, List<Packet<?>> packets) {
        packets.forEach(client.player.connection::send);
        // The stored swing packet animates other clients; this overload updates
        // only the local arm and cannot create a second outgoing swing packet.
        MinecraftClientAccess.swingAttackLocally(client.player, InteractionHand.MAIN_HAND);
        client.player.resetAttackStrengthTicker();
    }

    private static void clearPending() {
        ATTACK_BLINK.clear();
        remainingTicks = 0;
        replayLaggedTargetPackets(TARGET_LAG.stop());
    }

    @SuppressWarnings("unchecked")
    private static void replayLaggedTargetPackets(List<Packet<?>> packets) {
        if (packets.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientGamePacketListener listener = client == null ? null : client.getConnection();
        if (client == null || listener == null || client.level == null) {
            return;
        }
        for (Packet<?> packet : packets) {
            try {
                ((Packet<ClientGamePacketListener>) packet).handle(listener);
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean advancedMode() {
        return "advanced".equals(MODE.get().toLowerCase(Locale.ROOT));
    }

    public static List<String> modeOptions() {
        return List.of("normal", "advanced");
    }

    public static String statusTag() {
        if (!advancedMode()) {
            return format(RANGE.get());
        }
        if (distanceDisplayTicks > 0
                && displayedRealDistance >= 0.0D
                && displayedCurrentDistance >= 0.0D) {
            return "real:"
                    + formatDistance(displayedRealDistance)
                    + " current:"
                    + formatDistance(displayedCurrentDistance);
        }
        return "Advanced";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        clearPending();
        if (!value) {
            clearDisplayedDistances();
        }
        return showStatus(client);
    }

    public static int setMode(Minecraft client, String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!modeOptions().contains(normalized)) {
            return showStatus(client);
        }
        MODE.set(normalized);
        clearPending();
        clearDisplayedDistances();
        return showStatus(client);
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        clearPending();
        return showStatus(client);
    }

    public static int setChance(Minecraft client, double value) {
        CHANCE.set(value);
        return showStatus(client);
    }

    public static int setTimeout(Minecraft client, int value) {
        TIMEOUT.set(value);
        clearPending();
        return showStatus(client);
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Reach: "
                        + (ENABLED.get() ? "enabled" : "disabled")
                        + ", mode: "
                        + (advancedMode() ? "Advanced" : "Normal")
                        + ", range: "
                        + format(RANGE.get())
                        + (advancedMode()
                                ? ", target lag timeout: " + TIMEOUT.get() + "t"
                                : ", chance: " + format(CHANCE.get()) + "%")
                        + ". Usage: .moons reach <enable|disable|mode normal|advanced|range 3-6|chance 0-100|timeout 1-40>.");
        return 1;
    }

    private static String format(double value) {
        return Math.rint(value) == value
                ? Long.toString(Math.round(value))
                : Double.toString(value);
    }

    private static String formatDistance(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void clearDisplayedDistances() {
        displayedRealDistance = -1.0D;
        displayedCurrentDistance = -1.0D;
        distanceDisplayTicks = 0;
    }
}
