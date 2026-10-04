package com.blanoir.moons.client.management.task;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Binds an execution contract to the actual backend owner and scheduler. */
public final class ThreadDomain {
    private final String name;
    private final BooleanSupplier owner;
    private final Executor executor;

    public ThreadDomain(String name, BooleanSupplier owner, Executor executor) {
        this.name = Objects.requireNonNull(name);
        this.owner = Objects.requireNonNull(owner);
        this.executor = Objects.requireNonNull(executor);
    }

    public void requireOwner() {
        if (!owner.getAsBoolean()) throw new IllegalStateException("Wrong thread for " + name);
    }

    public void execute(Runnable action) {
        Objects.requireNonNull(action);
        executor.execute(
                () -> {
                    requireOwner();
                    action.run();
                });
    }

    /** Checks at execution time: stopping an executor does not retract an already queued action. */
    public void execute(TaskScope.Token lifetime, Runnable action) {
        Objects.requireNonNull(lifetime);
        execute(
                () -> {
                    if (lifetime.valid()) action.run();
                });
    }
}
