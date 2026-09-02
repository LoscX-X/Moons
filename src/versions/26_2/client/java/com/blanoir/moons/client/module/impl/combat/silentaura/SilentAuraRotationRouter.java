package com.blanoir.moons.client.module.impl.combat.silentaura;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Routes calls without sharing mutable aiming state between aim modes. */
final class SilentAuraRotationRouter {
    private final SilentAuraRotationController lock =
            new SilentAuraRotationController(true);
    private final SilentAuraRotationController balance =
            new SilentAuraRotationController(false);
    private final SilentAuraRotationController fullLock =
            new SilentAuraRotationController(true, true);

    private SilentAuraRotationController activeController() {
        return SilentAuraConfig.fullLockMode() ? fullLock
                : SilentAuraConfig.lockMode() ? lock : balance;
    }

    void track(Minecraft client, LivingEntity target, Vec3 point, double deltaSeconds) {
        activeController().track(client, target, point, deltaSeconds);
    }

    void returnToCamera(Minecraft client, double deltaSeconds) {
        activeController().returnToCamera(client, deltaSeconds);
    }

    boolean returnPacketReached(float sentYaw, float sentPitch) {
        return activeController().returnPacketReached(sentYaw, sentPitch);
    }

    void completeReturn() { activeController().completeReturn(); }
    void cancelReturn(Minecraft client) { activeController().cancelReturn(client); }
    boolean active() { return activeController().active(); }
    boolean returning() { return activeController().returning(); }
    int targetId() { return activeController().targetId(); }
    float yaw() { return activeController().yaw(); }
    float pitch() { return activeController().pitch(); }
    boolean crossingTarget() { return activeController().crossingTarget(); }
    float bodyYaw() { return activeController().bodyYaw(); }
    Vec3 lookVector() { return activeController().lookVector(); }

    void clear() {
        lock.clear();
        balance.clear();
        fullLock.clear();
    }
}
