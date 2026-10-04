package com.blanoir.moons.client.management.task;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Owner capture, worker computation, owner validation and explicit stale-result disposal. */
public final class SnapshotJob<I, R> implements AutoCloseable {
    private final ThreadDomain owner;
    private final TaskScope.Token lifetime;
    private final I input;
    private final Predicate<I> current;
    private final Consumer<R> disposer;
    private final CompletableFuture<R> result;
    private final AtomicBoolean consumed = new AtomicBoolean();

    public SnapshotJob(
            ThreadDomain owner,
            TaskScope tasks,
            Supplier<I> capture,
            Function<I, R> computation,
            Predicate<I> current,
            Consumer<R> disposer) {
        this.owner = Objects.requireNonNull(owner);
        owner.requireOwner();
        this.lifetime = tasks.token();
        this.input = Objects.requireNonNull(capture.get());
        this.current = Objects.requireNonNull(current);
        this.disposer = Objects.requireNonNull(disposer);
        this.result = tasks.submit(() -> computation.apply(input), disposer);
    }

    public boolean ready() {
        return result.isDone();
    }

    /** Transfers a valid result once. The caller controls its application budget. */
    public Optional<R> take() {
        owner.requireOwner();
        if (!ready() || !consumed.compareAndSet(false, true)) return Optional.empty();
        R value;
        try {
            value = result.join();
        } catch (CancellationException cancelled) {
            return Optional.empty();
        }
        if (value == null) return Optional.empty();
        boolean valid;
        try {
            valid = lifetime.valid() && current.test(input);
        } catch (RuntimeException | Error failure) {
            try {
                disposer.accept(value);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        if (!valid) {
            disposer.accept(value);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    @Override
    public void close() {
        if (!consumed.compareAndSet(false, true)) return;
        if (result.cancel(false)) return;
        if (!result.isCompletedExceptionally()) {
            R value = result.getNow(null);
            if (value != null) disposer.accept(value);
        }
    }
}
