package com.blanoir.moons.client.event;

/**
 * Documents the thread on which an event is expected to be posted.
 * Dispatch is always synchronous and never switches threads automatically.
 */
public enum EventThread {
    CLIENT,
    RENDER,
    NETWORK,
    CALLER
}
