package com.blanoir.moons.client.event.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Boundaries around Player.attack for the local attacker. */
public final class AttackEntityEvent {
    private AttackEntityEvent() { }

    public record Pre(Player attacker, Entity target) { }

    public record Post(Player attacker, Entity target) { }
}
