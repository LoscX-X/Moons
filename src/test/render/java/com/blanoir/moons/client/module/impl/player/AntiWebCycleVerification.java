package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.management.rotation.SilentPacketRotation;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class AntiWebCycleVerification {
    public static void main(String[] args) throws Exception {
        var guard = new AntiWebCycleGuard();
        Object turn = new Object(), wait = new Object();
        for (int tick = 0; tick < 30; tick++)
            require(!guard.expired(turn, 2, 0), "Rotation budget");
        require(guard.expired(turn, 2, 0), "Missing callback times out");
        guard.reset();
        for (int tick = 0; tick < 160; tick++) {
            require(!guard.expired(tick % 2 == 0 ? turn : wait, 2, 0), "Cycle remains bounded");
        }
        require(guard.expired(turn, 2, 0), "Repeated rotation retries cannot extend whole cycle");
        guard.reset();
        for (int tick = 0; tick < 50; tick++)
            require(!guard.expired(wait, 20, 10), "Configured hold has time");
        require(guard.expired(wait, 20, 10), "Even longest phase ends");

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var slot = AntiWeb.class.getDeclaredField("activeWaterSlot");
        slot.setAccessible(true);
        slot.setInt(null, 2);
        var active = SilentPacketRotation.class.getDeclaredField("holdingRotation");
        active.setAccessible(true);
        active.setBoolean(null, true);
        require(AntiWeb.isBusy(), "Fixture owns an unfinished cycle");
        AntiWeb.shutdown(null);
        require(
                !AntiWeb.isBusy() && !SilentPacketRotation.isBusy(),
                "Shutdown releases cycle and rotation");
        System.out.println("MOONS_ANTIWEB_CYCLE_VERIFIED");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
