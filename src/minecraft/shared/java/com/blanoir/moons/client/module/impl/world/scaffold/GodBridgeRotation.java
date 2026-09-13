package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverF;

/** Compass-aligned bridge view, held in the air and between placement opportunities. */
public final class GodBridgeRotation {
    private final AimSolverF.State state = new AimSolverF.State();

    public Rotation update(
            float cameraYaw, float forward, float sideways, double x, double z, boolean grounded) {
        if (state.initialized && (!grounded || forward == 0 && sideways == 0))
            return state.rotation;
        return AimSolverF.solve(state, cameraYaw, forward, sideways, x, z);
    }

    public void reset() {
        state.initialized = false;
        state.rotation = null;
    }
}
