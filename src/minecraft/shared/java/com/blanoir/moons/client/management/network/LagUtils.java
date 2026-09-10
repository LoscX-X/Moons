package com.blanoir.moons.client.management.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Bounded, thread-safe FIFO shared by manual Blink and timed lag buffers.
 * Values are retained by identity. Times are milliseconds from one caller-owned clock;
 * use {@link #nowMillis()} for new consumers, never mix clocks within a queue.
 * This class owns storage only: callers own packet policy, context and replay.
 */
public final class LagUtils<T> {
    private static final long CLOCK_ORIGIN = System.nanoTime();
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();
    private final int capacity;
    private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();

    public LagUtils(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Monotonic clock for elapsed times, unrelated to calendar time. */
    public static long nowMillis() {
        return (System.nanoTime() - CLOCK_ORIGIN) / 1_000_000L;
    }

    /** Hold until explicitly polled or drained. A held head also blocks timed successors. */
    public synchronized boolean offer(T value) {
        if (value == null || !hasCapacity()) return false;
        entries.addLast(new Entry<>(value, nowMillis(), 0L, true));
        return true;
    }

    public boolean offer(T value, long delayMillis) {
        return offer(value, nowMillis(), delayMillis);
    }

    /** A shorter later delay cannot overtake an earlier value. Negative delay means zero. */
    public synchronized boolean offer(T value, long now, long delayMillis) {
        if (value == null || !hasCapacity()) return false;
        long delay = Math.max(0L, delayMillis);
        long deadline = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
        Entry<T> last = entries.peekLast();
        if (last != null && !last.held()) deadline = Math.max(deadline, last.releaseAt());
        entries.addLast(new Entry<>(value, now, deadline, false));
        return true;
    }

    public synchronized T poll() {
        Entry<T> entry = entries.pollFirst();
        return entry == null ? null : entry.value();
    }

    public synchronized T pollDue(long now) {
        Entry<T> first = entries.peekFirst();
        return first == null || first.held() || now < first.releaseAt() ? null : poll();
    }

    public synchronized boolean hasCapacity() {
        return entries.size() < capacity;
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long age(long now) {
        Entry<T> first = entries.peekFirst();
        if (first == null || now <= first.arrival()) return 0L;
        long elapsed = now - first.arrival();
        return elapsed < 0L ? Long.MAX_VALUE : elapsed;
    }

    public synchronized List<T> snapshot() {
        return entries.stream().map(Entry::value).toList();
    }

    public List<T> drain() {
        return drain(Integer.MAX_VALUE);
    }

    /** Release a prefix; zero releases nothing. */
    public synchronized List<T> drain(int count) {
        if (count < 0) throw new IllegalArgumentException("count cannot be negative");
        List<T> result = new ArrayList<>(Math.min(count, entries.size()));
        while (result.size() < count && !entries.isEmpty()) result.add(poll());
        return result;
    }

    /** Release through the nth matching value, including all intervening values. */
    public synchronized List<T> drainThrough(int count, Predicate<? super T> matches) {
        if (count < 0) throw new IllegalArgumentException("count cannot be negative");
        Objects.requireNonNull(matches, "matches");
        // Evaluate before mutation so a failing predicate cannot lose packets.
        int prefix = 0;
        int found = 0;
        for (Entry<T> entry : entries) {
            if (found >= count) break;
            prefix++;
            if (matches.test(entry.value())) found++;
        }
        return drain(prefix);
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Shared only by lag consumers; ordinary packet events still run during replay. */
    public static boolean isReplaying() {
        return Boolean.TRUE.equals(REPLAYING.get());
    }

    /** Nested replay and exceptions preserve the enclosing thread's guard. */
    public static void replay(Runnable action) {
        Objects.requireNonNull(action, "action");
        boolean nested = isReplaying();
        REPLAYING.set(true);
        try {
            action.run();
        } finally {
            if (nested) REPLAYING.set(true);
            else REPLAYING.remove();
        }
    }

    private record Entry<T>(T value, long arrival, long releaseAt, boolean held) {}
}
