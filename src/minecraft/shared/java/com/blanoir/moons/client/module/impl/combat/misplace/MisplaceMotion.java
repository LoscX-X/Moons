package com.blanoir.moons.client.module.impl.combat.misplace;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/** Packet observations and presentation smoothing around the causal latency model. */
public final class MisplaceMotion {
    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private double velocityError;
    private int samples;
    private long receivedAt, settleUntil;
    private long damageAt = -1, damageUntil;
    private int damageRtt;
    private double amount;
    private long advancedAt = -1;
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private long sampleSpan;

    private record Sample(Vec3 position, long time) {}

    private MisplaceLatencyModel.Estimate estimate;

    public void reset(Vec3 at, long now) {
        position = MisplaceLatencyModel.finite(at) ? at : null;
        velocity = Vec3.ZERO;
        velocityError = 0;
        samples = 0;
        receivedAt = now;
        settleUntil = 0;
        damageAt = -1;
        amount = 0;
        advancedAt = -1;
        estimate = null;
        history.clear();
        if (position != null) history.add(new Sample(position, now));
        sampleSpan = 0;
    }

    public void discontinuity(Vec3 at, long now) {
        reset(at, now);
        settleUntil = now + 200;
    }

    public void observe(Vec3 at, long now) {
        if (!MisplaceLatencyModel.finite(at)) return;
        if (position == null || now < receivedAt || now - receivedAt > 250) {
            reset(at, now);
            return;
        }
        if (at.distanceToSqr(position) > 9) {
            discontinuity(at, now);
            return;
        }
        position = at;
        receivedAt = now;
        // A report after the complete target RTT/tick window is new trajectory evidence.
        if (damageAt >= 0 && now >= damageUntil) {
            damageAt = -1;
            samples = 0;
            velocity = Vec3.ZERO;
            history.clear();
            history.add(new Sample(at, now));
            sampleSpan = 0;
            return;
        }
        if (!history.isEmpty() && history.peekLast().time() == now) history.removeLast();
        history.addLast(new Sample(at, now));
        while (history.size() > 1 && history.peekFirst().time() < now - 400) history.removeFirst();
        while (history.size() > 64) history.removeFirst();
        Sample first = history.peekFirst();
        sampleSpan = now - first.time();
        if (sampleSpan < 100) return;
        Vec3 measured = at.subtract(first.position()).scale(1000.0 / sampleSpan);
        if (measured.lengthSqr() > 400) {
            discontinuity(at, now);
            return;
        }
        double residual = 0;
        double recentSpeedError = 0;
        Sample previous = null;
        for (Sample point : history) {
            Vec3 fitted =
                    first.position().add(measured.scale((point.time() - first.time()) / 1000.0));
            residual = Math.max(residual, fitted.distanceTo(point.position()) * 2000 / sampleSpan);
            if (previous != null && point.time() - previous.time() >= 25) {
                Vec3 segmentVelocity =
                        point.position()
                                .subtract(previous.position())
                                .scale(1000.0 / (point.time() - previous.time()));
                recentSpeedError =
                        Math.max(recentSpeedError, segmentVelocity.subtract(measured).length());
            }
            previous = point;
        }
        velocityError =
                Math.max(
                        recentSpeedError,
                        Math.max(
                                residual, samples == 0 ? 0 : measured.subtract(velocity).length()));
        velocity = measured;
        samples = history.size() - 1;
    }

    public void impact(long now, int targetRtt, int tickMs, int jitterMs) {
        if (position == null) return;
        damageAt = now;
        damageRtt = Math.clamp(targetRtt, 0, 2000);
        damageUntil = now + damageRtt + 2L * tickMs + 4L * jitterMs;
    }

    public MisplaceLatencyModel.Observation observation(boolean knockback) {
        return new MisplaceLatencyModel.Observation(
                position,
                velocity,
                velocityError,
                receivedAt,
                samples,
                sampleSpan,
                knockback ? damageAt : -1,
                damageRtt);
    }

    public MisplaceLatencyModel.Estimate estimate() {
        return estimate;
    }

    public double advance(
            MisplaceLatencyModel.Query query,
            double maximum,
            boolean adaptive,
            boolean knockback,
            int smoothingMs) {
        if (!Double.isFinite(maximum)
                || !MisplaceLatencyModel.finite(query.eye())
                || query.now() < settleUntil) {
            amount = 0;
            estimate = null;
            return 0;
        }
        estimate = MisplaceLatencyModel.estimate(observation(knockback), query);
        double desired =
                adaptive
                        ? MisplaceLatencyModel.pull(
                                estimate, query.eye(), query.visibleBox(), maximum)
                        : Math.min(
                                maximum,
                                Math.max(
                                        0,
                                        Math.hypot(
                                                        query.visiblePosition().x - query.eye().x,
                                                        query.visiblePosition().z - query.eye().z)
                                                - .8));
        double elapsed = advancedAt < 0 ? 50 : Math.clamp(query.now() - advancedAt, 0, 100);
        advancedAt = query.now();
        double blend = smoothingMs <= 0 ? 1 : 1 - Math.exp(-elapsed / smoothingMs);
        amount = desired < amount ? desired : amount + (desired - amount) * blend;
        return amount;
    }

    public static Vec3 toward(Vec3 observer, Vec3 target) {
        Vec3 delta = new Vec3(target.x - observer.x, 0, target.z - observer.z);
        return delta.lengthSqr() < 1.0E-9 ? Vec3.ZERO : delta.normalize();
    }

    public static Vec3 offset(Vec3 observer, Vec3 target, double amount) {
        return toward(observer, target).scale(-amount);
    }

    public static Vec3 intersection(AABB box, Vec3 start, Vec3 end) {
        return box.contains(start) ? start : box.clip(start, end).orElse(null);
    }
}
