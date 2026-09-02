package net.minecraft.client;

/** Minimal transformation fixture; deliberately has no Fabric dependency. */
public final class Minecraft {
    private long ticks;

    public void tick() {
        ticks++;
    }

    public long ticks() {
        return ticks;
    }
}
