package com.blanoir.moons.ysm.internal.runtime;

public record Pair<A, B>(A first, B second) {
    public A getFirst() {
        return first;
    }

    public B getSecond() {
        return second;
    }
}
