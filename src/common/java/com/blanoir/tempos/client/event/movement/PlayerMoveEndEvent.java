package com.blanoir.tempos.client.event.movement;

import net.minecraft.client.player.LocalPlayer;

/** End of the local moveRelative call, after temporary movement yaw is restored. */
public record PlayerMoveEndEvent(LocalPlayer player) {}
