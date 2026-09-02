package com.blanoir.moons.client.event.movement;

import net.minecraft.client.player.LocalPlayer;

/** Local-player boundary at LivingEntity.aiStep HEAD. */
public record LocalPlayerLivingTickEvent(LocalPlayer player) { }
