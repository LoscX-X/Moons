package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.rotation.Rotation;

/** Keeps packet-cadence history independent for every aim mode. */
final class SilentAuraPacketRotationRouter {
    private final PacketRotationSmoother lock = new PacketRotationSmoother(true);
    private final PacketRotationSmoother balance = new PacketRotationSmoother(false);
    private final PacketRotationSmoother fullLock = new PacketRotationSmoother(true, true);

    private PacketRotationSmoother activeSmoother() {
        return SilentAuraConfig.fullLockMode() ? fullLock
                : SilentAuraConfig.lockMode() ? lock : balance;
    }

    Rotation sample(int tick, float cameraYaw, float cameraPitch,
                    float desiredYaw, float desiredPitch,
                    boolean lockMode, double sensitivity,
                    boolean overlappingTarget, boolean matrixCompatibility) {
        PacketRotationSmoother smoother = lockMode ? lock : balance;
        if (SilentAuraConfig.fullLockMode()) smoother = fullLock;
        return smoother.sample(tick, cameraYaw, cameraPitch,
                desiredYaw, desiredPitch, sensitivity, overlappingTarget,
                matrixCompatibility, SilentAuraConfig.fullLockAngleStep(),
                SilentAuraConfig.fullLockSmoothing());
    }

    void confirm(float yaw, float pitch) { activeSmoother().confirm(yaw, pitch); }
    void rebase(float yaw, float pitch) { activeSmoother().rebase(yaw, pitch); }

    void reset() {
        lock.reset();
        balance.reset();
        fullLock.reset();
    }
}
