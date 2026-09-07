package com.blanoir.moons.client.event;

/** Marks an event whose cancellation stops later typed listeners and its hook action. */
public interface Cancellable {
    boolean isCancelled();

    void cancel();
}
