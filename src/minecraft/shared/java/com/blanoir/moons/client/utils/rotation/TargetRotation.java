package com.blanoir.moons.client.utils.rotation;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

public record TargetRotation(
        EntityLivingBase entity,
        Vec3 aimPoint,
        Rotation rotation,
        double angle,
        double distanceSquared) {}
