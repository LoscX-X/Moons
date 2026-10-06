package com.blanoir.moons.client.threads;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;

/** Named daemon workers; the caller retains queue, capacity, priority and shutdown ownership. */
public final class ThreadFactories {
    private ThreadFactories() {}

    public static ThreadFactory daemon(String name) {
        Objects.requireNonNull(name);
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
