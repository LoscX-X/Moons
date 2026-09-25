package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.management.network.LagPacketPolicy;
import com.blanoir.moons.client.management.network.LagUtils;
import com.blanoir.moons.client.management.network.PacketDelayQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.List;

/** Exercises timed delivery, gradual catch-up and connection/action boundaries. */
public final class AutoSpearFakeLagVerification {
    public static void main(String[] args) {
        verifyTimedDelivery();
        var queue = new PacketDelayQueue(2);
        var connection = new RecordingConnection(queue);
        Object world = new Object();
        queue.observe(connection, world);
        Packet<?> first = new ServerboundMovePlayerPacket.Pos(1, 64, 1, true, false);
        Packet<?> second = new ServerboundMovePlayerPacket.Rot(45, 0, true, false);
        Packet<?> stab = new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN);

        require(queue.offer(first) && queue.offer(second), "Movement is buffered");
        require(connection.sent.isEmpty(), "Buffered movement is not sent early");
        require(queue.isFull() && !queue.offer(first), "Capacity bounds pending movement");
        require(LagPacketPolicy.mustFlushBefore(stab), "STAB must follow buffered movement");
        queue.flush();
        connection.send(stab);
        require(connection.sent.equals(List.of(first, second, stab)),
                "Original position/rotation packets precede STAB in FIFO order");
        queue.flush();
        require(connection.sent.size() == 3, "Repeated flush does not replay twice");
        require(!LagUtils.isReplaying(), "Replay guard is restored");

        require(!LagPacketPolicy.mustFlushBefore(new ServerboundKeepAlivePacket(1)),
                "Keepalive can pass without ending the movement window");
        require(LagPacketPolicy.mustFlushBefore(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                        BlockPos.ZERO, Direction.DOWN)),
                "Releasing use sends earlier movement first");

        queue.offer(first);
        queue.observe(connection, new Object());
        queue.flush();
        require(connection.sent.size() == 3, "World changes discard stale positions");
        queue.offer(second);
        queue.discard();
        queue.flush();
        require(connection.sent.size() == 3, "Corrections discard rather than replay old movement");

        queue.observe(connection, world);
        queue.offer(first);
        connection.connected = false;
        queue.flush();
        require(queue.isEmpty() && connection.sent.size() == 3,
                "Disconnect discards pending packets");
        System.out.println("MOONS_AUTOSPEAR_FAKELAG_VERIFIED");
    }

    private static void verifyTimedDelivery() {
        var timing = new AutoSpearFakeLag.Timing();
        var queue = new LagUtils<String>(32);
        timing.start(0, 150, 500);
        for (long now = 0; now <= 800; now += 50)
            require(queue.offer("move-" + now, now, timing.delayAt(now)), "Capture timed movement");

        require(queue.pollDue(149) == null, "First packet waits its full delay");
        require("move-0".equals(queue.pollDue(150)), "First packet is released at 150 ms");
        require(queue.pollDue(150) == null, "Next packet is not released in the same batch");
        require("move-50".equals(queue.pollDue(200)), "Movement keeps its original cadence");
        for (long source = 100; source <= 500; source += 50)
            require(("move-" + source).equals(queue.pollDue(source + 150)), "Fixed-delay FIFO");

        require(queue.pollDue(674) == null, "Catch-up packet retains a distinct deadline");
        for (long source = 550, due = 675; source <= 800; source += 50, due += 25) {
            require(("move-" + source).equals(queue.pollDue(due)), "Catch up progressively at 2x");
            require(queue.pollDue(due) == null, "No final bulk release or overtaking");
        }
        require(queue.isEmpty() && !timing.active(800), "Catch-up drains and ends");
        require(timing.delayAt(800) == 0, "Normal sends resume without residual delay");
        timing.clear();
        require(!timing.active(0) && timing.delayAt(0) == 0, "Reset clears the timing window");
    }

    private static final class RecordingConnection extends Connection {
        private final PacketDelayQueue queue;
        private final List<Packet<?>> sent = new ArrayList<>();
        private boolean connected = true;

        private RecordingConnection(PacketDelayQueue queue) {
            super(PacketFlow.CLIENTBOUND);
            this.queue = queue;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void send(Packet<?> packet) {
            if (LagUtils.isReplaying())
                require(!queue.offer(packet), "Replayed packets cannot be buffered again");
            sent.add(packet);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
