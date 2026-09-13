package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;

/**
 * F: GodBridge compass-heading and pitch solution. Preserves sector hysteresis and side
 * selection using caller-owned state; no world reads, timing, input polling or rotation writes.
 */
public final class AimSolverF {
    private AimSolverF() {}

    /** Caller-owned compass history. Hold/reset decisions remain with the mode. */
    public static final class State {
        public boolean initialized;
        public float heading;
        public int side;
        public Rotation rotation;
    }

    public static Rotation solve(
            State state, float cameraYaw, float forward, float sideways, double x, double z) {

        float requested = cameraYaw + 180.0F;
        if (forward != 0 || sideways != 0)
            requested -= (float) Math.toDegrees(Math.atan2(sideways, forward));
        float nextHeading = Mth.wrapDegrees(Math.round(requested / 45.0F) * 45.0F);
        // Avoid toggling compass sectors when the camera hovers around a boundary.
        if (state.initialized && Math.abs(Mth.wrapDegrees(requested - state.heading)) < 27.0F)
            nextHeading = state.heading;
        boolean changed = !state.initialized || nextHeading != state.heading;
        state.heading = nextHeading;
        boolean straight = Math.floorMod(Math.round(state.heading / 45.0F), 2) == 0;
        if (straight) {
            double radians = Math.toRadians(state.heading);
            double towardCenter =
                    (Math.floor(x) + .5 - x) * Math.cos(radians)
                            + (Math.floor(z) + .5 - z) * Math.sin(radians);
            if (changed || Math.abs(towardCenter) > .08) state.side = towardCenter >= 0 ? -1 : 1;
        }
        state.rotation =
                new Rotation(
                        Mth.wrapDegrees(state.heading + (straight ? state.side * 45 : 0)),
                        straight ? 75.7F : 75.6F);
        state.initialized = true;
        return state.rotation;
    }
}
