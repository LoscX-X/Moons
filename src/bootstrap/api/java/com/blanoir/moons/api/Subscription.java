package com.blanoir.moons.api;

/** A registration that can be deterministically removed. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    Subscription NOOP = () -> {};

    @Override
    void close();
}
