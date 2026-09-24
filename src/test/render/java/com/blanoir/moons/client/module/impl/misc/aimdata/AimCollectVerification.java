package com.blanoir.moons.client.module.impl.misc.aimdata;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic combat replay, geometry, asynchronous drain and byte-budget checks. */
public final class AimCollectVerification {
    private static final long SECOND = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        geometry();
        combat();
        Path home = Path.of(System.getProperty("moons.home"));
        Files.createDirectories(home);
        writer(Files.createTempDirectory(home, "aim-"));
        System.out.println("AimCollect verification passed");
    }

    private static void geometry() {
        var observer = actor(1, 0, 0, 0);
        var near = actor(2, 0, 3, 0);
        var far = actor(3, 0, 5, 0);
        var behind = actor(4, 0, -3, 0);
        var sample = sample(1, SECOND, observer, List.of(far, behind, near));
        var candidates = AimGeometry.candidates(sample);
        require(
                candidates.size() == 2 && candidates.getFirst().actor().id() == 2,
                "Nearest intersected target wins, behind observer is excluded");
        require(
                candidates.getFirst().evidence().equals("ray_box_inferred"),
                "Ray remains an inference");
        require(candidates.getFirst().points().size() >= 18, "Multiple body and box landmarks");
        require(Math.abs(candidates.getFirst().rayDistance() - 2.7) < 1e-8, "Box entry distance");
        require(AimGeometry.wrap(-358) == 2 && AimGeometry.wrap(358) == -2, "Yaw wrap");
        require(
                AimGeometry.intersect(
                                new AimGeometry.Point(0, 1, 3),
                                new AimGeometry.Point(0, 0, 1),
                                near.box(),
                                8)
                        == 0,
                "Ray starts inside target");
        require(
                AimGeometry.intersect(observer.eye(), new AimGeometry.Point(0, 0, 1), near.box(), 2)
                        == null,
                "Finite ray range");
    }

    private static void combat() {
        CombatCapture capture = new CombatCapture(32);
        List<AimGeometry.Sample> saved = new ArrayList<>();
        capture.observe(sample(1, SECOND, actor(1, 0, 0, 0), List.of()), saved::add);
        capture.observe(sample(2, 3 * SECOND, actor(1, 0, 0, 0), List.of()), saved::add);
        capture.observe(sample(3, 4 * SECOND, actor(2, 0, 3, 0), List.of()), saved::add);
        capture.observe(sample(4, 5 * SECOND, actor(3, 0, 5, 0), List.of()), saved::add);
        require(saved.isEmpty(), "Idle/movement snapshots never reach disk");
        capture.combat("p1", 1, "p2", 2, 7 * SECOND, 3 * SECOND, saved::add);
        require(
                saved.size() == 2 && saved.getFirst().appliedNanos() == 3 * SECOND,
                "Only five seconds of participant pre-roll is promoted");
        require(
                saved.stream().allMatch(s -> s.appliedNanos() < s.combatId()),
                "Pre-roll phase retained");
        capture.observe(sample(5, 8 * SECOND, actor(1, .1, 0, 0), List.of()), saved::add);
        require(saved.size() == 3, "Combat continues streaming");
        capture.combat("p1", 1, "p2", 2, 9 * SECOND, 3 * SECOND, saved::add);
        require(saved.size() == 3, "Repeated damage never duplicates pre-roll");
        capture.observe(sample(6, 13 * SECOND, actor(1, .2, 0, 0), List.of()), saved::add);
        require(saved.size() == 3, "Combat timeout returns to memory only");
        capture.combat("p1", 1, "p2", 2, 14 * SECOND, 3 * SECOND, saved::add);
        require(
                saved.size() == 4 && saved.getLast().combatId() == 14 * SECOND,
                "Next fight promotes unsaved pre-roll under a new combat id");
        CombatCapture bounded = new CombatCapture(2);
        List<AimGeometry.Sample> limited = new ArrayList<>();
        for (int i = 1; i <= 3; i++)
            bounded.observe(sample(i, i * SECOND, actor(1, 0, 0, 0), List.of()), limited::add);
        bounded.combat("p1", 1, "p2", 2, 4 * SECOND, SECOND, limited::add);
        require(
                limited.size() == 2 && limited.getFirst().preRollEvicted() == 1,
                "Pre-roll memory is bounded and truncation is visible");
    }

    private static void writer(Path root) throws Exception {
        Path idle = root.resolve("idle");
        AimDatasetWriter noCombat = new AimDatasetWriter(idle, 100_000, 5000, 16);
        noCombat.close();
        require(noCombat.await(5000), "Idle writer stops");
        try (var files = Files.list(idle)) {
            require(files.noneMatch(p -> p.toString().endsWith(".jsonl")), "No idle dataset file");
        }
        Path normal = root.resolve("normal");
        AimDatasetWriter writer = new AimDatasetWriter(normal, 1_000_000, 5000, 128);
        for (int i = 1; i <= 20; i++) {
            var sample =
                    sample(
                            i,
                            SECOND + i * 50_000_000L,
                            actor(1, i * .1, 0, i == 1 ? 179 : -179),
                            List.of());
            require(
                    writer.offer(sample.withCombat(i, SECOND, SECOND, 4 * SECOND, 2, 0)),
                    "Queue accepts fixture");
        }
        writer.close();
        require(
                !writer.offer(sample(99, SECOND, actor(1, 0, 0, 0), List.of())),
                "Close rejects new producers");
        require(
                writer.await(5000) && writer.written() == 20 && writer.dropped() == 0,
                "Close drains every accepted sample");
        List<JsonObject> rows = readRows(normal);
        require(rows.size() == 20, "Every JSONL sample is independently readable");
        rows.sort(
                java.util.Comparator.comparingLong(
                        row -> row.getAsJsonObject("sample").get("sequence").getAsLong()));
        require(
                Math.abs(rows.get(1).get("bodyYawDelta").getAsDouble() - 2) < 1e-8,
                "Writer wraps angular delta");
        require(
                Math.abs(rows.get(1).get("horizontalSpeed").getAsDouble() - 2) < 1e-8,
                "Packet interval drives observed speed");
        require(
                rows.getFirst().get("observedVelocity").isJsonNull(),
                "Initial velocity remains unknown");
        try (var files = Files.list(normal)) {
            require(
                    files.filter(p -> p.toString().endsWith(".jsonl")).count() > 1,
                    "Shards rotate");
        }
        Path quota = root.resolve("quota");
        Files.createDirectories(quota);
        Files.write(quota.resolve("existing.bin"), new byte[1000]);
        AimDatasetWriter capped = new AimDatasetWriter(quota, 10_000, 5000, 128);
        for (int i = 1; i <= 30; i++)
            capped.offer(sample(i, i * SECOND, actor(1, 0, 0, 0), List.of()));
        capped.close();
        require(
                capped.await(5000) && capped.status().equals("Limit reached"),
                "Quota stops recording");
        require(
                AimDatasetWriter.directoryBytes(quota) <= 10_000 && capped.written() > 0,
                "Existing files and new bytes stay below cap");
        require(Files.size(quota.resolve("existing.bin")) == 1000, "Old data is never deleted");
        AimDatasetWriter resumed = new AimDatasetWriter(quota, 10_000, 5000, 16);
        resumed.offer(sample(1, SECOND, actor(1, 0, 0, 0), List.of()));
        resumed.close();
        require(
                resumed.await(5000) && AimDatasetWriter.directoryBytes(quota) <= 10_000,
                "Restart recounts old sessions instead of resetting quota");
        Path invalid = root.resolve("not-a-directory");
        Files.writeString(invalid, "fixture");
        AimDatasetWriter broken = new AimDatasetWriter(invalid, 10_000, 5000, 16);
        require(
                broken.await(5000) && broken.status().startsWith("Error:"),
                "I/O failures are visible");
    }

    private static List<JsonObject> readRows(Path directory) throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".jsonl")).toList())
                for (String line : Files.readAllLines(path)) {
                    JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                    if (row.get("type").getAsString().equals("sample")) rows.add(row);
                }
        }
        return rows;
    }

    private static AimGeometry.Actor actor(int id, double x, double z, float yaw) {
        return new AimGeometry.Actor(
                id,
                "p" + id,
                new AimGeometry.Point(x, 0, z),
                new AimGeometry.Point(x, 1.62, z),
                new AimGeometry.Box(x - .3, 0, z - .3, x + .3, 1.8, z + .3),
                yaw,
                0,
                yaw,
                true,
                true,
                SECOND,
                SECOND,
                SECOND,
                true,
                false,
                false,
                "STANDING",
                false,
                50);
    }

    private static AimGeometry.Sample sample(
            long seq, long time, AimGeometry.Actor observer, List<AimGeometry.Actor> targets) {
        return new AimGeometry.Sample(
                seq,
                time - 10_000_000L,
                time,
                1_700_000_000_000L + time / 1_000_000L - 10,
                1_700_000_000_000_000L / 1000 + time / 1_000_000L,
                time / 50_000_000L,
                0,
                -1,
                -1,
                -1,
                0,
                "PosRot",
                true,
                true,
                false,
                false,
                -1,
                -1,
                observer,
                targets,
                false,
                8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
