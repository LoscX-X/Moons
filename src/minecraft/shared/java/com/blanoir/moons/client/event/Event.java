package com.blanoir.moons.client.event;

import com.blanoir.moons.api.ScopedResources;
import com.blanoir.moons.api.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A synchronous event channel with stable priority and registration ordering.
 * Listener failures are isolated. Cancelling an event stops later listeners.
 */
public class Event<T> {
    private final String name;
    private final EventThread thread;
    private final Object registrationLock = new Object();
    private final List<RegisteredListener<T>> listeners = new ArrayList<>();
    private volatile RegisteredListener<?>[] snapshot = new RegisteredListener<?>[0];

    public Event() {
        this("unnamed", EventThread.CALLER);
    }

    public Event(String name, EventThread thread) {
        this.name = requireName(name);
        this.thread = Objects.requireNonNull(thread, "thread");
    }

    public String name() {
        return name;
    }

    public EventThread thread() {
        return thread;
    }

    public int listenerCount() {
        return snapshot.length;
    }

    public void register(Consumer<T> listener) {
        register(null, EventPriority.NORMAL, listener);
    }

    public void register(EventPriority priority, Consumer<T> listener) {
        register(null, priority, listener);
    }

    public void register(String listenerName, Consumer<T> listener) {
        register(listenerName, EventPriority.NORMAL, listener);
    }

    /** Registers a listener for the lifetime of the loading module's resource scope. */
    public void register(String listenerName, EventPriority priority, Consumer<T> listener) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(listener, "listener");
        RegisteredListener<T> registration =
                new RegisteredListener<>(readableName(listenerName, listener), priority, listener);
        synchronized (registrationLock) {
            int index = 0;
            while (index < listeners.size()
                    && listeners.get(index).priority.ordinal() <= priority.ordinal()) {
                index++;
            }
            listeners.add(index, registration);
            snapshot = listeners.toArray(new RegisteredListener<?>[0]);
        }

        AtomicBoolean closed = new AtomicBoolean();
        ScopedResources.<Subscription>own(
                () -> {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    synchronized (registrationLock) {
                        listeners.remove(registration);
                        snapshot = listeners.toArray(new RegisteredListener<?>[0]);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    public void post(T event) {
        Cancellable cancellable = event instanceof Cancellable value ? value : null;
        for (RegisteredListener<?> entry : snapshot) {
            RegisteredListener<T> registration = (RegisteredListener<T>) entry;
            try {
                registration.listener.accept(event);
            } catch (Throwable failure) {
                reportFailure(registration.name, failure);
            }
            if (cancellable != null && cancellable.isCancelled()) {
                break;
            }
        }
    }

    private void reportFailure(String listenerName, Throwable failure) {
        System.err.println(
                "[EventBus] Event '"
                        + name
                        + "' listener '"
                        + listenerName
                        + "' failed: "
                        + failure);
        failure.printStackTrace(System.err);
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Event name must not be blank");
        }
        return name;
    }

    private static String readableName(String name, Consumer<?> listener) {
        return name == null || name.isBlank() ? listener.getClass().getName() : name;
    }

    private static final class RegisteredListener<T> {
        private final String name;
        private final EventPriority priority;
        private final Consumer<T> listener;

        private RegisteredListener(String name, EventPriority priority, Consumer<T> listener) {
            this.name = name;
            this.priority = priority;
            this.listener = listener;
        }
    }
}
