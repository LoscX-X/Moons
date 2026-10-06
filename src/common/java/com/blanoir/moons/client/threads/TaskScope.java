package com.blanoir.moons.client.threads;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns background work. Cancellation and actual worker termination are distinct states. */
public final class TaskScope implements AutoCloseable {
    public enum State {
        ACCEPTING,
        STOPPING,
        TERMINATED
    }

    public record Token(TaskScope owner, long generation) {
        public boolean valid() {
            return owner.valid(generation);
        }
    }

    private final String name;
    private static final ThreadLocal<TaskScope> CURRENT = new ThreadLocal<>();
    private final ExecutorService executor;
    private final int capacity;
    private final Map<CompletableFuture<?>, FutureTask<?>> outstanding = new IdentityHashMap<>();
    private boolean closed;
    private long generation;

    /** Creates a serial worker owned and closed by this scope. */
    public static TaskScope serial(String name, String workerName, int capacity) {
        return new TaskScope(
                name,
                Executors.newSingleThreadExecutor(ThreadFactories.daemon(workerName)),
                capacity);
    }

    public TaskScope(String name, ExecutorService executor, int capacity) {
        this.name = Objects.requireNonNull(name);
        this.executor = Objects.requireNonNull(executor);
        if (capacity <= 0) throw new IllegalArgumentException("Task capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized Token token() {
        return new Token(this, generation);
    }

    private synchronized boolean valid(long expected) {
        return !closed && generation == expected;
    }

    public synchronized void invalidateResults() {
        generation++;
    }

    /** Invalidates one activation while leaving the worker available for the next one. */
    public void cancelOutstanding() {
        ArrayList<CompletableFuture<?>> work;
        synchronized (this) {
            generation++;
            work = new ArrayList<>(outstanding.keySet());
        }
        // Cancel delivery before interrupting computation; an interrupted supplier may return.
        for (CompletableFuture<?> task : work) task.cancel(false);
    }

    public synchronized int pending() {
        return outstanding.size();
    }

    public synchronized State state() {
        return !closed
                ? State.ACCEPTING
                : executor.isTerminated() ? State.TERMINATED : State.STOPPING;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> computation) {
        return submit(computation, ignored -> {});
    }

    /** The disposer owns results that cannot be delivered after cancellation. */
    public <T> CompletableFuture<T> submit(Supplier<T> computation, Consumer<T> disposer) {
        Objects.requireNonNull(computation);
        Objects.requireNonNull(disposer);
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> work =
                new FutureTask<>(
                        () -> {
                            if (result.isCancelled()) return null;
                            TaskScope previous = CURRENT.get();
                            CURRENT.set(this);
                            try {
                                T value = computation.get();
                                if (!result.complete(value)) {
                                    try {
                                        disposer.accept(value);
                                    } catch (Throwable failure) {
                                        System.err.println(
                                                "[client] "
                                                        + name
                                                        + " cancelled result cleanup failed: "
                                                        + failure);
                                    }
                                }
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            } finally {
                                if (previous == null) CURRENT.remove();
                                else CURRENT.set(previous);
                            }
                            return null;
                        }) {
                    @Override
                    protected void done() {
                        synchronized (TaskScope.this) {
                            outstanding.remove(result);
                        }
                        if (isCancelled()) result.cancel(false);
                    }
                };
        synchronized (this) {
            if (closed || outstanding.size() >= capacity) {
                throw new RejectedExecutionException(name + (closed ? " is stopping" : " is full"));
            }
            outstanding.put(result, work);
        }
        result.whenComplete(
                (value, failure) -> {
                    if (result.isCancelled()) work.cancel(true);
                });
        try {
            executor.execute(work);
        } catch (RuntimeException failure) {
            work.cancel(false);
            throw failure;
        }
        return result;
    }

    /** Does not wait on the client/render thread or claim that interruption stopped a worker. */
    @Override
    public void close() {
        ArrayList<CompletableFuture<?>> work;
        synchronized (this) {
            if (closed) return;
            closed = true;
            generation++;
            work = new ArrayList<>(outstanding.keySet());
        }
        for (CompletableFuture<?> task : work) task.cancel(false);
        executor.shutdownNow();
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        if (CURRENT.get() == this)
            throw new IllegalStateException(name + " cannot await its own worker");
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative timeout");
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
