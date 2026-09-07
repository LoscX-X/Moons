package com.blanoir.moons.api;

import java.util.Objects;

/** Binds static listener registrations to the module currently being loaded. */
public final class ScopedResources {
    private static final ThreadLocal<ResourceScope> CURRENT = new ThreadLocal<>();

    private ScopedResources() {}

    public static <T extends AutoCloseable> T own(T resource) {
        ResourceScope scope = CURRENT.get();
        return scope == null ? resource : scope.own(resource);
    }

    public static void run(ResourceScope scope, CheckedRunnable action) throws Exception {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Nested module resource scopes are not supported");
        }
        CURRENT.set(scope);
        try {
            action.run();
        } finally {
            CURRENT.remove();
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
