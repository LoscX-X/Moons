package com.blanoir.moons.client.manager.network;

/** Immutable identity pair for work that must remain in the same connection/world session. */
public record SessionToken<O, C>(O owner, C context) {
    public boolean matches(O owner, C context) {
        return this.owner == owner && this.context == context;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionToken<?, ?> token
                && owner == token.owner
                && context == token.context;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(owner) + System.identityHashCode(context);
    }
}
