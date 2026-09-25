package com.blanoir.moons.client.module.impl.combat;

import net.minecraft.world.phys.Vec3;

public final class AutoMaceVerification {
    public static void main(String[] args) {
        Vec3 origin = new Vec3(10, 64, 10);
        Vec3 eye = origin.add(0, 1.62, 0);
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 impact = AutoMaceCycle.firstImpact(eye, Vec3.ZERO, up, 0);
        require(
                impact != null && impact.y > origin.y + 2.5 && impact.y < origin.y + 3.2,
                "Immediate upward pearl hits the wind high enough for a smash");
        require(
                AutoMaceCycle.firstImpact(eye, Vec3.ZERO, up, 1) == null,
                "Waiting one tick loses the wind: never implement this as a delayed throw");
        require(
                AutoMaceCycle.firstImpact(eye, new Vec3(.2, 0, -.1), up, 0) != null,
                "Both projectiles inherit the grounded player's horizontal motion");

        var cycle = new AutoMaceCycle();
        cycle.begin();
        require(!cycle.confirmTeleport(impact), "Rotation preparation cannot arm a strike");
        cycle.launched(origin, impact);
        require(
                !cycle.confirmTeleport(impact),
                "Local upward movement is not teleport confirmation");
        cycle.positionReply();
        require(!cycle.confirmTeleport(origin), "A ground correction cannot arm a strike");
        require(!cycle.confirmTeleport(impact), "An invalid reply cannot later confirm a jump");
        cycle.positionReply();
        require(!cycle.confirmTeleport(impact.add(8, 0, 0)), "Reject unrelated sideways teleports");
        cycle.positionReply();
        require(cycle.confirmTeleport(impact), "A matching airborne server teleport arms the fall");
        require(
                cycle.phase() == AutoMaceCycle.Phase.FALL,
                "Advance to falling only after confirmation");
        require(!cycle.confirmTeleport(impact), "Consume each teleport once");
        for (int i = 0; i < 60; i++) require(!cycle.expired(), "Allow the bounded fall window");
        require(cycle.expired(), "A missing landing must not hold the weapon forever");

        cycle.reset();
        cycle.positionReply();
        cycle.begin();
        cycle.launched(origin, impact);
        require(!cycle.confirmTeleport(impact), "Disable/context loss discard stale replies");
        for (int i = 0; i < 40; i++) require(!cycle.expired(), "Allow network response time");
        require(cycle.expired(), "Missing or rejected pearl must time out without a strike");
        cycle.begin();
        for (int i = 0; i < 20; i++) require(!cycle.expired(), "Allow rotation confirmation");
        require(cycle.expired(), "Blocked rotations must release the cycle");
        cycle.reset();
        require(!cycle.active() && !cycle.expired(), "Reset releases all transaction state");
        System.out.println("MOONS_AUTOMACE_VERIFIED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
