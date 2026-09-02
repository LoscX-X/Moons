package com.blanoir.moons.client.event.tick;

import net.minecraft.client.Minecraft;

/** Posted by the ASM bridge at the end of a client tick. */
public record TickEndEvent(
        Minecraft client
) {
}
