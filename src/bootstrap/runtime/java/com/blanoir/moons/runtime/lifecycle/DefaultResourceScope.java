package com.blanoir.moons.runtime.lifecycle;

import com.blanoir.moons.api.ResourceScope;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Closes module-owned resources once, in reverse acquisition order. */
public final class DefaultResourceScope implements ResourceScope {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private boolean closed;

    @Override
    public <T extends AutoCloseable> T own(T resource) {
        T checked = Objects.requireNonNull(resource, "resource");
        synchronized (this) {
            if (!closed) {
                resources.push(checked);
                return checked;
            }
        }
        closeQuietly(checked);
        throw new IllegalStateException("Resource scope is already closed");
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        Deque<AutoCloseable> acquired;
        synchronized (this) {
            if (closed) return;
            closed = true;
            acquired = new ArrayDeque<>(resources);
            resources.clear();
        }
        RuntimeException aggregate = null;
        while (!acquired.isEmpty()) {
            try {
                acquired.pop().close();
            } catch (Throwable failure) {
                if (aggregate == null) {
                    aggregate =
                            new RuntimeException("One or more module resources failed to close");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) throw aggregate;
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Throwable ignored) {
        }
    }
}
