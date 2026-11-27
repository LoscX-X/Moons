package com.blanoir.tempos.client.module.framework;

import net.minecraft.client.Minecraft;

/** A component owned by a feature. Only its parent has a catalog entry. */
public abstract class SubFeature<P extends ClientFeature> {
    private final P parent;

    protected SubFeature(P parent) {
        this.parent = parent;
    }

    public final P parent() {
        return parent;
    }

    public boolean isActive() {
        return parent.isEnabled();
    }

    public void registerEvents() {}

    /** Some components have a switch with its own settings or reset behavior. */
    public abstract static class Switchable<P extends ClientFeature> extends SubFeature<P> {
        protected Switchable(P parent) {
            super(parent);
        }

        @Override
        public abstract boolean isActive();

        public abstract int setActive(Minecraft client, boolean active);
    }
}
