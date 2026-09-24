package com.blanoir.moons.client.module.impl.misc.aimdata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** One bounded, nonblocking producer queue and one file owner per recording session. */
public final class AimDatasetWriter {
    public static final long MAX_BYTES = 50_000_000_000L;
    private static final long SHARD_BYTES = 32_000_000L;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final ArrayBlockingQueue<AimGeometry.Sample> queue;
    private final Path directory;
    private final long budget;
    private final long shardLimit;
    private final String session = UUID.randomUUID().toString();
    private final String fileTime =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
    private final Thread thread;
    private final Map<String, AimGeometry.Sample> previous = new HashMap<>();
    private final Map<String, Movement> movements = new HashMap<>();
    private long lastSequence;
    private volatile boolean closing;
    private volatile boolean done;
    private volatile String status = "Starting";
    private volatile long written, dropped, used;
    private long shardBytes;
    private int shard;
    private OutputStream output;

    public AimDatasetWriter(Path directory) {
        this(directory, MAX_BYTES, SHARD_BYTES, 2048);
    }

    AimDatasetWriter(Path directory, long budget, long shardLimit, int capacity) {
        this.directory = directory;
        this.budget = Math.min(MAX_BYTES, budget);
        this.shardLimit = shardLimit;
        queue = new ArrayBlockingQueue<>(capacity);
        thread = new Thread(this::run, "moons-aim-dataset");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized boolean offer(AimGeometry.Sample sample) {
        if (closing || done) return false;
        if (queue.offer(sample)) return true;
        dropped++;
        return false;
    }

    public synchronized void close() {
        closing = true;
    }

    public boolean done() {
        return done;
    }

    public boolean closing() {
        return closing;
    }

    public String status() {
        return status;
    }

    public long written() {
        return written;
    }

    public long dropped() {
        return dropped;
    }

    public long used() {
        return used;
    }

    boolean await(long millis) throws InterruptedException {
        thread.join(millis);
        return done;
    }

    private void run() {
        try {
            Files.createDirectories(directory);
            try (FileChannel lockChannel =
                            FileChannel.open(
                                    directory.resolve(".writer.lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    FileLock lock = lockChannel.tryLock()) {
                if (lock == null) throw new IOException("Dataset is already being recorded");
                used = directoryBytes(directory);
                status = "Recording";
                try {
                    if (used >= budget) throw new QuotaReached();
                    long flushed = System.nanoTime();
                    while (!closing || !queue.isEmpty()) {
                        AimGeometry.Sample sample = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (sample != null) {
                            try {
                                writeSample(sample);
                            } catch (IOException failure) {
                                synchronized (this) {
                                    dropped++;
                                }
                                throw failure;
                            }
                        }
                        if (output != null && System.nanoTime() - flushed >= 1_000_000_000L) {
                            output.flush();
                            flushed = System.nanoTime();
                        }
                    }
                    if (output != null)
                        append(
                                JSON.toJson(
                                        Map.of(
                                                "type", "end", "session", session, "written",
                                                written, "dropped", dropped)));
                    status = "Saved";
                } finally {
                    if (output != null) output.close();
                }
            }
        } catch (QuotaReached limit) {
            status = "Limit reached";
        } catch (Exception failure) {
            status = "Error: " + failure.getMessage();
            System.err.println("[AimCollect] " + status);
        } finally {
            synchronized (this) {
                closing = true;
                dropped += queue.size();
                queue.clear();
                done = true;
            }
        }
    }

    private void writeSample(AimGeometry.Sample sample) throws IOException {
        if (lastSequence != 0 && sample.sequence() != lastSequence + 1) {
            previous.clear();
            movements.clear();
        }
        var actor = sample.observer();
        var last = previous.get(actor.uuid());
        double dt = last == null ? 0 : (sample.appliedNanos() - last.appliedNanos()) / 1e9;
        boolean continuous =
                last != null
                        && sample.combatId() == last.combatId()
                        && !sample.discontinuity()
                        && dt > 0
                        && dt <= .5
                        && sample.sequence() > last.sequence();
        boolean knownRotation =
                continuous && actor.rotationKnown() && last.observer().rotationKnown();
        List<AimGeometry.Candidate> candidates = AimGeometry.candidates(sample);
        var row = new HashMap<String, Object>();
        row.put("type", "sample");
        row.put("session", session);
        row.put("sample", sample);
        row.put("droppedTotal", dropped);
        row.put("dtSeconds", last == null ? null : dt);
        row.put("continuous", continuous);
        row.put(
                "bodyYawDelta",
                knownRotation
                        ? AimGeometry.wrap(actor.bodyYaw() - last.observer().bodyYaw())
                        : null);
        row.put(
                "headYawDelta",
                continuous && actor.headKnown() && last.observer().headKnown()
                        ? AimGeometry.wrap(actor.headYaw() - last.observer().headYaw())
                        : null);
        row.put("pitchDelta", knownRotation ? actor.pitch() - last.observer().pitch() : null);
        row.put(
                "displacement",
                continuous ? actor.position().subtract(last.observer().position()) : null);
        row.put("sampleTimeUtc", Instant.ofEpochMilli(sample.appliedEpochMillis()).toString());
        row.put("phase", sample.appliedNanos() < sample.combatId() ? "pre_roll" : "combat");
        row.put(
                "combatStartEpochMillis",
                sample.appliedEpochMillis()
                        + (sample.combatId() - sample.appliedNanos()) / 1_000_000L);
        row.put(
                "combatUntilEpochMillis",
                sample.appliedEpochMillis()
                        + (sample.combatUntilNanos() - sample.appliedNanos()) / 1_000_000L);
        row.put(
                "receiveToApplyMs",
                sample.receivedNanos() < 0
                        ? null
                        : (sample.appliedNanos() - sample.receivedNanos()) / 1e6);
        if (sample.discontinuity() || (last != null && sample.combatId() != last.combatId()))
            movements.remove(actor.uuid());
        Movement movement = movements.get(actor.uuid());
        AimGeometry.Point velocity = null, acceleration = null;
        Double movementDt = null;
        String movementClock = null;
        if (sample.positionChanged()) {
            boolean receiptClock =
                    movement != null && sample.receivedNanos() >= 0 && movement.receivedNanos >= 0;
            double elapsed =
                    movement == null
                            ? 0
                            : receiptClock
                                    ? (sample.receivedNanos() - movement.receivedNanos) / 1e9
                                    : (sample.appliedNanos() - movement.nanos) / 1e9;
            if (movement != null && elapsed > 0 && elapsed <= .5) {
                movementDt = elapsed;
                movementClock = receiptClock ? "receive" : "apply";
                velocity = scale(actor.position().subtract(movement.position), 1 / elapsed);
                if (movement.velocity != null)
                    acceleration = scale(velocity.subtract(movement.velocity), 1 / elapsed);
            }
            movements.put(
                    actor.uuid(),
                    new Movement(
                            sample.appliedNanos(),
                            sample.receivedNanos(),
                            actor.position(),
                            velocity));
        }
        row.put("movementDtSeconds", movementDt);
        row.put("movementClock", movementClock);
        row.put("observedVelocity", velocity);
        row.put("observedAcceleration", acceleration);
        row.put(
                "horizontalSpeed",
                velocity == null ? null : Math.hypot(velocity.x(), velocity.z()));
        row.put(
                "groundTransition",
                continuous && actor.onGround() != last.observer().onGround()
                        ? (actor.onGround() ? "landed" : "left_ground")
                        : "none");
        row.put("targetEvidence", candidates.isEmpty() ? "none" : candidates.getFirst().evidence());
        row.put("targetCandidates", candidates);
        row.put("occlusion", "unknown");
        append(JSON.toJson(row));
        written++;
        if (previous.size() >= 4096) {
            previous.clear();
            movements.clear();
        }
        previous.put(actor.uuid(), sample);
        lastSequence = sample.sequence();
    }

    private void append(String json) throws IOException {
        byte[] line = (json + "\n").getBytes(StandardCharsets.UTF_8);
        if (output == null || shardBytes + line.length > shardLimit) {
            if (output != null) {
                output.close();
                output = null;
            }
            // Recount at every shard boundary, including prior sessions and imported files.
            used = directoryBytes(directory);
            byte[] header =
                    (JSON.toJson(
                                            Map.of(
                                                    "type",
                                                    "session",
                                                    "schemaVersion",
                                                    1,
                                                    "session",
                                                    session,
                                                    "startedAt",
                                                    Instant.now().toString(),
                                                    "source",
                                                    "remote_entity_packets_at_client_apply",
                                                    "maxDatasetBytes",
                                                    budget,
                                                    "angles",
                                                    "degrees",
                                                    "positions",
                                                    "blocks",
                                                    "visibility",
                                                    "unknown"))
                                    + "\n")
                            .getBytes(StandardCharsets.UTF_8);
            if (used + header.length + line.length > budget) throw new QuotaReached();
            Path file =
                    directory.resolve(
                            "aim-" + fileTime + "Z-" + session + "-" + (++shard) + ".jsonl");
            output =
                    new BufferedOutputStream(
                            Files.newOutputStream(
                                    file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            64 * 1024);
            output.write(header);
            used += header.length;
            shardBytes = header.length;
        }
        if (used + line.length > budget) throw new QuotaReached();
        output.write(line);
        used += line.length;
        shardBytes += line.length;
    }

    static long directoryBytes(Path directory) throws IOException {
        long total = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(path))
                    throw new IOException("Dataset contains a symbolic link: " + path);
                if (Files.isRegularFile(path)) total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }

    private static final class QuotaReached extends IOException {}

    private record Movement(
            long nanos,
            long receivedNanos,
            AimGeometry.Point position,
            AimGeometry.Point velocity) {}

    private static AimGeometry.Point scale(AimGeometry.Point value, double scale) {
        return new AimGeometry.Point(value.x() * scale, value.y() * scale, value.z() * scale);
    }
}
