package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.utils.rotation.Rotation;

/**
 * One place selects the active mode's state owners; callers invoke the owners directly.
 * Array order is Lock, Balance, FullLock. Target history is also independent for Latest
 * and Legacy, while frame/packet history retains the original three-way separation.
 * Reset methods retain the original all-mode scope and iteration order.
 */
final class SilentAuraModes {
    private final SilentAuraTargets[] latest = targetsForModes();
    private final SilentAuraTargets[] legacy = targetsForModes();
    private final SilentAuraRotationController[] rotations = {
        new SilentAuraRotationController(true),
        new SilentAuraRotationController(false),
        new SilentAuraRotationController(true, true)
    };
    private final PacketRotationSmoother[] packets = {
        new PacketRotationSmoother(true),
        new PacketRotationSmoother(false),
        new PacketRotationSmoother(true, true)
    };

    private static SilentAuraTargets[] targetsForModes() {
        return new SilentAuraTargets[] {
            new SilentAuraTargets(true),
            new SilentAuraTargets(false),
            new SilentAuraTargets(true, true)
        };
    }

    private static int index() {
        return SilentAuraConfig.fullLockMode() ? 2 : SilentAuraConfig.lockMode() ? 0 : 1;
    }

    SilentAuraTargets targets() {
        return (SilentAuraConfig.legacyCombat() ? legacy : latest)[index()];
    }

    SilentAuraRotationController rotation() {
        return rotations[index()];
    }

    PacketRotationSmoother packets() {
        return packets[index()];
    }

    Rotation samplePacket(
            int tick,
            float cameraYaw,
            float cameraPitch,
            float desiredYaw,
            float desiredPitch,
            boolean lockMode,
            double sensitivity,
            boolean overlappingTarget,
            boolean matrixCompatibility) {
        PacketRotationSmoother smoother = packets[lockMode ? 0 : 1];
        if (SilentAuraConfig.fullLockMode()) smoother = packets[2];
        return smoother.sample(
                tick,
                cameraYaw,
                cameraPitch,
                desiredYaw,
                desiredPitch,
                sensitivity,
                overlappingTarget,
                matrixCompatibility,
                SilentAuraConfig.fullLockAngleStep(),
                SilentAuraConfig.fullLockSmoothing());
    }

    void clearTargets() {
        for (var target : latest) target.clear();
        for (var target : legacy) target.clear();
    }

    void clearRotations() {
        for (var rotation : rotations) rotation.clear();
    }

    void resetPackets() {
        for (var packet : packets) packet.reset();
    }
}
