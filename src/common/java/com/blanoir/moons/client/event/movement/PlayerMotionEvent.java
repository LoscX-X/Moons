package com.blanoir.moons.client.event.movement;

import net.minecraft.client.entity.EntityPlayerSP;

/** Effective camera and outgoing rotation around LocalPlayer.sendPosition. */
public final class PlayerMotionEvent {
    private PlayerMotionEvent() {}

    public record Pre(
            EntityPlayerSP player,
            float cameraYaw,
            float cameraPitch,
            float outgoingYaw,
            float outgoingPitch,
            boolean overridden) {}

    public record Post(
            EntityPlayerSP player,
            float cameraYaw,
            float cameraPitch,
            float outgoingYaw,
            float outgoingPitch,
            boolean overridden) {}
}
