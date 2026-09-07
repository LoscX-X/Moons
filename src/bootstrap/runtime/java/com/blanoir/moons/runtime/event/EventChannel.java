package com.blanoir.moons.runtime.event;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.Subscription;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Thread-safe event channel with deterministic unregistration. */
public final class EventChannel<T> {
    private final CopyOnWriteArrayList<Consumer<? super T>> listeners =
            new CopyOnWriteArrayList<>();

    public Subscription subscribe(Consumer<? super T> listener) {
        Consumer<? super T> checked = Objects.requireNonNull(listener, "listener");
        listeners.add(checked);
        return new ChannelSubscription(checked);
    }

    public void publish(T event) {
        for (Consumer<? super T> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable failure) {
                System.err.println(Branding.prefix() + " Event listener failed: " + failure);
                failure.printStackTrace(System.err);
            }
        }
    }

    public int listenerCount() {
        return listeners.size();
    }

    private final class ChannelSubscription implements Subscription {
        private Consumer<? super T> listener;

        private ChannelSubscription(Consumer<? super T> listener) {
            this.listener = listener;
        }

        @Override
        public synchronized void close() {
            if (listener != null) {
                listeners.remove(listener);
                listener = null;
            }
        }
    }
}
