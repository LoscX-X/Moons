package com.blanoir.moons.client.access;

/** Private-member resolution boundaries; availability does not imply a feature is enabled. */
public enum GameCapability {
    INPUT,
    INTERACTION,
    PACKET,
    RENDER,
    HUD;

    public record Status(GameCapability capability, Throwable failure) {
        public boolean available() {
            return failure == null;
        }
    }
}
