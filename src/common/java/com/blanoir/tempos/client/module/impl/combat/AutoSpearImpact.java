package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.SubFeature;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.raytrace.LineOfSight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One predicted contact step followed by a horizontal brake; never generates attacks. */
final class AutoSpearImpact extends SubFeature<AutoSpear> {
    private static final double SEARCH_RANGE = 32.0;
    private final Pulse pulse = new Pulse();
    private LocalPlayer player;
    private ClientLevel level;
    private Item spear;
    private int slot;

    AutoSpearImpact(AutoSpear parent) {
        super(parent);
    }

    @Override
    public void registerEvents() {
        EventBus.PLAYER_UPDATE.register(
                "AutoSpear.impact", EventPriority.HIGH, event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoSpear.impactContext", event -> discard());
        EventBus.PACKET_RECEIVE_APPLY.register(
                "AutoSpear.impactInterrupt",
                event -> {
                    if (player == null
                            || event.listener() != Minecraft.getInstance().getConnection()) return;
                    var packet = event.packet();
                    if (packet instanceof ClientboundPlayerPositionPacket
                            || packet instanceof ClientboundSetEntityMotionPacket motion
                                    && motion.id() == player.getId()
                            || packet instanceof ClientboundDamageEventPacket damage
                                    && damage.entityId() == player.getId()
                            || MinecraftClientAccess.playerImpulse(packet, player.getId()).isPresent()) {
                        // Do not brake a new server velocity, correction or damage knockback.
                        discard();
                    }
                });
    }

    /** Called only after the existing STAB/sound/velocity correlation succeeded. */
    boolean arm(Minecraft client) {
        if (!parent().impactBurstEnabled()
                || !ClientReady.aliveGameplay(client)
                || !holdingSpear(client.player)) return false;
        stop(client);
        parent().flushDelayedPackets(client);
        player = client.player;
        level = client.level;
        spear = player.getMainHandItem().getItem();
        slot = player.getInventory().getSelectedSlot();
        pulse.arm(player.getDeltaMovement().horizontalDistance(), System.nanoTime());
        return true;
    }

    boolean active() {
        return player != null;
    }

    private boolean holdingSpear(LocalPlayer current) {
        return current.isUsingItem()
                && current.getUsedItemHand() == InteractionHand.MAIN_HAND
                && current.getMainHandItem().has(DataComponents.KINETIC_WEAPON);
    }

    private void tick(Minecraft client) {
        if (player == null) return;
        if (!ClientReady.world(client) || client.player != player || client.level != level) {
            discard();
            return;
        }
        if (player.isDeadOrDying() || player.isSpectator()) {
            discard();
            return;
        }
        // Brake on the next movement tick, even if use was released or the slot changed.
        Vec3 brake = pulse.brake(player.getDeltaMovement());
        if (brake != null) {
            player.setDeltaMovement(brake);
            discard();
            return;
        }
        if (!parent().isEnabled()
                || !parent().impactBurstEnabled()
                || !ClientReady.aliveGameplay(client)
                || !client.isWindowActive()
                || client.isPaused()
                || player.getInventory().getSelectedSlot() != slot
                || !player.getMainHandItem().is(spear)
                || !holdingSpear(player)
                || !pulse.waiting(System.nanoTime())) {
            discard();
            return;
        }
        var weapon = player.getMainHandItem().get(DataComponents.KINETIC_WEAPON);
        int used = player.getTicksUsingItem();
        if (used < weapon.delayTicks()) return;
        if (used > weapon.computeDamageUseDuration()) {
            discard();
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        var target =
                Targeting.findTargetOnRay(
                        client,
                        eye,
                        look,
                        SEARCH_RANGE,
                        entity ->
                                entity instanceof LivingEntity living
                                        && living != player
                                        && living.isAlive()
                                        && living.isAttackable()
                                        && !living.isSpectator()
                                        && (!(living instanceof Player other)
                                                || Targeting.isValidTargetPlayer(client, other)),
                        false);
        if (target == null) return;
        var range = player.getAttackRangeWith(player.getMainHandItem());
        Vec3 burst =
                plan(
                        eye,
                        look,
                        player.getDeltaMovement(),
                        target.getBoundingBox().move(target.getKnownSpeed()),
                        range.effectiveMinRange(player),
                        range.effectiveMaxRange(player),
                        pulse.speed * parent().impactMultiply());
        if (burst == null) return;
        // Reject paths through obstacles, including walls below the eye ray.
        if (!client.level.noCollision(
                player, player.getBoundingBox().expandTowards(burst.x, 0, burst.z))) return;
        Vec3 nextEye = eye.add(burst);
        if (!LineOfSight.canRayTraceTo(client, eye, nextEye)) return;
        if (pulse.fire(System.nanoTime())) player.setDeltaMovement(burst);
    }

    /** Cap the step before the target, and require the predicted endpoint to be in spear reach. */
    static Vec3 plan(
            Vec3 eye,
            Vec3 look,
            Vec3 current,
            AABB target,
            double minReach,
            double maxReach,
            double desiredSpeed) {
        double horizontal = current.horizontalDistance();
        if (horizontal < .01
                || !Double.isFinite(desiredSpeed)
                || desiredSpeed <= horizontal
                || maxReach <= minReach + .5) return null;
        Vec3 direction = new Vec3(current.x / horizontal, 0, current.z / horizontal);
        double alignment = direction.dot(look);
        if (alignment < .95) return null;
        var hit = target.clip(eye, eye.add(look.scale(SEARCH_RANGE)));
        if (hit.isEmpty()) return null;
        double gap = eye.distanceTo(hit.get());
        double step = Math.min(desiredSpeed, (gap - minReach - .5) / alignment);
        if (step <= horizontal) return null;
        Vec3 motion = new Vec3(direction.x * step, current.y, direction.z * step);
        Vec3 endEye = eye.add(motion);
        if (target.contains(endEye)) return null;
        var endHit =
                target.clip(
                        endEye.add(look.scale(minReach + .1)),
                        endEye.add(look.scale(maxReach - .1)));
        return endHit.isPresent() ? motion : null;
    }

    /** Explicit disable/settings changes stop our outstanding pulse in the same context. */
    void stop(Minecraft client) {
        if (player != null && client != null && client.player == player && client.level == level) {
            Vec3 brake = pulse.brake(player.getDeltaMovement());
            if (brake != null) player.setDeltaMovement(brake);
        }
        discard();
    }

    private void discard() {
        pulse.clear();
        player = null;
        level = null;
        spear = null;
        slot = -1;
    }

    static final class Pulse {
        private double speed;
        private long deadline;
        private boolean armed;
        private boolean braking;

        void arm(double initialSpeed, long now) {
            clear();
            speed = initialSpeed;
            deadline = now + 750_000_000L;
            armed = true;
        }

        boolean waiting(long now) {
            return armed && now <= deadline;
        }

        boolean fire(long now) {
            if (!waiting(now)) return false;
            armed = false;
            braking = true;
            return true;
        }

        Vec3 brake(Vec3 current) {
            if (!braking) return null;
            clear();
            return new Vec3(0, current.y, 0);
        }

        void clear() {
            speed = 0;
            deadline = 0;
            armed = false;
            braking = false;
        }
    }
}
