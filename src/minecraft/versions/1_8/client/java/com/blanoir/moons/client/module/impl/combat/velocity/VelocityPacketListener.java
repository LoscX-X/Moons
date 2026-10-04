package com.blanoir.moons.client.module.impl.combat.velocity;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.module.impl.combat.Velocity;
import com.blanoir.moons.client.module.impl.movement.JumpReset;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Native 1.8.9 fixed-point velocity and additive explosion packet adapter. */
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
                    if (event.packet() instanceof S12PacketEntityVelocity motion)
                        JumpReset.handleEntityVelocity(motion.getEntityID(), movement(motion));
                });
    }

    private static Vec3 movement(S12PacketEntityVelocity packet) {
        return new Vec3(
                packet.getMotionX() / 8000.0,
                packet.getMotionY() / 8000.0,
                packet.getMotionZ() / 8000.0);
    }

    private static void receive(PacketReceiveEvent.Pre event) {
        Packet<?> packet = event.packet();
        if (PASSTHROUGH.remove(packet)) return;
        if (packet instanceof S19PacketEntityStatus status)
            Velocity.handleEntityStatus(GameAccess.entityEventId(status), status.getOpCode());
        Packet<?> replacement = replacement(packet);
        if (replacement == null) return;
        event.cancel();
        PASSTHROUGH.add(replacement);
        try {
            GameAccess.dispatchIncoming(replacement, event.listener());
        } finally {
            PASSTHROUGH.remove(replacement);
        }
    }

    private static Packet<?> replacement(Packet<?> packet) {
        if (!Velocity.normalMode()) return null;
        if (packet instanceof S12PacketEntityVelocity motion) {
            Vec3 adjusted =
                    Velocity.transformEntityVelocity(motion.getEntityID(), movement(motion));
            return adjusted == null
                    ? null
                    : new S12PacketEntityVelocity(
                            motion.getEntityID(),
                            adjusted.xCoord,
                            adjusted.yCoord,
                            adjusted.zCoord);
        }
        if (packet instanceof S27PacketExplosion explosion) {
            Vec3 adjusted =
                    Velocity.transformExplosionVelocity(
                            new Vec3(
                                    explosion.func_149149_c(),
                                    explosion.func_149144_d(),
                                    explosion.func_149147_e()));
            return adjusted == null
                    ? null
                    : new S27PacketExplosion(
                            explosion.getX(),
                            explosion.getY(),
                            explosion.getZ(),
                            explosion.getStrength(),
                            explosion.getAffectedBlockPositions(),
                            adjusted);
        }
        return null;
    }
}
