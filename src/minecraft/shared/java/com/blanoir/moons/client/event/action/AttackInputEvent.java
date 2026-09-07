package com.blanoir.moons.client.event.action;

import com.blanoir.moons.client.event.Cancellable;

import net.minecraft.client.Minecraft;

/** Boundaries around Minecraft's attack-input action. */
public final class AttackInputEvent {
    private AttackInputEvent() {}

    public static final class Pre implements Cancellable {
        private final Minecraft client;
        private boolean cancelled;

        public Pre(Minecraft client) {
            this.client = client;
        }

        public Minecraft client() {
            return client;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** Finally-style boundary; it also runs when PRE cancelled the vanilla action. */
    public record Post(Minecraft client) {}
}
