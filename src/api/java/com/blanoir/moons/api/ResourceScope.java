package com.blanoir.moons.api;

/** Owns every resource acquired by one dynamically loaded module. */
public interface ResourceScope extends AutoCloseable {
    <T extends AutoCloseable> T own(T resource);

    boolean isClosed();

    @Override
    void close();
}
