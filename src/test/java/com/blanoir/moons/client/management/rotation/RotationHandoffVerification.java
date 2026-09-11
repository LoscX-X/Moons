package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.module.impl.combat.critical.mode.PacketCritical;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Executable contract for every producer using the shared rotation template. */
public final class RotationHandoffVerification {
    private static int assertions;
    private static final Rotation CAMERA = new Rotation(5, 6);

    public static void main(String[] args) throws Exception {
        try {
            acquisitionStartsFromRealHistory();
            committedWindowSurvivesDisableAndCancelledSend();
            pinnedInteractionSurvivesRelease();
            replayCannotConfirmAnotherRequest();
            manualUseBlocksNewOwners();
            quantizationPreservesExactPairs();
            cameraKeepsContinuousYawAfterRelease();
            nextOwnerResumesAtClosingBoundary();
            releaseBeforeMovementResumesWithoutGap();
            packetCriticalPublishesAuraRotationAboveFloor();
            System.out.println("ROTATION_HANDOFF_VERIFIED assertions=" + assertions);
        } finally {
            RotationManager.reset();
        }
    }

    private static void packetCriticalPublishesAuraRotationAboveFloor() throws Exception {
        var sendMovement =
                PacketCritical.class.getDeclaredMethod(
                        "sendMovement", Vec3.class, Rotation.class, boolean.class, Consumer.class);
        sendMovement.setAccessible(true);
        for (double floor : new double[] {-64, 0, 64, 319}) {
            for (boolean collision : new boolean[] {false, true}) {
                RotationManager.reset();
                RotationManager.record(look(-40, -10), 1);
                var aura = new RotationLease("Aura", 50);
                aura.acquire(request(80, 20), CAMERA, value -> {});
                Rotation committed = aura.commit(new Rotation(80, 20), true, true);
                Vec3 position = new Vec3(10, floor, -20);
                var packets = new ArrayList<ServerboundMovePlayerPacket>();
                sendMovement.invoke(
                        null,
                        position,
                        RotationManager.resolve().rotation(),
                        collision,
                        (Consumer<ServerboundMovePlayerPacket>) packets::add);
                require(packets.size() == 2, "critical has an ascent followed by a descent");
                double peak = packets.getFirst().getY(Double.NaN);
                double falling = packets.getLast().getY(Double.NaN);
                require(
                        peak > falling && falling > floor,
                        "critical descends without entering the floor or landing before ATTACK");
                require(peak - floor <= 0.0625D, "critical stays within the checked headroom");
                for (var packet : packets) {
                    require(
                            packet.hasPosition() && packet.hasRotation(),
                            "critical must publish the current attack ray before vanilla movement");
                    require(!packet.isOnGround(), "critical remains airborne until ATTACK");
                    require(
                            packet.horizontalCollision() == collision,
                            "critical preserves horizontal collision state");
                    require(
                            packet.getX(Double.NaN) == position.x
                                    && packet.getZ(Double.NaN) == position.z,
                            "critical cannot introduce horizontal displacement");
                    RotationManager.capture(new PacketSendEvent.Pre(null, packet, null));
                    RotationManager.record(packet, 2);
                    require(
                            RotationManager.same(RotationManager.latest().rotation(), committed),
                            "critical uses Aura's committed ray, not the camera or previous send");
                    require(
                            RotationManager.sentFor(aura, packet),
                            "critical movement confirms the existing Aura submission");
                }
                RotationLease.finishMotion();
                require(
                        RotationManager.same(RotationManager.latest().rotation(), committed),
                        "closing movement cannot restore stale rotation history");
            }
        }
    }

    private static void acquisitionStartsFromRealHistory() {
        RotationManager.reset();
        require(!RotationLease.hasSilentRotation(), "idle leaves manual input available");
        var aura = new RotationLease("Aura", 50);
        var block = new RotationLease("Block", 100);
        Rotation[] start = {null};
        int[] starts = {0};
        require(
                aura.acquire(
                        request(70, 10),
                        CAMERA,
                        value -> {
                            start[0] = value;
                            starts[0]++;
                        }),
                "initial acquire");
        require(RotationManager.same(start[0], CAMERA), "unknown history seeds from camera");
        require(
                RotationLease.hasSilentRotation(),
                "input blocked before the first rotation commit");
        require(!RotationManager.latest().valid(), "seed is never labelled sent");
        RotationManager.record(new ServerboundMovePlayerPacket.StatusOnly(false, false), 0);
        require(
                !RotationManager.latest().valid(),
                "positionless/rotationless send cannot invent a look");
        var sent = look(70, 10);
        RotationManager.record(sent, 1);
        aura.acquire(request(80, 20), CAMERA, value -> starts[0]++);
        require(starts[0] == 1, "renewal does not restart the trajectory");
        require(
                block.acquire(request(120, 40), CAMERA, value -> start[0] = value),
                "uncommitted preemption");
        require(
                RotationManager.same(start[0], new Rotation(70, 10)),
                "B starts at sent 70, not planned 80 or camera 5");
        require(!aura.active(), "old owner invalidated");
        require(RotationLease.hasSilentRotation(), "preemption keeps manual input blocked");
        block.release();
        require(RotationManager.latest().yaw() == 70, "feature release preserves shared history");
        require(aura.acquire(request(90, 30), CAMERA, value -> start[0] = value), "reacquire");
        require(start[0].yaw() == 70, "reacquire ignores its old private trajectory");
        RotationManager.reset();
        require(!RotationLease.hasSilentRotation(), "context reset restores manual input");
        require(
                !aura.active() && !RotationManager.latest().valid(),
                "context reset invalidates owner and history");
    }

    private static void committedWindowSurvivesDisableAndCancelledSend() {
        RotationManager.reset();
        RotationManager.record(look(70, 10), 1);
        var aura = new RotationLease("Aura", 50);
        var block = new RotationLease("Block", 100);
        aura.acquire(request(80, 20), CAMERA, value -> {});
        Rotation committed = aura.commit(new Rotation(80, 20), true, true);
        require(
                aura.commit(new Rotation(100, 50), true, true) == committed,
                "all readers reuse the same result");
        require(!block.acquire(request(120, 40)), "cannot preempt an action/movement window");
        aura.release();
        require(RotationLease.hasSilentRotation(), "pending release keeps input blocked");
        require(
                aura.active() && RotationLease.submission().rotation() == committed,
                "disable defers release and preserves output");
        var cancelled = look(80, 20);
        RotationManager.capture(new PacketSendEvent.Pre(null, cancelled, null));
        RotationLease.finishMotion();
        require(!RotationLease.hasSilentRotation(), "completed release restores input");
        require(
                !aura.active() && RotationManager.latest().yaw() == 70,
                "method completion without POST cannot advance history");
        Rotation[] start = {null};
        require(
                block.acquire(request(120, 40), CAMERA, value -> start[0] = value),
                "handoff after cancelled window completes");
        require(start[0].yaw() == 70, "cancelled angle is not the next seed");
        block.release();
        aura.acquire(request(80, 20), CAMERA, value -> {});
        aura.commit(new Rotation(80, 20), true, true);
        var emitted = look(80, 20);
        RotationManager.capture(new PacketSendEvent.Pre(null, emitted, null));
        RotationManager.record(emitted, 2);
        require(
                aura.confirmed() && RotationManager.sentFor(aura, emitted),
                "matching real send confirms its owner");
        RotationLease.finishMotion();
        block.acquire(request(120, 40), CAMERA, value -> start[0] = value);
        require(start[0].yaw() == 80, "successful window hands off its final sent angle");
    }

    private static void pinnedInteractionSurvivesRelease() {
        RotationManager.reset();
        var block = new RotationLease("Block", 100);
        var next = new RotationLease("Next", 1000);
        block.acquire(request(42, 76), CAMERA, value -> {});
        require(block.pin(), "pin interaction");
        block.release();
        RotationLease.finishMotion();
        require(RotationLease.hasSilentRotation(), "pinned use blocks input until closure");
        require(
                block.active() && !next.acquire(request(0, 0)),
                "pin survives disable and method completion");
        block.unpin();
        require(!RotationLease.hasSilentRotation(), "unpin completes input suppression");
        require(
                !block.active() && next.acquire(request(0, 0)),
                "explicit interaction closure completes deferred release");
    }

    private static void replayCannotConfirmAnotherRequest() {
        RotationManager.reset();
        var lease = new RotationLease("A", 50);
        lease.acquire(request(20, 10), CAMERA, value -> {});
        lease.commit(new Rotation(20, 10), true, true);
        var queued = look(20, 10);
        RotationManager.capture(new PacketSendEvent.Pre(null, queued, null));
        RotationLease.finishMotion();
        lease.release();
        lease.acquire(request(20, 10), CAMERA, value -> {});
        lease.commit(new Rotation(20, 10), true, true);
        RotationManager.capture(new PacketSendEvent.Pre(null, queued, null));
        RotationManager.record(queued, 2);
        require(RotationManager.latest().yaw() == 20, "replayed send updates actual history");
        require(
                !lease.confirmed() && !RotationManager.sentFor(lease, queued),
                "same floats cannot confirm a new tenure");
        RotationLease.finishMotion();
        var own = look(20, 10);
        lease.commit(new Rotation(20, 10), true, true);
        RotationManager.capture(new PacketSendEvent.Pre(null, own, null));
        RotationManager.record(own, 3);
        require(lease.confirmed(), "new tenure confirms from its own send");
        RotationManager.record(new ServerboundMovePlayerPacket.StatusOnly(false, false), 4);
        require(
                RotationManager.latest().yaw() == 20 && RotationManager.latest().pitch() == 10,
                "rotationless send inherits history");
        RotationLease.finishMotion();
        lease.acquire(request(30, 10), CAMERA, value -> {});
        lease.commit(new Rotation(30, 10), true, true);
        var oldWindow = look(30, 10);
        RotationManager.capture(new PacketSendEvent.Pre(null, oldWindow, null));
        RotationLease.finishMotion();
        lease.commit(new Rotation(40, 10), true, true);
        RotationManager.record(oldWindow, 5);
        require(
                !lease.confirmed() && !RotationManager.sentFor(lease, oldWindow),
                "a replay from an old window cannot confirm the same still-active request");
    }

    private static void manualUseBlocksNewOwners() {
        RotationManager.reset();
        var next = new RotationLease("Next", 100);
        Rotation exact = new Rotation(Math.nextUp(42F), 76);
        require(RotationLease.holdManual(exact), "manual use records exact pair");
        require(!RotationLease.hasSilentRotation(), "ordinary manual use is not a silent turn");
        require(!next.acquire(request(120, 30)), "new rotation waits for manual closing movement");
        RotationLease.finishMotion();
        require(
                RotationLease.manualRotation() != null,
                "cancel/no packet cannot close manual window");
        RotationManager.record(look(42, 76), 1);
        require(
                RotationLease.manualRotation() != null,
                "similar float is not the exact manual angle");
        RotationManager.record(look(exact.yaw(), exact.pitch()), 2);
        Rotation[] start = {null};
        require(
                next.acquire(request(120, 30), CAMERA, value -> start[0] = value),
                "closing send permits takeover");
        require(RotationManager.same(start[0], exact), "takeover begins at manual closing angle");
    }

    private static void quantizationPreservesExactPairs() {
        require(
                RotationQuantizer.yaw(179, -179, .5) == 181,
                "wrap follows shortest equivalent yaw");
        require(RotationQuantizer.pitch(89, 100, .5) == 90, "pitch stays legal");
        RotationManager.reset();
        var lease = new RotationLease("Exact", 100);
        Rotation exact = new Rotation(Math.nextUp(42F), Math.nextDown(76F));
        lease.acquire(request(exact.yaw(), exact.pitch()), CAMERA, value -> {});
        require(
                RotationManager.same(exact, lease.commit(exact, true, true)),
                "validated/interaction float bits bypass quantization");
    }

    private static RotationRequest request(float yaw, float pitch) {
        return new RotationRequest(yaw, pitch, 1, .35F, null);
    }

    private static void cameraKeepsContinuousYawAfterRelease() {
        for (float reference : new float[] {181, -181, 541, -541, 180, -180}) {
            float camera = reference > 0 ? -179 : 179;
            if (Math.abs(reference) == 180) camera = 0;
            float previousCamera = camera - .25F;
            float rebasedCamera = RotationQuantizer.continuousYaw(reference, camera);
            float offset = rebasedCamera - camera;
            require(offset % 360 == 0, "release only changes whole turns, never visible aim");
            require(
                    Math.abs(rebasedCamera - reference) <= 180,
                    "positive, negative and multi-turn camera releases use the closest yaw domain");
            require(
                    rebasedCamera - (previousCamera + offset) == .25F,
                    "rebasing previous yaw preserves camera interpolation");

            RotationManager.reset();
            RotationManager.record(look(reference, 6), 1);
            float closingYaw = RotationQuantizer.yaw(reference, rebasedCamera, .5);
            RotationManager.record(look(closingYaw, 6), 2);
            // This is the next unmodified vanilla packet, after temporary
            // rotation has been restored. Checking only the closing packet
            // missed the old one-tick-later modulo snap.
            RotationManager.record(look(rebasedCamera, 6), 3);
            require(
                    Math.abs(RotationManager.latest().yaw() - closingYaw) <= .5F,
                    "first ordinary packet after release has no full-turn snap");
            require(
                    RotationQuantizer.continuousYaw(rebasedCamera, rebasedCamera) == rebasedCamera,
                    "already aligned camera remains unchanged");
        }
    }

    private static void nextOwnerResumesAtClosingBoundary() {
        RotationManager.reset();
        var previous = new RotationLease("Previous", 100);
        var next = new RotationLease("Next", 50);
        previous.acquire(request(42, 76), CAMERA, value -> {});
        previous.commit(new Rotation(42, 76), true, true);
        previous.pin();
        previous.release();
        int[] calls = {0};
        next.whenAvailable(
                () -> {
                    require(
                            next.acquire(
                                    request(120, 30),
                                    CAMERA,
                                    start -> {
                                        require(
                                                start.yaw() == 42 && start.pitch() == 76,
                                                "next owner inherits closing angle");
                                    }),
                            "next owner acquires in the closing tick");
                    calls[0]++;
                });
        RotationLease.resumePending();
        require(calls[0] == 0, "pending owner cannot run inside the pinned window");
        RotationManager.record(look(42, 76), 7);
        previous.unpin();
        require(calls[0] == 0, "unpin before camera restoration does not run client preparation");
        RotationLease.finishMotion();
        require(
                calls[0] == 1 && next.active() && RotationManager.latest().tick() == 7,
                "release and reacquisition share the same tick without an idle movement");
        require(
                RotationManager.latest().sequence() == 1,
                "handoff creates no synthetic movement packet");
    }

    private static ServerboundMovePlayerPacket look(float yaw, float pitch) {
        return new ServerboundMovePlayerPacket.Rot(yaw, pitch, false, false);
    }

    private static void releaseBeforeMovementResumesWithoutGap() {
        RotationManager.reset();
        var previous = new RotationLease("Previous", 100);
        var next = new RotationLease("Next", 50);
        previous.acquire(request(42, 76), CAMERA, value -> {});
        int[] calls = {0};
        next.whenAvailable(
                () -> {
                    require(
                            next.acquire(request(120, 30)),
                            "next producer can acquire before this tick's movement");
                    calls[0]++;
                });
        require(calls[0] == 0, "ordinary priority remains effective while old owner is active");
        previous.release();
        require(
                calls[0] == 1,
                "release directly resumes waiting work without FRAME or finishMotion");
        next.release();

        previous.acquire(request(42, 76), CAMERA, value -> {});
        next.whenAvailable(() -> calls[0]++);
        RotationLease.beginMotion();
        previous.release();
        require(
                calls[0] == 1,
                "temporary camera window blocks early continuation even without a submission");
        RotationLease.finishMotion();
        require(calls[0] == 2, "camera restoration immediately enables the queued continuation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
