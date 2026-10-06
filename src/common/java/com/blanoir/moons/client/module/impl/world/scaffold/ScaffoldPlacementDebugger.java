package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.manager.rotation.RotationManager;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

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
                    com.blanoir.moons.client.threads.ThreadFactories.daemon("Moons-ScaffoldDebug"));
    private static boolean active;
    private static long epoch;
    private static long counter;
    private static String session;
    private static String firstAlert = "none";
    private static volatile String saved = "not saved";
    private static String selection = "support=-";
    private static String movement = "next MOVE: waiting";
    private static BlockPos previousSupport;
    private static EnumFacing previousFace;
    private static Attempt attempt;
    private static int captureAfterAlert;

    private record Attempt(long id, BlockPos support, EnumFacing face, Rotation planned) {}

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
        add("START player=" + client.thePlayer.getName());
    }

    static synchronized void context(Minecraft client) {
        if (active) save(client, "context-change");
        active = false;
        PENDING.clear();
        attempt = null;
    }

    static synchronized void state(Minecraft client, String state) {
        if (!active) return;
        add("TICK t=" + client.thePlayer.ticksExisted + " " + state);
        if (captureAfterAlert > 0 && --captureAfterAlert == 0) save(client, "after-alert");
    }

    static synchronized void begin(
            Minecraft client, BlockPos support, EnumFacing face, Vec3 hit, Rotation planned) {
        if (!active) return;
        attempt = new Attempt(++counter, new BlockPos(support), face, planned);
        boolean changed =
                previousSupport != null
                        && (!previousSupport.equals(support) || previousFace != face);
        selection = "support=" + pos(support) + "/" + face + " switch=" + changed;
        RotationManager.Sent sent = RotationManager.latest();
        add(
                "ATTEMPT id="
                        + counter
                        + " t="
                        + client.thePlayer.ticksExisted
                        + " "
                        + selection
                        + " previous="
                        + pos(previousSupport)
                        + "/"
                        + previousFace
                        + " place="
                        + pos(support.offset(face))
                        + " hit="
                        + vec(hit)
                        + " block="
                        + client.theWorld.getBlockState(support)
                        + " camera="
                        + angle(
                                new Rotation(
                                        client.thePlayer.rotationYaw,
                                        client.thePlayer.rotationPitch))
                        + " planned="
                        + angle(planned)
                        + " sent="
                        + (sent.valid() ? angle(sent.rotation()) : "unknown")
                        + " sentTick="
                        + sent.tick()
                        + " sentIndex="
                        + sent.sequence());
        previousSupport = new BlockPos(support);
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
        if (!active || event.connection() != client.getNetHandler().getNetworkManager()) return;
        long now = System.nanoTime();
        // A replay can run off-thread. Do not ray trace or read mutable world/player state there.
        if (!client.isCallingFromMinecraftThread()) {
            add(
                    "SEND_OFF_THREAD "
                            + event.packet().getClass().getSimpleName()
                            + " (geometry/correlation unavailable)");
            return;
        }
        if (event.packet() instanceof C08PacketPlayerBlockPlacement use) {
            var hit = PacketAccess.useOnHit(use);
            if (hit == null) return;
            boolean own =
                    attempt != null
                            && attempt.support().equals(hit.getBlockPos())
                            && attempt.face() == hit.sideHit;
            Rotation planned = own ? attempt.planned() : null;
            Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
            Vec3 sentEye =
                    positionKnown
                            ? lastSentPosition.addVector(0, eye.yCoord - client.thePlayer.posY, 0)
                            : null;
            var sent = RotationManager.latest();
            double reach = Minecraft.getMinecraft().playerController.getBlockReachDistance();
            add(
                    "USE_ON seq="
                            + (int) counter
                            + " id="
                            + (own ? attempt.id() : "external/replayed")
                            + " support="
                            + pos(hit.getBlockPos())
                            + "/"
                            + hit.sideHit
                            + " eye="
                            + vec(eye)
                            + " lastSentEye="
                            + vec(sentEye)
                            + " reach="
                            + reach
                            + " ground="
                            + client.thePlayer.onGround
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
                            (int) counter,
                            now,
                            eye,
                            sentEye,
                            reach,
                            new BlockPos(hit.getBlockPos()),
                            planned));
        } else if (event.packet() instanceof C03PacketPlayer move) {
            RotationManager.Sent sent = RotationManager.latest();
            Rotation actual = sent.valid() ? sent.rotation() : null;
            add(
                    "MOVE t="
                            + client.thePlayer.ticksExisted
                            + " look="
                            + move.getRotating()
                            + " position="
                            + move.isMoving()
                            + " angle="
                            + angle(actual)
                            + " previousPos="
                            + (positionKnown ? vec(lastSentPosition) : "unknown")
                            + " packetPos="
                            + (move.isMoving()
                                    ? vec(
                                            new Vec3(
                                                    move.getPositionX(),
                                                    move.getPositionY(),
                                                    move.getPositionZ()))
                                    : "unchanged"));
            while (!PENDING.isEmpty()) {
                Use use = PENDING.removeFirst();
                String matches =
                        use.planned() == null || actual == null
                                ? "unknown"
                                : Boolean.toString(RotationManager.same(use.planned(), actual));
                movement =
                        String.format(
                                Locale.ROOT,
                                "seq=%d nextLook=%s gap=%.2fms match=%s",
                                use.sequence(),
                                move.getRotating(),
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

    static synchronized void alert(Minecraft client, S02PacketChat packet) {
        if (!active) return;
        String message =
                packet.getChatComponent()
                        .getUnformattedText()
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
                client.mcDataDir
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
                        + "box = support unit-AxisAlignedBB intersection at local eye height; NOT Grim's full ray/pose history.\n"
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
        AxisAlignedBB bounds = LegacyWorld.box(support);
        Vec3 end =
                eye.add(
                        VecMath.scale(
                                VecMath.directionFromRotation(rotation.pitch(), rotation.yaw()),
                                reach));
        return Boolean.toString(
                bounds.isVecInside(eye) || LegacyWorld.intercept(bounds, eye, end).isPresent());
    }

    private static String pos(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String vec(Vec3 vec) {
        return vec == null
                ? "unknown"
                : String.format(Locale.ROOT, "%.5f,%.5f,%.5f", vec.xCoord, vec.yCoord, vec.zCoord);
    }

    private static String angle(Rotation rotation) {
        return rotation == null
                ? "unknown"
                : String.format(Locale.ROOT, "%.5f/%.5f", rotation.yaw(), rotation.pitch());
    }
}
