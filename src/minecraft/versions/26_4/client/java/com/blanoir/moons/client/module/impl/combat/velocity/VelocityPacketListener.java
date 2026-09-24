package com.blanoir.moons.client.module.impl.combat.velocity;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.module.impl.combat.Velocity;
import com.blanoir.moons.client.module.impl.movement.JumpReset;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Version-specific decoding and reconstruction for the shared Velocity modes. */
public final class VelocityPacketListener {
    private static final Set<Packet<?>> PASSTHROUGH =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private VelocityPacketListener() {}

    public static void init() {
        EventBus.PACKET_RECEIVE_PRE.register(
                "Velocity.packetReceive", VelocityPacketListener::receive);
    }

    public static void initJumpReset() {
        EventBus.PACKET_RECEIVE_APPLY.register(
                "JumpReset.velocityApplied",
                event -> {
                    if (event.packet() instanceof ClientboundSetEntityMotionPacket motion) {
                        JumpReset.handleEntityVelocity(motion.id(), motion.movement());
                    }
                });
    }

    private static void receive(PacketReceiveEvent.Pre event) {
        Packet<?> packet = event.packet();
        if (PASSTHROUGH.remove(packet) || event.bundleExpansionRequested()) return;
        if (packet instanceof ClientboundDamageEventPacket damage) {
            Velocity.handleDamageEvent(damage.entityId(), damage.sourceCauseId());
        } else if (packet instanceof ClientboundEntityEventPacket status) {
            Velocity.handleEntityStatus(GameAccess.entityEventId(status), status.getEventId());
        }
        Packet<?> replacement = velocityReplacement(packet);
        if (replacement != null) {
            // Re-enter the existing receive path so earlier observers see the replacement too.
            event.cancel();
            PASSTHROUGH.add(replacement);
            try {
                GameAccess.dispatchIncoming(replacement, event.listener());
            } finally {
                PASSTHROUGH.remove(replacement);
            }
        }
    }

    private static Packet<?> velocityReplacement(Packet<?> packet) {
        if (!Velocity.normalMode()) return null;
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            Vec3 transformed = Velocity.transformEntityVelocity(motion.id(), motion.movement());
            return transformed == null
                    ? null
                    : new ClientboundSetEntityMotionPacket(motion.id(), transformed);
        }
        if (packet instanceof ClientboundExplodePacket explosion
                && explosion.playerKnockback().isPresent()) {
            Vec3 transformed =
                    Velocity.transformExplosionVelocity(explosion.playerKnockback().orElseThrow());
            return transformed == null
                    ? null
                    : new ClientboundExplodePacket(
                            explosion.center(),
                            explosion.radius(),
                            explosion.blockCount(),
                            java.util.Optional.of(transformed),
                            explosion.explosionParticle(),
                            explosion.explosionSound(),
                            explosion.blockParticles(),
                            explosion.playSound());
        }
        return null;
    }
}
