package com.blanoir.moons.client.event.network;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.combat.Velocity;
import com.blanoir.moons.client.module.impl.combat.Reach;
import com.blanoir.moons.client.module.impl.movement.JumpReset;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.module.impl.network.LowHealthFakeLag;
import com.blanoir.moons.client.module.impl.network.RandomFakeLag;
import com.blanoir.moons.client.module.impl.render.ScoreboardChanger;
import com.blanoir.moons.client.module.impl.world.FastBreak;
import com.blanoir.moons.client.module.impl.world.LightningTracker;
import com.blanoir.moons.client.management.combat.CriticalHitTracker;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Sole routing entry point for packet phases outside Mixins. PRE runs on the
 * Netty thread; handlers must only mutate their thread-safe queues. APPLY runs
 * on the client thread after vanilla's handoff check and may update game state.
 */
public final class PacketEventRouter {
    private static boolean initialized;
    private static final Set<Packet<?>> VELOCITY_PASSTHROUGH =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private PacketEventRouter() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        EventBus.PACKET_SEND_PRE.register("PacketEventRouter.packetSendPre", PacketEventRouter::onSendPre);
        EventBus.PACKET_SEND_POST.register("PacketEventRouter.packetSendPost", PacketEventRouter::onSendPost);
        EventBus.PACKET_RECEIVE_PRE.register("PacketEventRouter.packetReceivePre", PacketEventRouter::onReceivePre);
        EventBus.PACKET_RECEIVE_APPLY.register("PacketEventRouter.packetReceiveApply", PacketEventRouter::onReceiveApply);
    }

    private static void onSendPre(PacketSendEvent.Pre event) {
        if (event.packet() instanceof ServerboundAttackPacket attack) {
            CriticalHitTracker.recordAttackSendPre(event.packet(), attack.entityId());
        }
        if (FakeLag.handleOutgoing(event.connection(), event.packet())
                || LowHealthFakeLag.handleOutgoing(event.connection(), event.packet())
                || RandomFakeLag.handleOutgoing(event.connection(), event.packet())) {
            event.cancel();
            return;
        }
        FastBreak.handleOutgoingPacket(event.packet());
    }

    private static void onSendPost(PacketSendEvent.Post event) {
        if (event.packet() instanceof ServerboundAttackPacket attack) {
            CriticalHitTracker.recordAttackSendPost(event.packet(), attack.entityId());
        }
    }

    private static void onReceivePre(PacketReceiveEvent.Pre event) {
        Packet<?> packet = event.packet();
        boolean velocityBundle = packet instanceof ClientboundBundlePacket
                && Velocity.isEnabled() && Velocity.packetDrivenMode();
        boolean expandBundle = packet instanceof ClientboundBundlePacket
                && (Backtrack.isEnabled() || Reach.requiresBundleExpansion() || velocityBundle);

        if (!VELOCITY_PASSTHROUGH.remove(packet) && !expandBundle) {
            if (Velocity.handleJumpIncoming(packet)) {
                event.cancel();
                return;
            }
            if (packet instanceof ClientboundDamageEventPacket damage) {
                Velocity.handleDamageEvent(damage.entityId(), damage.sourceCauseId());
            } else if (packet instanceof ClientboundEntityEventPacket status) {
                Velocity.handleEntityStatus(GameAccess.entityEventId(status), status.getEventId());
            }
            Packet<?> replacement = velocityReplacement(packet);
            if (replacement != null) {
                event.cancel();
                VELOCITY_PASSTHROUGH.add(replacement);
                GameAccess.dispatchIncoming(replacement, event.listener());
                return;
            }
        }

        // Expanded sub-packets return through this event individually, so do
        // not feed the same packet object to lag modules twice.
        if (!expandBundle) {
            forEachPacket(packet, subPacket -> {
                FakeLag.handleIncoming(subPacket);
                LowHealthFakeLag.handleIncoming(subPacket);
                RandomFakeLag.handleIncoming(subPacket);
            });
        }

        if (expandBundle) {
            event.requestBundleExpansion();
        } else if (Reach.handleIncomingPacket(packet, event.listener())
                || Backtrack.isEnabled()
                && Backtrack.handleIncomingPacket(packet, event.listener())) {
            event.cancel();
        }
    }

    private static Packet<?> velocityReplacement(Packet<?> packet) {
        if (!Velocity.vanillaMode()) return null;
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            Vec3 transformed = Velocity.transformEntityVelocity(motion.id(), motion.movement());
            return transformed == null ? null
                    : new ClientboundSetEntityMotionPacket(motion.id(), transformed);
        }
        if (packet instanceof ClientboundExplodePacket explosion
                && explosion.playerKnockback().isPresent()) {
            Vec3 transformed = Velocity.transformExplosionVelocity(
                    explosion.playerKnockback().orElseThrow());
            return transformed == null ? null : new ClientboundExplodePacket(
                    explosion.center(), explosion.radius(), explosion.blockCount(),
                    java.util.Optional.of(transformed), explosion.explosionParticle(),
                    explosion.explosionSound(), explosion.blockParticles());
        }
        return null;
    }

    private static void onReceiveApply(PacketReceiveEvent.Apply event) {
        // Bundle handling invokes every sub-packet's own handler, and therefore
        // publishes a separate APPLY event for each object. Do not flatten here.
        ScoreboardChanger.onScoreboardPacketApplied(event.packet());
        if (event.packet() instanceof ClientboundDamageEventPacket damage) {
            CriticalHitTracker.confirmDamageApplied(
                    damage.entityId(), damage.sourceCauseId());
        }
        if (event.packet() instanceof ClientboundSetEntityMotionPacket motion) {
            JumpReset.handleEntityVelocity(motion.id(), motion.movement());
        } else if (event.packet() instanceof ClientboundAddEntityPacket addEntity) {
            LightningTracker.handlePacket(addEntity);
        }
    }

    private static void forEachPacket(Packet<?> packet, java.util.function.Consumer<Packet<?>> consumer) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(consumer);
        } else {
            consumer.accept(packet);
        }
    }
}
