package com.blanoir.moons.client.event.movement;

import net.minecraft.client.entity.EntityPlayerSP;

/** Local-player boundary at LivingEntity.aiStep HEAD. */
public record LocalPlayerLivingTickEvent(EntityPlayerSP player) {}
