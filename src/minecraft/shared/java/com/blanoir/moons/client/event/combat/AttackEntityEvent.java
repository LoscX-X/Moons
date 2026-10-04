package com.blanoir.moons.client.event.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/** Boundaries around Player.attack for the local attacker. */
public final class AttackEntityEvent {
    private AttackEntityEvent() {}

    public record Pre(EntityPlayer attacker, Entity target) {}

    public record Post(EntityPlayer attacker, Entity target) {}
}
