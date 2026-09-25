package com.blanoir.moons.client.module.impl.combat.hitselect;

import static com.blanoir.moons.client.module.impl.combat.hitselect.HitSelectCycle.Gate.*;

import com.blanoir.moons.client.module.impl.combat.HitSelect;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils.EntityRayState;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HitSelectVerification {
    public static void main(String[] args) throws Exception {
        waits();
        confirmations();
        scope();
        geometry();
        rates();
        System.out.println(
                "HitSelect verified: bounded waits, damage clocks, TriggerBot bypass and shared displaced attack geometry.");
    }

    private static HitSelectCycle.Policy policy(boolean server, int rtt) {
        return new HitSelectCycle.Policy(450, 150, 80, server, rtt);
    }

    private static void waits() {
        var cycle = new HitSelectCycle();
        require(cycle.evaluate(0, policy(false, 0)) == FIRST_HIT, "First contact waits");
        for (int time = 1; time < 150; time++)
            require(
                    cycle.evaluate(time, policy(false, 0)) == FIRST_HIT,
                    "Attempts cannot restart deadline");
        require(cycle.evaluate(150, policy(false, 0)) == READY, "Timeout releases without damage");
        require(
                cycle.evaluate(151, policy(false, 0)) == READY,
                "Multiple PRE checks are idempotent");
        cycle.attacked(151);
        cycle.incomingHit(500);
        require(
                cycle.evaluate(500, policy(false, 0)) == REPEAT,
                "Incoming damage cannot bypass repeat spacing");
        require(
                cycle.evaluate(601, policy(false, 0)) == READY,
                "Recent opponent hit releases trade delay");
        cycle.attacked(601);
        require(
                cycle.evaluate(1051, policy(false, 0)) == TRADE,
                "One incoming hit cannot unlock the next trade");
        require(
                cycle.evaluate(1131, policy(false, 0)) == READY,
                "Trade has its own fixed deadline");
        var incoming = new HitSelectCycle();
        incoming.incomingHit(100);
        require(
                incoming.evaluate(120, policy(false, 0)) == READY,
                "Can counter a hit just before first attempt");
        require(
                incoming.evaluate(1800, policy(false, 0)) == FIRST_HIT,
                "Idle encounter restarts first-hit timing");
        var missed = new HitSelectCycle();
        missed.evaluate(0, policy(false, 0));
        require(
                missed.evaluate(150, policy(false, 0)) == READY,
                "Rejected dispatch doesn't count as attack");
        require(
                missed.evaluate(160, policy(false, 0)) == READY,
                "No artificial cooldown after cancellation");
    }

    private static void confirmations() {
        var noWait = new HitSelectCycle.Policy(450, 0, 0, true, 100);
        var cycle = new HitSelectCycle();
        cycle.attacked(0);
        require(cycle.evaluate(449, noWait) == REPEAT, "Missing confirmation has a bounded retry");
        require(cycle.evaluate(450, noWait) == READY, "A missed attack cannot wait forever");
        cycle = new HitSelectCycle();
        cycle.attacked(0);
        cycle.targetDamaged(100, true);
        require(
                cycle.evaluate(499, noWait) == REPEAT,
                "Receipt clock accounts for RTT and tick uncertainty");
        require(cycle.evaluate(500, noWait) == READY, "Confirmation window expires");
        cycle.attacked(500);
        require(
                cycle.evaluate(501, noWait) == REPEAT,
                "Old confirmation cannot authorize another immediate attack");
        var unknown = new HitSelectCycle.Policy(450, 0, 0, true, 0);
        cycle = new HitSelectCycle();
        cycle.attacked(0);
        cycle.targetDamaged(100, true);
        require(cycle.evaluate(549, unknown) == REPEAT, "Unknown latency is not guessed");
        require(cycle.evaluate(550, unknown) == READY, "No-compensation confirmation expires");
        var predicted = new HitSelectCycle.Policy(450, 0, 0, false, 250);
        cycle = new HitSelectCycle();
        cycle.attacked(0);
        cycle.targetDamaged(250, true);
        require(cycle.evaluate(450, predicted) == READY, "Prediction doesn't double-count own RTT");
        cycle = new HitSelectCycle();
        cycle.attacked(0);
        cycle.targetDamaged(20, false);
        require(
                cycle.evaluate(300, new HitSelectCycle.Policy(450, 0, 0, true, 250)) == REPEAT,
                "Someone else's hit cannot confirm ours");
    }

    private static void scope() throws Exception {
        var depth = HitSelect.class.getDeclaredField("bypassDepth");
        depth.setAccessible(true);
        require(depth.getInt(null) == 0, "Normal attacks are filterable");
        HitSelect.withoutFiltering(
                () -> {
                    try {
                        require(depth.getInt(null) == 1, "TriggerBot invocation is excluded");
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                    return HitSelect.withoutFiltering(() -> true);
                });
        require(depth.getInt(null) == 0, "Nested bypass restores outer state");
        try {
            HitSelect.withoutFiltering(
                    () -> {
                        throw new IllegalStateException("fixture");
                    });
        } catch (IllegalStateException expected) {
        }
        require(
                depth.getInt(null) == 0,
                "Exceptions cannot leak TriggerBot exclusion to manual or Aura");
    }

    private static void geometry() {
        Vec3 eye = new Vec3(0, 1.62, 0), look = new Vec3(1, 0, 0), offset = new Vec3(-.4, 0, 0);
        AABB raw = new AABB(3.3, 0, -.3, 3.9, 1.8, .3);
        var original = new CombatGeometry.Shape(raw, Vec3.ZERO);
        var shifted = new CombatGeometry.Shape(raw.move(offset), offset);
        require(
                CombatGeometry.traceShape(original, eye, look, 3, p -> true)
                        == EntityRayState.RANGE,
                "Raw target out of reach");
        require(
                CombatGeometry.traceShape(shifted, eye, look, 3, p -> true) == EntityRayState.HIT,
                "Shifted visible target is attackable");
        require(
                CombatGeometry.traceShape(shifted, eye, new Vec3(0, 0, 1), 3, p -> true)
                        == EntityRayState.AIM,
                "Offset doesn't bypass aiming");
        require(
                CombatGeometry.traceShape(shifted, eye, look, 3, p -> false)
                        == EntityRayState.BLOCKED,
                "Offset doesn't bypass cover");
        Vec3 contact = CombatGeometry.contact(shifted.box(), eye, eye.add(look.scale(3)));
        require(
                Math.abs(shifted.original(contact).x - 3.3) < 1e-6,
                "Cover check can map contact back to original location");
        require(raw.minX == 3.3, "Render/attack queries never mutate raw physics box");
        var backtracked = new CombatGeometry.Shape(raw.move(-.8, 0, 0), Vec3.ZERO);
        require(
                CombatGeometry.traceShape(backtracked, eye, look, 3, p -> true)
                        == EntityRayState.HIT,
                "Backtrack consumes already-delayed entity without a second rewind");
        require(
                CombatGeometry.traceShape(original, eye, look, 3, p -> true)
                        == EntityRayState.RANGE,
                "Replayed newer position immediately invalidates old reach");
    }

    private static void rates() {
        for (int step : new int[] {1, 10, 50}) {
            var cycle = new HitSelectCycle();
            var p = new HitSelectCycle.Policy(450, 150, 80, false, 250);
            int attacks = 0;
            long previous = -10000;
            for (int now = 0; now <= 3000; now += step) {
                if (cycle.evaluate(now, p) == READY) {
                    require(now - previous >= 450, "Click rate never bypasses repeat pause");
                    cycle.attacked(now);
                    previous = now;
                    attacks++;
                }
            }
            require(
                    attacks >= 5 && attacks <= 6,
                    "Useful attack cadence with bounded waits at every sampling rate");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
