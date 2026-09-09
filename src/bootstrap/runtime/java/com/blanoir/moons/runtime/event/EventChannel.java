package com.blanoir.moons.runtime.event;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.Subscription;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Thread-safe event channel with deterministic unregistration. */
public final class EventChannel<T> {
    private volatile Registration<?>[] listeners = new Registration<?>[0];

    public Subscription subscribe(Consumer<? super T> listener) {
        return subscribe(null, listener);
    }

    /** Filters keyed hooks before their event object and boxed values are allocated. */
    public synchronized Subscription subscribe(
            Predicate<String> filter, Consumer<? super T> listener) {
        Registration<T> registration =
                new Registration<>(filter, Objects.requireNonNull(listener, "listener"));
        Registration<?>[] current = listeners;
        Registration<?>[] updated = java.util.Arrays.copyOf(current, current.length + 1);
        updated[current.length] = registration;
        listeners = updated;
        return new ChannelSubscription(registration);
    }

    public boolean hasListeners(String key) {
        for (Registration<?> registration : listeners) {
            try {
                if (registration.filter == null || registration.filter.test(key)) return true;
            } catch (Throwable failure) {
                // Dispatch handles the failure in the normal listener-isolation boundary.
                return true;
            }
        }
        return false;
    }

    public void publish(T event) {
        publish(null, event);
    }

    @SuppressWarnings("unchecked")
    public void publish(String key, T event) {
        for (Registration<?> entry : listeners) {
            Registration<T> registration = (Registration<T>) entry;
            try {
                if (key == null || registration.filter == null || registration.filter.test(key)) {
                    registration.listener.accept(event);
                }
            } catch (Throwable failure) {
                System.err.println(Branding.prefix() + " Event listener failed: " + failure);
                failure.printStackTrace(System.err);
            }
        }
    }

    public int listenerCount() {
        return listeners.length;
    }

    private record Registration<T>(Predicate<String> filter, Consumer<? super T> listener) {}

    private final class ChannelSubscription implements Subscription {
        private Registration<T> listener;

        private ChannelSubscription(Registration<T> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            synchronized (EventChannel.this) {
                if (listener == null) return;
                Registration<?>[] current = listeners;
                for (int index = 0; index < current.length; index++) {
                    if (current[index] != listener) continue;
                    Registration<?>[] updated = new Registration<?>[current.length - 1];
                    System.arraycopy(current, 0, updated, 0, index);
                    System.arraycopy(
                            current, index + 1, updated, index, current.length - index - 1);
                    listeners = updated;
                    break;
                }
                listener = null;
            }
        }
    }
}
