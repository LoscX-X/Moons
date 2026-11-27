package com.blanoir.tempos.client.event.movement;

import net.minecraft.client.player.KeyboardInput;

/** KeyboardInput.tick completion, after vanilla refreshed movement keys. */
public record MovementInputUpdatedEvent(KeyboardInput input) {}
