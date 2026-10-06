package com.blanoir.moons.client.event.movement;

import net.minecraft.util.MovementInputFromOptions;

/** KeyboardInput.tick completion, after vanilla refreshed movement keys. */
public record MovementInputUpdatedEvent(MovementInputFromOptions input) {}
