package com.blanoir.moons.client.management.task;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Stops new dispatch without reordering registrations or pretending an in-flight call was cancelled. */
public final class ActivationGate {
    private volatile boolean active;

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    public boolean active() {
        return active;
    }

    public <T> Consumer<T> guard(Consumer<T> action) {
        Objects.requireNonNull(action);
        return event -> {
            if (active) action.accept(event);
        };
    }

    public Predicate<String> guardFilter(Predicate<String> filter) {
        Objects.requireNonNull(filter);
        return key -> active && filter.test(key);
    }
}
