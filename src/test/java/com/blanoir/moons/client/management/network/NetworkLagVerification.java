package com.blanoir.moons.client.management.network;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Deterministic queue and transport contracts, without a server or game window. */
public final class NetworkLagVerification {
    private NetworkLagVerification() {}

    public static void main(String[] args) throws Exception {
        verifyTiming();
        verifyBlink();
        verifyReplay();
        verifyConnection();
        verifyConcurrency();
        System.out.println("Network lag verification passed");
    }

    private static void verifyTiming() {
        LagUtils<String> lag = new LagUtils<>(3);
        require(!lag.offer(null), "Null never consumes capacity");
        require(lag.offer("first", 1000, 150), "First timed packet");
        require(lag.offer("second", 1020, 10), "Later shorter delay");
        require(lag.offer("third", 1030, 0), "Zero delay still respects predecessors");
        require(!lag.offer("overflow"), "Overflow stays with caller");
        require(lag.pollDue(1149) == null && lag.age(1149) == 149, "No early replay");
        require("first".equals(lag.pollDue(1150)), "First deadline");
        require("second".equals(lag.pollDue(1150)), "No overtaking or extra pacing");
        require("third".equals(lag.pollDue(1150)) && lag.isEmpty(), "Whole due prefix");
        lag.offer("held");
        lag.offer("timed", 1000, 0);
        require(lag.pollDue(Long.MAX_VALUE) == null, "Blink blocks timed successors");
        require(lag.drain().equals(List.of("held", "timed")), "Manual flush preserves order");
        lag.offer("old", 2000, 100);
        lag.clear();
        lag.offer("new", 1000, -1);
        require("new".equals(lag.pollDue(1000)), "Clear removes old deadlines");
        lag.offer("overflow-time", Long.MAX_VALUE - 5, 100);
        require(lag.pollDue(Long.MAX_VALUE - 1) == null, "Overflow does not release early");
        require(lag.pollDue(Long.MAX_VALUE) != null, "Saturated deadline is reachable");
        lag.offer("negative-clock", -100, 20);
        require(
                lag.pollDue(-81) == null && lag.pollDue(-80) != null,
                "Caller clock may be negative");
    }

    private static void verifyBlink() {
        PacketBlink blink = new PacketBlink(5);
        TestPacket action = new TestPacket(1);
        TestPacket movement = new TestPacket(2);
        TestPacket last = new TestPacket(3);
        blink.offer(action);
        blink.offer(movement);
        blink.offer(last);
        List<Packet<?>> snapshot = blink.snapshot();
        require(blink.drainThrough(0, p -> true).isEmpty() && blink.size() == 3, "Zero is a no-op");
        List<Packet<?>> prefix = blink.drainThrough(1, p -> p == movement);
        require(
                prefix.size() == 2 && prefix.getFirst() == action && prefix.getLast() == movement,
                "Movement flush retains preceding action identity");
        require(snapshot.size() == 3 && blink.drain(1).getFirst() == last, "Detached snapshot");
        require(blink.drain().isEmpty(), "No duplicate replay");
        blink.offer(last);
        try {
            blink.drainThrough(
                    1,
                    p -> {
                        throw new IllegalStateException("predicate");
                    });
            throw new AssertionError("Predicate exception expected");
        } catch (IllegalStateException expected) {
            require(blink.size() == 1, "Predicate failure cannot lose the packet");
        }
        blink.clear();
        require(blink.isEmpty(), "Discard does not replay");
    }

    private static void verifyReplay() throws InterruptedException {
        require(!LagUtils.isReplaying(), "Initially outside replay");
        LagUtils.replay(
                () -> {
                    require(LagUtils.isReplaying(), "Guard visible to all lag consumers");
                    try {
                        LagUtils.replay(
                                () -> {
                                    throw new IllegalStateException("send");
                                });
                    } catch (IllegalStateException expected) {
                        require(LagUtils.isReplaying(), "Nested failure preserves outer guard");
                    }
                    AtomicBoolean otherReplaying = new AtomicBoolean(true);
                    Thread other = new Thread(() -> otherReplaying.set(LagUtils.isReplaying()));
                    other.start();
                    try {
                        other.join();
                        require(!otherReplaying.get(), "Guard is thread local");
                    } catch (InterruptedException exception) {
                        throw new AssertionError(exception);
                    }
                });
        require(!LagUtils.isReplaying(), "Guard cleaned after replay");
    }

    private static void verifyConnection() {
        PacketDelayQueue queue = new PacketDelayQueue(3);
        PacketDelayQueue other = new PacketDelayQueue(3);
        TestConnection first = new TestConnection();
        TestConnection second = new TestConnection();
        Object world = new Object();
        TestPacket a = new TestPacket(1);
        TestPacket b = new TestPacket(2);
        require(!queue.offer(a), "No connection means no cancellation");
        queue.observe(first, world);
        other.observe(first, world);
        queue.offer(a);
        queue.offer(b);
        first.observer =
                packet -> {
                    require(LagUtils.isReplaying(), "Normal send observes replay scope");
                    require(
                            !other.offer(packet) && !queue.offer(packet),
                            "No recapture by any lag queue");
                    queue.flush();
                };
        queue.flush(1);
        require(first.sent.equals(List.of(a)) && queue.size() == 1, "Bounded and recursive flush");
        queue.flush();
        require(first.sent.equals(List.of(a, b)) && queue.isEmpty(), "FIFO exactly once");
        first.observer = packet -> {};
        queue.offer(a);
        queue.observe(second, world);
        queue.flush();
        require(second.sent.isEmpty(), "Connection replacement discards stale packets");
        queue.offer(a);
        queue.observe(second, new Object());
        queue.flush();
        require(second.sent.isEmpty(), "World replacement discards stale packets");
        queue.offer(a);
        second.connected = false;
        queue.flush();
        require(queue.isEmpty() && second.sent.isEmpty(), "Disconnect discards without sending");
        second.connected = true;
        queue.observe(second, world);
        queue.offer(a, 0);
        queue.offer(b, 60_000);
        queue.flushDue();
        require(second.sent.equals(List.of(a)) && queue.size() == 1, "Only due prefix is sent");
        queue.flush();
        require(second.sent.equals(List.of(a, b)), "Forced flush ignores delay");
        second.sent.clear();
        queue.offer(a);
        queue.offer(b);
        second.observer = packet -> queue.observe(first, new Object());
        queue.flush();
        require(
                second.sent.equals(List.of(a)) && queue.isEmpty(),
                "Context change during replay stops batch");
        queue.observe(first, world);
        queue.offer(a);
        queue.offer(b);
        first.observer =
                packet -> {
                    throw new IllegalStateException("transport");
                };
        try {
            queue.flush();
            throw new AssertionError("Send exception expected");
        } catch (IllegalStateException expected) {
            require(
                    !LagUtils.isReplaying() && queue.size() == 1,
                    "Send failure cleans guard and retains unsent tail");
        }
        queue.discard();
        require(!queue.offer(a), "Discard releases connection ownership");
        queue.observe(second, world);
        queue.offer(b);
        queue.flushClient(null);
        require(
                queue.isEmpty() && second.sent.equals(List.of(a)),
                "Missing client discards before a settings flush");
    }

    private static void verifyConcurrency() throws InterruptedException {
        LagUtils<Integer> lag = new LagUtils<>(128);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int offset = t * 1000;
            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int i = 0; i < 1000; i++)
                                        if (lag.offer(offset + i)) accepted.incrementAndGet();
                                } catch (InterruptedException exception) {
                                    throw new AssertionError(exception);
                                }
                            });
            threads.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        List<Integer> retained = lag.drain();
        require(
                accepted.get() == 128
                        && retained.size() == 128
                        && new HashSet<>(retained).size() == 128,
                "Concurrent producers respect capacity and identity");
    }

    private record TestPacket(int id) implements Packet<PacketListener> {
        @Override
        public PacketType<? extends Packet<PacketListener>> type() {
            return null;
        }

        @Override
        public void handle(PacketListener listener) {}
    }

    private static final class TestConnection extends Connection {
        private boolean connected = true;
        private final List<Packet<?>> sent = new ArrayList<>();
        private Consumer<Packet<?>> observer = packet -> {};

        private TestConnection() {
            super(PacketFlow.CLIENTBOUND);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void send(Packet<?> packet) {
            sent.add(packet);
            observer.accept(packet);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
