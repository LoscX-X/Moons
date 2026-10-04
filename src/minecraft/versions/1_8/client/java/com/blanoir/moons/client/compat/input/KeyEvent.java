package com.blanoir.moons.client.compat.input;

/** Snapshot of a LWJGL keyboard event used only inside Moons. */
public record KeyEvent(int key, int scancode, int modifiers) {}
