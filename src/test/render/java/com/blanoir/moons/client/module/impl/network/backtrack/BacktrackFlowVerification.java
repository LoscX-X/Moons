package com.blanoir.moons.client.module.impl.network.backtrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class BacktrackFlowVerification {
    public static void main(String[] args) {
        for (var mode :
                new BacktrackConfig.TargetMode[] {
                    BacktrackConfig.TargetMode.ATTACK, BacktrackConfig.TargetMode.RANGE
                }) {
            require(!mode.acceptsAttackAge(-1, 1000), "No history before the first attack");
            require(mode.acceptsAttackAge(1000, 1000), "Current attack window is accepted");
            require(!mode.acceptsAttackAge(1001, 1000), "Expired window cannot rearm");
        }
        require(
                BacktrackConfig.TargetMode.INTENT.acceptsAttackAge(-1, 1000),
                "Intent mode can start without attack");
        var queue = new BacktrackPacketQueue<Integer>(64);
        var played = new ArrayList<Integer>();
        queue.start(60);
        queue.offer(1, 0);
        queue.offer(2, 20);
        queue.offer(3, 40);
        queue.releaseDue(59, played::add);
        require(played.isEmpty(), "No packet plays early");
        queue.releaseDue(60, played::add);
        require(played.equals(List.of(1)), "First packet retains its arrival deadline");
        queue.drain(60);
        queue.drain(70);
        queue.offer(4, 70);
        queue.releaseDue(80, played::add);
        require(played.equals(List.of(1, 2)), "Catch-up advances the original clock");
        queue.releaseDue(87, played::add);
        require(played.equals(List.of(1, 2, 3)), "Catch-up preserves cadence instead of flushing");
        queue.releaseDue(180, played::add);
        require(queue.drained(180), "Repeated drain did not extend catch-up");
        require(played.equals(List.of(1, 2, 3, 4)), "New arrivals share the same FIFO");
        queue.releaseAll(played::add);
        require(played.size() == 4, "Repeated release cannot replay a packet twice");

        // Continuous input, burst arrivals, repeated frame/tick pumps, and repeated exits.
        for (int seed = 0; seed < 100; seed++) {
            var random = new Random(seed);
            queue.start(50 + random.nextInt(21));
            played.clear();
            List<Integer> offered = new ArrayList<>();
            int sequence = 0;
            for (long now = 0; now <= 1000; now++) {
                if (now >= 500) queue.drain(now);
                if (random.nextInt(5) == 0) {
                    offered.add(sequence);
                    require(queue.offer(sequence++, now), "Bounded normal traffic fits");
                }
                queue.releaseDue(now, played::add);
                queue.releaseDue(now, played::add);
                require(offered.subList(0, played.size()).equals(played), "Exact FIFO prefix");
                if (now >= 640) require(queue.isEmpty(), "Catch-up reaches live input under load");
            }
            require(played.equals(offered), "No loss or duplication after catch-up");
        }
        var small = new BacktrackPacketQueue<Integer>(2);
        small.start(70);
        require(small.offer(1, 0) && small.offer(2, 1) && !small.offer(3, 2), "Bounded overflow");
        try {
            small.start(50);
            throw new AssertionError("New history cannot overlap queued history");
        } catch (IllegalStateException expected) {
        }
        played.clear();
        small.releaseAll(played::add);
        require(played.equals(List.of(1, 2)), "Boundary uses the same ordered consumer");
        small.start(50);
        small.offer(4, 10);
        small.clear();
        small.releaseAll(played::add);
        require(played.equals(List.of(1, 2)), "Context discard never replays into the new world");
        System.out.println("MOONS_BACKTRACK_FLOW_VERIFIED");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
