package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/** Bounded camera return using the shortest yaw arc, including across -180/180. */
public final class ScaffoldTurn {
    private ScaffoldTurn() {}

    public static Rotation step(Rotation from, Rotation to, double degrees) {
        return new Rotation(
                from.yaw()
                        + (float)
                                Mth.clamp(
                                        Mth.wrapDegrees(to.yaw() - from.yaw()), -degrees, degrees),
                from.pitch() + (float) Mth.clamp(to.pitch() - from.pitch(), -degrees, degrees));
    }
}
