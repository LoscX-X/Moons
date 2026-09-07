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
    public synchronized <T extends AutoCloseable> T own(T resource) {
        T checked = Objects.requireNonNull(resource, "resource");
        if (closed) {
            closeQuietly(checked);
            throw new IllegalStateException("Resource scope is already closed");
        }
        resources.push(checked);
        return checked;
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException aggregate = null;
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (Exception failure) {
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
        } catch (Exception ignored) {
        }
    }
}
