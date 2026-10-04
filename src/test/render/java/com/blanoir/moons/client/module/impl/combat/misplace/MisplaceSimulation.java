package com.blanoir.moons.client.module.impl.combat.misplace;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Random;

/** Independent discrete-event world: two client clocks, server clock and four FIFO links. */
public final class MisplaceSimulation {
    public record Config(
            int aRtt,
            int bRtt,
            double speed,
            int scenario,
            double uplinkFraction,
            int jitter,
            int phase,
            long seed) {}

    private record Event(long time, int priority, long sequence, Runnable action) {}

    public static final class Frame {
        long time, arrival;
        double a, b, serverA, serverB, seenB, seenA, source, actual = Double.NaN, offset;
        boolean pendingSelf, knownTargetDamage, crossedUnseenImpulse;
        MisplaceLatencyModel.Estimate estimate;
    }

    public record Result(
            Config config,
            List<Frame> frames,
            List<String> events,
            long damageTime,
            long appliedTime,
            long reportedTime) {}

    private static final class Client {
        double position, input, impulse, pendingImpulse, lastSent;
        int epoch, pendingEpoch;
        long lastSentAt;
        double renderStart, renderTarget;
        long renderAt;
        final MisplaceMotion tracker = new MisplaceMotion();

        double rendered(long at) {
            double weight = Math.clamp((at - renderAt) / 150.0, 0, 1);
            return renderStart + (renderTarget - renderStart) * weight;
        }
    }

    private final Config config;
    private final Client[] clients = {new Client(), new Client()};
    private final double[] server = new double[2];
    private final long[] channelArrival = {-1, -1, -1, -1};
    private final PriorityQueue<Event> events =
            new PriorityQueue<>(
                    Comparator.comparingLong(Event::time)
                            .thenComparingInt(Event::priority)
                            .thenComparingLong(Event::sequence));
    private final List<Frame> frames = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();
    private final Random random;
    private long now, sequence, damageTime = -1, appliedTime = -1, reportedTime = -1;
    private int victim = -1;

    private MisplaceSimulation(Config config) {
        this.config = config;
        random = new Random(config.seed());
        clients[0].position = 0;
        clients[1].position = 3 + config.speed() * .65;
        clients[0].input = config.scenario() == 0 ? 0 : config.speed() * .5;
        clients[1].input = config.scenario() == 0 ? -config.speed() : -config.speed() * .5;
        if (config.scenario() == 3) {
            clients[0].input = config.speed();
            clients[1].input = 0;
        }
        for (int i = 0; i < 2; i++) {
            server[i] = clients[i].lastSent = clients[i].position;
            clients[i].renderStart = clients[i].renderTarget = clients[1 - i].position;
            clients[i].tracker.reset(position(clients[1 - i].position), 0);
        }
    }

    public static Result run(Config config) {
        MisplaceSimulation simulation = new MisplaceSimulation(config);
        simulation.start();
        return new Result(
                config,
                simulation.frames,
                simulation.trace,
                simulation.damageTime,
                simulation.appliedTime,
                simulation.reportedTime);
    }

    private void start() {
        schedule(config.phase(), 1, this::serverTick);
        schedule(0, 2, () -> clientTick(0));
        schedule(25, 2, () -> clientTick(1));
        schedule(0, 3, this::probe);
        while (!events.isEmpty()) {
            Event event = events.remove();
            if (event.time() > 2100) break;
            now = event.time();
            event.action().run();
        }
    }

    private void schedule(long time, int priority, Runnable action) {
        events.add(new Event(time, priority, sequence++, action));
    }

    private int rtt(int client) {
        return client == 0 ? config.aRtt() : config.bRtt();
    }

    private long serverTime(long at) {
        return config.phase() + (long) Math.ceil((at - config.phase()) / 50.0) * 50;
    }

    private void send(int client, boolean up, Runnable receive) {
        int channel = client * 2 + (up ? 0 : 1);
        double base = rtt(client) * (up ? config.uplinkFraction() : 1 - config.uplinkFraction());
        int jitter =
                config.jitter() == 0
                        ? 0
                        : random.nextInt(config.jitter() * 2 + 1) - config.jitter();
        long arrival =
                Math.max(channelArrival[channel], now + Math.max(0, Math.round(base + jitter)));
        channelArrival[channel] = arrival;
        schedule(up ? Math.max(now, serverTime(arrival)) : arrival, 0, receive);
    }

    private void clientTick(int id) {
        Client client = clients[id];
        if (client.pendingEpoch > client.epoch) {
            client.epoch = client.pendingEpoch;
            client.impulse = client.pendingImpulse;
            if (id == victim) {
                appliedTime = now;
                trace.add(now + ":client_" + id + "_applies_knockback");
            }
        }
        // Deliberately independent from the estimator: a tick-integrated input+impulse trajectory.
        if (now > 0) client.position += (client.input + client.impulse) * .05;
        client.impulse *= .6;
        double report = client.position;
        int epoch = client.epoch;
        client.lastSent = report;
        client.lastSentAt = now;
        send(
                id,
                true,
                () -> {
                    server[id] = report;
                    if (id == victim && epoch > 0 && reportedTime < 0) {
                        reportedTime = now;
                        trace.add(now + ":server_receives_knockback_motion_" + id);
                    }
                });
        if (now < 1700) schedule(now + 50, 2, () -> clientTick(id));
    }

    private void serverTick() {
        if ((config.scenario() == 1 || config.scenario() == 2)
                && damageTime < 0
                && now >= 400
                && Math.abs(server[1] - server[0]) - .3 <= 3) {
            // A preceding, server-confirmed hit is the initial condition of the counterhit
            // scenario.
            victim = config.scenario() == 1 ? 1 : 0;
            damageTime = now;
            trace.add(now + ":server_confirms_hit_on_" + victim);
            int target = victim;
            send(
                    target,
                    false,
                    () -> {
                        clients[target].pendingImpulse = target == 0 ? -8 : 8;
                        clients[target].pendingEpoch = 1;
                        trace.add(now + ":client_" + target + "_receives_knockback");
                    });
            send(
                    1 - target,
                    false,
                    () -> {
                        clients[1 - target].tracker.impact(now, rtt(target), 50, config.jitter());
                        trace.add(now + ":observer_receives_damage_" + target);
                    });
        }
        for (int i = 0; i < 2; i++) {
            int observer = i;
            double snapshot = server[1 - i];
            send(
                    observer,
                    false,
                    () -> {
                        Client client = clients[observer];
                        client.renderStart = client.rendered(now);
                        client.renderTarget = snapshot;
                        client.renderAt = now;
                        client.tracker.observe(position(snapshot), now);
                    });
        }
        if (now < 1700) schedule(now + 50, 1, this::serverTick);
    }

    private void probe() {
        Client a = clients[0], b = clients[1];
        Frame frame = new Frame();
        frame.time = now;
        frame.a = a.position;
        frame.b = b.position;
        frame.serverA = server[0];
        frame.serverB = server[1];
        frame.seenB = a.rendered(now);
        frame.seenA = b.rendered(now);
        frame.source = a.lastSent;
        frame.pendingSelf = victim == 0 && damageTime >= 0 && appliedTime < 0;
        frame.knownTargetDamage = a.tracker.observation(true).damageAt() >= 0;
        var network =
                new MisplaceLatencyModel.Network(
                        config.aRtt(), config.bRtt(), 50, config.jitter(), 1000);
        var query =
                new MisplaceLatencyModel.Query(
                        eye(a.lastSent),
                        a.lastSentAt,
                        eye(a.position),
                        position(frame.seenB),
                        box(frame.seenB),
                        network,
                        3,
                        now);
        frame.offset = a.tracker.advance(query, .4, true, true, 0);
        frame.estimate = a.tracker.estimate();
        frames.add(frame);
        // Probes carry no damage side effect: counterfactual reach queries can be compared fairly.
        send(
                0,
                true,
                () -> {
                    frame.arrival = now;
                    frame.actual = MisplaceLatencyModel.distance(eye(server[0]), box(server[1]));
                    frame.crossedUnseenImpulse =
                            victim == 1
                                    && damageTime >= 0
                                    && damageTime <= now
                                    && !frame.knownTargetDamage;
                    if (Math.abs(server[0] - frame.source) > 1.0E-8)
                        throw new AssertionError(
                                "Later movement overtook an earlier attack in the FIFO link");
                });
        if (now < 1500) schedule(now + 50, 3, this::probe);
    }

    public static Vec3 position(double x) {
        return new Vec3(x, 64, 0);
    }

    public static Vec3 eye(double x) {
        return new Vec3(x, 65.62, 0);
    }

    public static AABB box(double x) {
        return new AABB(x - .3, 64, -.3, x + .3, 65.8, .3);
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path output = Path.of(args.length == 0 ? "misplace-simulation" : args[0]);
        Files.createDirectories(output);
        StringBuilder json = new StringBuilder("["),
                csv =
                        new StringBuilder(
                                "aRtt,bRtt,speed,scenario,upFraction,jitter,issued,arrived,visible,min,max,actual,offset,verdict,pendingSelf,unseenImpulse\n");
        long probes = 0,
                bounded = 0,
                covered = 0,
                pull = 0,
                falseInRange = 0,
                pendingHits = 0,
                unseenFalse = 0;
        boolean first = true;
        for (int a : new int[] {20, 80, 150, 250})
            for (int b : new int[] {20, 80, 150, 250})
                for (double speed : new double[] {3, 6})
                    for (int scenario = 0; scenario < 4; scenario++)
                        for (double split : new double[] {.5, .8}) {
                            Result result = run(new Config(a, b, speed, scenario, split, 0, 0, 42));
                            if (!first) json.append(',');
                            first = false;
                            json.append('[')
                                    .append(a)
                                    .append(',')
                                    .append(b)
                                    .append(',')
                                    .append(speed)
                                    .append(',')
                                    .append(scenario)
                                    .append(',')
                                    .append(split)
                                    .append(",[");
                            boolean firstFrame = true;
                            for (Frame f : result.frames()) {
                                probes++;
                                var e = f.estimate;
                                if (e.hasBounds()) {
                                    bounded++;
                                    if (f.actual >= e.minimumDistance() - 1e-6
                                            && f.actual <= e.maximumDistance() + 1e-6) covered++;
                                }
                                if (f.offset > .001) pull++;
                                if (e.verdict() == MisplaceLatencyModel.Verdict.IN_RANGE
                                        && f.actual > 3 + 1e-6) {
                                    falseInRange++;
                                    if (f.crossedUnseenImpulse) unseenFalse++;
                                }
                                if (f.pendingSelf && f.actual <= 3) pendingHits++;
                                csv.append(
                                        String.format(
                                                "%d,%d,%.0f,%d,%.1f,0,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s,%s%n",
                                                a,
                                                b,
                                                speed,
                                                scenario,
                                                split,
                                                f.time,
                                                f.arrival,
                                                e.visibleDistance(),
                                                e.minimumDistance(),
                                                e.maximumDistance(),
                                                f.actual,
                                                f.offset,
                                                e.verdict(),
                                                f.pendingSelf,
                                                f.crossedUnseenImpulse));
                                if (!firstFrame) json.append(',');
                                firstFrame = false;
                                json.append('[').append(f.time);
                                for (double value :
                                        new double[] {
                                            f.a,
                                            f.b,
                                            f.serverA,
                                            f.serverB,
                                            f.seenB,
                                            f.seenA,
                                            e.minimumDistance(),
                                            e.maximumDistance(),
                                            f.offset,
                                            f.actual,
                                            f.arrival,
                                            e.verdict().ordinal(),
                                            f.pendingSelf ? 1 : 0,
                                            f.crossedUnseenImpulse ? 1 : 0
                                        })
                                    json.append(',')
                                            .append(
                                                    Double.isFinite(value)
                                                            ? String.format("%.3f", value)
                                                            : "null");
                                json.append(']');
                            }
                            json.append("],[")
                                    .append(result.damageTime())
                                    .append(',')
                                    .append(result.appliedTime())
                                    .append(',')
                                    .append(result.reportedTime())
                                    .append("]]");
                        }
        json.append(']');
        Files.writeString(output.resolve("traces.json"), json);
        Files.writeString(output.resolve("probes.csv"), csv);
        String summary =
                "scenarios=256\nprobes="
                        + probes
                        + "\nbounded="
                        + bounded
                        + "\ncovered="
                        + covered
                        + "\nnonzeroPull="
                        + pull
                        + "\nfalseInRange="
                        + falseInRange
                        + "\nunseenImpulseFalseInRange="
                        + unseenFalse
                        + "\npendingSelfHits="
                        + pendingHits
                        + "\n";
        Files.writeString(output.resolve("summary.txt"), summary);
        System.out.print(summary);
        if (bounded == 0 || pull == 0 || pendingHits == 0)
            throw new AssertionError("Missing useful simulation coverage");
        if (falseInRange != unseenFalse)
            throw new AssertionError(
                    "Unexplained in-range prediction errors require investigation");
    }
}
