package com.blanoir.moons.client.event.movement;

import net.minecraft.client.player.LocalPlayer;

/** Effective camera and outgoing rotation around LocalPlayer.sendPosition. */
public final class PlayerMotionEvent {
    private PlayerMotionEvent() {}

    public record Pre(
            LocalPlayer player,
            float cameraYaw,
            float cameraPitch,
            float outgoingYaw,
            float outgoingPitch,
            boolean overridden) {}

    public record Post(
            LocalPlayer player,
            float cameraYaw,
            float cameraPitch,
            float outgoingYaw,
            float outgoingPitch,
            boolean overridden) {}
}
