package com.blanoir.tempos.client.module.impl.combat.aim;

import com.blanoir.tempos.client.utils.rotation.Rotation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public record TargetRotation(
        LivingEntity entity,
        Vec3 aimPoint,
        Rotation rotation,
        double angle,
        double distanceSquared) {}
