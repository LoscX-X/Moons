package com.blanoir.moons.client.manager.network;

/** Owner-confined session state; the transport still decides when to invalidate and discard. */
final class PacketSession<O, C> {
    private O owner;
    private C context;
    private long generation;

    boolean matches(O owner, C context) {
        return this.owner == owner && this.context == context;
    }

    void bind(O owner, C context) {
        this.owner = owner;
        this.context = context;
    }

    O owner() {
        return owner;
    }

    long generation() {
        return generation;
    }

    void invalidate() {
        generation++;
    }
}
