package com.blanoir.moons.client.module.impl.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AutoSpearImpactVerification {
    public static void main(String[] args) {
        Vec3 eye = new Vec3(0, 1.6, 0);
        Vec3 look = new Vec3(0, 0, 1);
        Vec3 motion = new Vec3(0, -.08, 1);
        AABB target = new AABB(-.3, 0, 8, .3, 2, 8.6);
        Vec3 burst = AutoSpearImpact.plan(eye, look, motion, target, 2, 4, 20);
        require(burst != null && Math.abs(burst.z - 5.5) < 1E-9,
                "Even an extreme requested speed stops before the target's minimum reach");
        require(burst.y == motion.y, "Burst preserves vertical velocity");
        require(AutoSpearImpact.plan(eye, look, motion, target, 2, 4, 3) == null,
                "Wait until a single burst can put the target in reach");
        require(AutoSpearImpact.plan(eye, look, new Vec3(1, 0, 0), target, 2, 4, 20) == null,
                "Sideways motion must not trigger a contact burst");
        require(AutoSpearImpact.plan(eye, look, Vec3.ZERO, target, 2, 4, 20) == null,
                "No burst from a standstill after the Lunge has stopped");
        require(AutoSpearImpact.plan(eye, look, motion, target.move(4, 0, 0), 2, 4, 20) == null,
                "A target that leaves the aim ray cancels this step");
        require(AutoSpearImpact.plan(eye, look, motion, target.move(0, 0, -6), 2, 4, 20) == null,
                "Do not accelerate past a target already too close");
        require(AutoSpearImpact.plan(eye, look, new Vec3(0, 4, 1), target, 2, 4, 20) == null,
                "Vertical movement that misses the target cancels the predicted contact");
        require(AutoSpearImpact.plan(eye, look, motion, target, 2, 4, Double.NaN) == null,
                "Invalid desired speed cannot alter motion");

        var pulse = new AutoSpearImpact.Pulse();
        pulse.arm(1, 0);
        require(pulse.brake(motion) == null, "Waiting must not brake ordinary approach motion");
        require(pulse.fire(1), "A confirmed Lunge permits one pulse");
        require(!pulse.fire(2), "Never repeat a burst from one Lunge");
        require(new Vec3(0, -.08, 0).equals(pulse.brake(motion)),
                "The following movement tick brakes horizontally while preserving gravity");
        require(pulse.brake(motion) == null, "Only brake once; subsequent input remains usable");
        pulse.arm(1, 0);
        require(!pulse.fire(750_000_001L), "Expired approach windows cannot fire later");
        pulse.arm(1, 0);
        pulse.fire(1);
        pulse.clear();
        require(pulse.brake(new Vec3(1, .4, 2)) == null,
                "Server corrections or new knockback discard the old brake");
        pulse.arm(1, 0);
        pulse.fire(1);
        require(pulse.brake(motion) != null, "Disable can consume an outstanding brake immediately");
        require(!pulse.waiting(2), "Disable clears the approach window as well");
        System.out.println("MOONS_AUTOSPEAR_IMPACT_VERIFIED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
