package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothD;

/** Bounded camera return using the shortest yaw arc, including across -180/180. */
public final class ScaffoldTurn {
    private ScaffoldTurn() {}

    public static Rotation step(Rotation from, Rotation to, double degrees) {
        return SmoothD.boundedTurn(from, to, degrees);
    }
}
