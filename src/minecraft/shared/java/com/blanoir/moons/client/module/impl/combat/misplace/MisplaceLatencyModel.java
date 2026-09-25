package com.blanoir.moons.client.module.impl.combat.misplace;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Short-horizon server-distance model. It predicts geometry, not an anticheat verdict. */
public final class MisplaceLatencyModel {
    public enum Verdict {
        IN_RANGE,
        OUT_OF_RANGE,
        UNCERTAIN,
        NO_SOURCE,
        STALE,
        WARMUP,
        HORIZON_EXCEEDED,
        KNOCKBACK_TRANSITION
    }

    public record Network(int ownRtt, int targetRtt, int tickMs, int jitterMs, int limitMs) {}

    public record Observation(
            Vec3 position,
            Vec3 velocity,
            double velocityError,
            long receivedAt,
            int samples,
            long sampleSpanMs,
            long damageAt,
            int damageRtt) {}

    public record Query(
            Vec3 sentEye,
            long sentAt,
            Vec3 eye,
            Vec3 visiblePosition,
            AABB visibleBox,
            Network network,
            double reach,
            long now) {}

    public record Estimate(
            Verdict verdict,
            double visibleDistance,
            double minimumDistance,
            double maximumDistance,
            double horizonMinMs,
            double horizonMaxMs) {
        public boolean hasBounds() {
            return Double.isFinite(maximumDistance)
                    && (verdict == Verdict.IN_RANGE
                            || verdict == Verdict.OUT_OF_RANGE
                            || verdict == Verdict.UNCERTAIN);
        }
    }

    private MisplaceLatencyModel() {}

    public static Estimate estimate(Observation observation, Query query) {
        double visible = distance(query.eye(), query.visibleBox());
        Network network = query.network();
        if (!finite(query.sentEye())
                || query.now() < query.sentAt()
                || query.now() - query.sentAt() > 1500)
            return unavailable(Verdict.NO_SOURCE, visible);
        if (observation == null
                || !finite(observation.position())
                || !finite(observation.velocity())
                || query.now() < observation.receivedAt()
                || query.now() - observation.receivedAt() > 250)
            return unavailable(Verdict.STALE, visible);
        if (observation.samples() < 2) return unavailable(Verdict.WARMUP, visible);
        if (network.ownRtt() < 0
                || network.targetRtt() < 0
                || network.ownRtt() > 2000
                || network.targetRtt() > 2000
                || !Double.isFinite(observation.velocityError()))
            return unavailable(Verdict.UNCERTAIN, visible);
        double age = query.now() - observation.receivedAt();
        double tick = Math.clamp(network.tickMs(), 1, 250);
        double jitter = Math.clamp(network.jitterMs(), 0, 250);
        // Two endpoint receipts include target-uplink and observer-downlink jitter.
        // A server snapshot also hides up to one movement-report tick of sample age.
        double sampleTimeError = tick + 4 * jitter;
        if (observation.sampleSpanMs() < 2 * sampleTimeError)
            return unavailable(Verdict.WARMUP, visible);
        double timingSpeedError =
                observation.velocity().length()
                        * sampleTimeError
                        / (observation.sampleSpanMs() - sampleTimeError);
        // Snapshot downlink + attack uplink = OWN RTT. Target uplink is already
        // present in the observed server coordinate; adding target RTT double counts it.
        double low = Math.max(0, age + network.ownRtt() - tick - 4 * jitter);
        double high = age + network.ownRtt() + tick + 4 * jitter;
        if (high > network.limitMs())
            return new Estimate(
                    Verdict.HORIZON_EXCEEDED, visible, Double.NaN, Double.NaN, low, high);
        // Damage -> target downlink -> client tick -> target uplink -> server tick.
        // Receipt-time differences cancel our downlink. RTT difference alone cannot
        // identify execution of an impulse. Never invent a rewind of observed motion.
        if (observation.damageAt() >= 0) {
            double earliestChange =
                    observation.damageAt()
                            + Math.max(0, observation.damageRtt() - tick - 4 * jitter);
            if (query.now() + network.ownRtt() + tick + 4 * jitter >= earliestChange)
                return new Estimate(
                        Verdict.KNOCKBACK_TRANSITION, visible, Double.NaN, Double.NaN, low, high);
        }
        AABB rawBox =
                query.visibleBox().move(observation.position().subtract(query.visiblePosition()));
        double minimum = Double.POSITIVE_INFINITY, maximum = 0;
        // Distance to a translated box is convex along constant-velocity motion.
        for (int i = 0; i <= 8; i++) {
            double seconds = (low + (high - low) * i / 8.0) / 1000;
            double nominal =
                    distance(query.sentEye(), rawBox.move(observation.velocity().scale(seconds)));
            // Explicit assumption: residual speed plus <= 8 blocks/s^2 acceleration.
            // This envelope is not a calibrated probability or an anticheat guarantee.
            double error =
                    .01
                            + (Math.max(0, observation.velocityError()) + timingSpeedError)
                                    * seconds
                            + 4 * seconds * seconds;
            minimum = Math.min(minimum, Math.max(0, nominal - error));
            maximum = Math.max(maximum, nominal + error);
        }
        minimum = Math.max(0, minimum - observation.velocity().length() * (high - low) / 16000);
        Verdict verdict =
                maximum <= query.reach()
                        ? Verdict.IN_RANGE
                        : minimum > query.reach() ? Verdict.OUT_OF_RANGE : Verdict.UNCERTAIN;
        return new Estimate(verdict, visible, minimum, maximum, low, high);
    }

    /** Never make the displayed box closer than the largest supported server distance. */
    public static double pull(Estimate estimate, Vec3 eye, AABB box, double maximum) {
        if (!estimate.hasBounds() || estimate.maximumDistance() >= estimate.visibleDistance())
            return 0;
        Vec3 center = box.getCenter();
        Vec3 direction = MisplaceMotion.toward(eye, center);
        double cap =
                Math.min(
                        Math.max(0, maximum),
                        Math.max(0, Math.hypot(center.x - eye.x, center.z - eye.z) - .8));
        double low = 0, high = cap;
        for (int i = 0; i < 16; i++) {
            double middle = (low + high) * .5;
            if (distance(eye, box.move(direction.scale(-middle))) >= estimate.maximumDistance())
                low = middle;
            else high = middle;
        }
        return low;
    }

    public static double distance(Vec3 point, AABB box) {
        if (!finite(point) || box == null) return Double.NaN;
        double dx = Math.max(box.minX - point.x, Math.max(0, point.x - box.maxX));
        double dy = Math.max(box.minY - point.y, Math.max(0, point.y - box.maxY));
        double dz = Math.max(box.minZ - point.z, Math.max(0, point.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Estimate unavailable(Verdict verdict, double visible) {
        return new Estimate(verdict, visible, Double.NaN, Double.NaN, 0, 0);
    }

    static boolean finite(Vec3 value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }
}
