package com.blanoir.moons.client.module.impl.combat;

import net.minecraft.world.phys.Vec3;

public final class AutoSpearMotionVerification {
    public static void main(String[] args) {
        var pending = new AutoSpearMotion.Pending();
        Vec3 origin = new Vec3(10, 64, 10);
        Vec3 motion = new Vec3(.8, -.12, -.4);

        pending.request(origin, 0);
        pending.velocity(motion, 2, 10);
        require(pending.take(motion, 11) == null, "No Lunge confirmation: do not scale knockback");

        pending.request(origin, 0);
        pending.confirm(origin.add(10, 0, 0), 10);
        pending.velocity(motion, 2, 11);
        require(pending.take(motion, 12) == null, "Distant player's Lunge cannot confirm ours");

        pending.request(origin, 0);
        pending.confirm(origin, 10);
        pending.velocity(motion, 2, 11);
        require(new Vec3(1.6, -.12, -.8).equals(pending.take(motion, 12)),
                "Scale horizontal motion once while preserving falling speed");
        require(pending.take(motion, 13) == null, "Never multiply on subsequent ticks");

        pending.request(origin, 0);
        pending.confirm(origin, 10);
        pending.velocity(motion, 2, 11);
        require(pending.take(motion.add(0, .1, 0), 12) == null,
                "Another movement change invalidates the boost");

        pending.request(origin, 0);
        pending.confirm(origin, 10);
        pending.velocity(motion, 2, 11);
        pending.velocity(new Vec3(0, .4, 0), 2, 12);
        require(pending.take(new Vec3(0, .4, 0), 13) == null,
                "A later self velocity replaces the Lunge and is not amplified");

        pending.request(origin, 0);
        pending.confirm(origin, 10);
        pending.velocity(motion, 2, 1_000_000_001L);
        require(pending.take(motion, 1_000_000_002L) == null, "Expired replies cannot boost");

        pending.request(origin, 0);
        pending.confirm(origin, 10);
        pending.velocity(motion, 2, 11);
        pending.clear();
        require(pending.take(motion, 12) == null, "Disable, damage and context loss clear pending motion");

        pending.request(origin, 0);
        pending.confirm(origin, 10);
        pending.velocity(motion, 1, 11);
        require(motion.equals(pending.take(motion, 12)), "Default multiplier preserves vanilla velocity");
        System.out.println("MOONS_AUTOSPEAR_MOTION_VERIFIED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
