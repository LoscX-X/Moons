package com.blanoir.moons.client.utils.rotation.smooth;

import com.blanoir.moons.client.utils.rotation.Rotation;

/**
 * A: Existing exact-angle Instant assignment used by SilentPacketRotation.
 * Preserves the supplied numeric yaw/pitch without wrapping, clamping or interpolation.
 * Completion callbacks, velocity resets and movement/use confirmation stay with the owner.
 */
public final class InstantA {
    private InstantA() {}

    public static Rotation step(Rotation desired) {
        return desired;
    }
}
