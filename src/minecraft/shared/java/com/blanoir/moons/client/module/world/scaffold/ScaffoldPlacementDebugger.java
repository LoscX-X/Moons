package com.blanoir.moons.client.module.world.scaffold;

import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.rotation.RotationHistory;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Observes only: never commits rotation, sends packets or changes placement eligibility. */
final class ScaffoldPlacementDebugger {
    private static final ScaffoldTraceBuffer HISTORY = new ScaffoldTraceBuffer(1024);
    private static final ArrayDeque<Use> PENDING = new ArrayDeque<>();
    private static final ThreadPoolExecutor WRITER =
            new ThreadPoolExecutor(
                    0,
                    1,
                    10,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    task -> {
                        Thread thread = new Thread(task, "Moons-ScaffoldDebug");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static boolean active;
    private static long epoch;
    private static long counter;
    private static String session;
    private static String firstAlert = "none";
    private static volatile String saved = "not saved";
    private static String selection = "support=-";
    private static String movement = "next MOVE: waiting";
    private static BlockPos previousSupport;
    private static Direction previousFace;
    private static Attempt attempt;
    private static int captureAfterAlert;

    private record Attempt(long id, BlockPos support, Direction face, Rotation planned) {}

    private record Use(
            int sequence,
            long nanos,
            Vec3 eye,
            Vec3 sentEye,
            double reach,
            BlockPos support,
            Rotation planned) {}

    private ScaffoldPlacementDebugger() {}

    static synchronized void update(Minecraft client, boolean wanted) {
        if (wanted == active) return;
        if (active) save(client, "stopped");
        active = wanted;
        PENDING.clear();
        attempt = null;
        if (!active) return;
        HISTORY.clear();
        epoch = System.nanoTime();
        session = System.currentTimeMillis() + "-" + RandomMath.uuid().toString().substring(0, 8);
        firstAlert = "none";
        saved = "not saved";
        previousSupport = null;
        previousFace = null;
        selection = "support=-";
        movement = "next MOVE: waiting";
        captureAfterAlert = 0;
        add("START player=" + client.player.getName().getString());
    }

    static synchronized void context(Minecraft client) {
        if (active) save(client, "context-change");
        active = false;
        PENDING.clear();
        attempt = null;
    }

    static synchronized void state(Minecraft client, String state) {
        if (!active) return;
        add("TICK t=" + client.player.tickCount + " " + state);
        if (captureAfterAlert > 0 && --captureAfterAlert == 0) save(client, "after-alert");
    }

    static synchronized void begin(
            Minecraft client, BlockPos support, Direction face, Vec3 hit, Rotation planned) {
        if (!active) return;
        attempt = new Attempt(++counter, support.immutable(), face, planned);
        boolean changed =
                previousSupport != null
                        && (!previousSupport.equals(support) || previousFace != face);
        selection = "support=" + pos(support) + "/" + face + " switch=" + changed;
        RotationHistory.Sent sent = RotationHistory.latest();
        add(
                "ATTEMPT id="
                        + counter
                        + " t="
                        + client.player.tickCount
                        + " "
                        + selection
                        + " previous="
                        + pos(previousSupport)
                        + "/"
                        + previousFace
                        + " place="
                        + pos(support.relative(face))
                        + " hit="
                        + vec(hit)
                        + " block="
                        + client.level.getBlockState(support)
                        + " camera="
                        + angle(new Rotation(client.player.getYRot(), client.player.getXRot()))
                        + " planned="
                        + angle(planned)
                        + " sent="
                        + (sent.valid() ? angle(sent.rotation()) : "unknown")
                        + " sentTick="
                        + sent.tick()
                        + " sentIndex="
                        + sent.sequence());
        previousSupport = support.immutable();
        previousFace = face;
    }

    static synchronized void result(String result) {
        if (!active || attempt == null) return;
        add(
                "RESULT id="
                        + attempt.id()
                        + " local="
                        + result
                        + " (local result is not server acceptance)");
        attempt = null;
    }

    static synchronized void clearAim() {
        if (active) add("CLEAR_AIM pendingUses=" + PENDING.size());
    }

    static synchronized void packet(
            Minecraft client,
            PacketSendEvent.Post event,
            Vec3 lastSentPosition,
            boolean positionKnown) {
        if (!active || event.connection() != client.player.connection.getConnection()) return;
        long now = System.nanoTime();
        // A replay can run off-thread. Do not ray trace or read mutable world/player state there.
        if (!client.isSameThread()) {
            add(
                    "SEND_OFF_THREAD "
                            + event.packet().getClass().getSimpleName()
                            + " (geometry/correlation unavailable)");
            return;
        }
        if (event.packet() instanceof ServerboundUseItemOnPacket use) {
            var hit = use.getHitResult();
            boolean own =
                    attempt != null
                            && attempt.support().equals(hit.getBlockPos())
                            && attempt.face() == hit.getDirection();
            Rotation planned = own ? attempt.planned() : null;
            Vec3 eye = client.player.getEyePosition();
            Vec3 sentEye =
                    positionKnown ? lastSentPosition.add(0, eye.y - client.player.getY(), 0) : null;
            var sent = RotationHistory.latest();
            double reach = client.player.blockInteractionRange();
            add(
                    "USE_ON seq="
                            + use.getSequence()
                            + " id="
                            + (own ? attempt.id() : "external/replayed")
                            + " support="
                            + pos(hit.getBlockPos())
                            + "/"
                            + hit.getDirection()
                            + " eye="
                            + vec(eye)
                            + " lastSentEye="
                            + vec(sentEye)
                            + " reach="
                            + reach
                            + " ground="
                            + client.player.onGround()
                            + " sent="
                            + (sent.valid() ? angle(sent.rotation()) : "unknown")
                            + " planned="
                            + angle(planned)
                            + " box(localEye,sent)="
                            + box(
                                    eye,
                                    hit.getBlockPos(),
                                    sent.valid() ? sent.rotation() : null,
                                    reach)
                            + " box(localEye,planned)="
                            + box(eye, hit.getBlockPos(), planned, reach)
                            + " box(sentEye,planned)="
                            + box(sentEye, hit.getBlockPos(), planned, reach));
            if (PENDING.size() == 32) {
                add("UNPAIRED_EVICT seq=" + PENDING.removeFirst().sequence());
            }
            PENDING.addLast(
                    new Use(
                            use.getSequence(),
                            now,
                            eye,
                            sentEye,
                            reach,
                            hit.getBlockPos().immutable(),
                            planned));
        } else if (event.packet() instanceof ServerboundMovePlayerPacket move) {
            RotationHistory.Sent sent = RotationHistory.latest();
            Rotation actual = sent.valid() ? sent.rotation() : null;
            add(
                    "MOVE t="
                            + client.player.tickCount
                            + " look="
                            + move.hasRotation()
                            + " position="
                            + move.hasPosition()
                            + " angle="
                            + angle(actual)
                            + " previousPos="
                            + (positionKnown ? vec(lastSentPosition) : "unknown")
                            + " packetPos="
                            + (move.hasPosition()
                                    ? vec(new Vec3(move.getX(0), move.getY(0), move.getZ(0)))
                                    : "unchanged"));
            while (!PENDING.isEmpty()) {
                Use use = PENDING.removeFirst();
                String matches =
                        use.planned() == null || actual == null
                                ? "unknown"
                                : Boolean.toString(RotationHistory.same(use.planned(), actual));
                movement =
                        String.format(
                                Locale.ROOT,
                                "seq=%d nextLook=%s gap=%.2fms match=%s",
                                use.sequence(),
                                move.hasRotation(),
                                (now - use.nanos()) / 1_000_000.0,
                                matches);
                add(
                        "PAIR "
                                + movement
                                + " box(localEye,next)="
                                + box(use.eye(), use.support(), actual, use.reach())
                                + " box(sentEye,next)="
                                + box(use.sentEye(), use.support(), actual, use.reach()));
            }
        } else {
            add("SEND " + event.packet().getClass().getSimpleName());
        }
    }

    static synchronized void alert(Minecraft client, ClientboundSystemChatPacket packet) {
        if (!active) return;
        String message =
                packet.content()
                        .getString()
                        .replaceAll("§.", "")
                        .replace('\n', ' ')
                        .replace('\r', ' ');
        if (!message.toLowerCase(Locale.ROOT).contains("rotationplace")) return;
        // A server alert may name another player; retain the text, do not assert it is our flag.
        add("RECEIVED_ALERT " + message);
        if (!firstAlert.equals("none")) return;
        firstAlert = message.length() > 100 ? message.substring(0, 100) : message;
        captureAfterAlert = 40;
        save(client, "first-alert");
    }

    static synchronized List<String> lines() {
        return List.of(
                selection,
                movement,
                "first alert: " + firstAlert,
                "trace: " + saved,
                "gap/box = local measurements; not Grim verdict");
    }

    private static void add(String text) {
        HISTORY.add(
                String.format(
                        Locale.ROOT,
                        "+%.3fms %s",
                        (System.nanoTime() - epoch) / 1_000_000.0,
                        text));
    }

    private static void save(Minecraft client, String reason) {
        Path file =
                client.gameDirectory
                        .toPath()
                        .resolve("logs/moons/scaffold-" + session + "-" + reason + ".log");
        String content =
                "Scaffold local trace. Reason="
                        + reason
                        + " discardedEntries="
                        + HISTORY.discarded()
                        + " pendingUses="
                        + PENDING.size()
                        + "\n"
                        + "Times are local send-observer times, NOT server processing gaps.\n"
                        + "box = support unit-AABB intersection at local eye height; NOT Grim's full ray/pose history.\n"
                        + "lastSentEye is a local estimate, NOT acknowledged server position.\n"
                        + "Alerts are received text, may refer to another player; no server packet sequence is inferred.\n"
                        + String.join("\n", HISTORY.snapshot())
                        + "\n";
        String saveSession = session;
        saved = "saving " + reason;
        try {
            WRITER.execute(
                    () -> {
                        try {
                            Files.createDirectories(file.getParent());
                            Files.writeString(file, content);
                            synchronized (ScaffoldPlacementDebugger.class) {
                                if (saveSession.equals(session))
                                    saved = reason + " saved (logs/moons)";
                            }
                            System.out.println(
                                    "[Moons/ScaffoldDebug] Saved " + file.toAbsolutePath());
                        } catch (Exception failure) {
                            synchronized (ScaffoldPlacementDebugger.class) {
                                if (saveSession.equals(session))
                                    saved = "save failed: " + failure.getClass().getSimpleName();
                            }
                            System.err.println("[Moons/ScaffoldDebug] Save failed: " + failure);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            saved = "save queue full";
        }
    }

    private static String box(Vec3 eye, BlockPos support, Rotation rotation, double reach) {
        if (eye == null || rotation == null) return "unknown";
        AABB bounds = new AABB(support);
        Vec3 end =
                eye.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(reach));
        return Boolean.toString(bounds.contains(eye) || bounds.clip(eye, end).isPresent());
    }

    private static String pos(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String vec(Vec3 vec) {
        return vec == null
                ? "unknown"
                : String.format(Locale.ROOT, "%.5f,%.5f,%.5f", vec.x, vec.y, vec.z);
    }

    private static String angle(Rotation rotation) {
        return rotation == null
                ? "unknown"
                : String.format(Locale.ROOT, "%.5f/%.5f", rotation.yaw(), rotation.pitch());
    }
}
