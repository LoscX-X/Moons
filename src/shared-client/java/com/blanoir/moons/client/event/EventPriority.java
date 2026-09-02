package com.blanoir.moons.client.event;

/** Listener order from the earliest dispatch phase to the latest. */
public enum EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST
}
